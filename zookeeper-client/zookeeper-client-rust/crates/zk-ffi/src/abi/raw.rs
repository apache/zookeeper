// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Centralized raw memory translation boundary and unsafe invariant encapsulation.
//!
//! All memory conversions and pointer dereferences spanning the C/Rust FFI boundary are isolated
//! within this module. Outside this subsystem, `zk-ffi` prohibits direct invocations of
//! pointer-to-slice conversions (`slice::from_raw_parts`), raw C string dereferencing
//! (`CStr::from_ptr`), pointer reconstruction (`Box::from_raw`), or unverified vtable function
//! calls. Each `unsafe` block is restricted to a single primitive pointer operation governed by
//! explicit `SAFETY` rationale.
//!
//! Function signatures exposed to neighboring modules remain safe by construction. The FFI contract
//! dictates that C callers provide valid, aligned memory buffers for the duration of a synchronous
//! call, maintaining an active reference count on handle instances. Supplying a null pointer where
//! non-null references are mandatory triggers an immediate process abort to preclude undefined
//! behavior.
//!
//! Incoming memory payloads are copied into owned Rust buffers or borrowed with lifetimes strictly
//! bounded by the synchronous call frame. Outgoing data writes utilize raw pointer writes
//! (`ptr::write`) rather than constructing `&mut T` references into C-managed memory, thereby
//! preventing undefined behavior due to potential external aliasing.
//!
//! This module encapsulates pointer validation, handle and outbox memory transformations, and host
//! vtable dispatch. Outgoing result marshaling is partitioned into the [`writers`] submodule.

pub(crate) mod writers;

use std::ffi::{CStr, c_char, c_void};
use std::net::SocketAddr;
use std::slice;
use std::sync::Arc;

use zeroize::Zeroizing;
use zk_driver::Unlocked;
use zk_proto::SecretBytes;

use super::entry::{abort_with, ffi_guard};
use super::handle::Inner;
use super::outbox::FfiOutbox;
use super::types::{
    zkrs_bytes, zkrs_handle, zkrs_host_vtable, zkrs_outbox, zkrs_sockaddr_view, zkrs_watcher,
};

/// Encapsulates invalid pointer conditions or buffer length violations encountered at the C ABI
/// boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RawError {
    /// A null pointer was passed alongside a positive element or byte count.
    NullData,
    /// A buffer payload descriptor specified an illegal length (< -1) or a null pointer with
    /// positive length.
    BadValue,
    /// A length specification exceeded addressable memory limits (greater than `isize::MAX`).
    TooLong,
}

/// Constructs a borrowed byte slice reference from a raw pointer and length.
///
/// A length of zero returns an empty slice `&[]` regardless of the pointer value. A null pointer
/// accompanied by a positive length returns [`RawError::NullData`]. Callers must copy or encode
/// the borrowed slice before the enclosing synchronous FFI call returns.
pub(crate) fn bytes<'a>(p: *const u8, len: usize) -> Result<&'a [u8], RawError> {
    if len == 0 {
        return Ok(&[]);
    }
    if p.is_null() {
        return Err(RawError::NullData);
    }
    if isize::try_from(len).is_err() {
        return Err(RawError::TooLong);
    }
    // SAFETY: C guarantees `len` readable bytes at the non-null pointer `p` for the duration
    // of this call. The length is bounded by `isize::MAX` and byte alignment is 1.
    Ok(unsafe { slice::from_raw_parts(p, len) })
}

/// Constructs a borrowed byte slice from a legacy C `(ptr, i32)` buffer pair.
///
/// A length of -1 indicates a null payload (`None`) and ignores the pointer. A length of 0
/// yields an empty slice. Positive lengths return borrowed byte slice views. Negative lengths below
/// -1 or null pointers with positive lengths yield [`RawError::BadValue`], which translates to an
/// input defect during serialization.
///
/// The borrowed slice is encoded directly into outgoing wire frames during the synchronous call;
/// no borrowed views outlive the call boundary.
pub(crate) fn value_ref<'a>(p: *const c_char, len: i32) -> Result<Option<&'a [u8]>, RawError> {
    match len {
        -1 => Ok(None),
        0 => Ok(Some(&[])),
        n if n < -1 => Err(RawError::BadValue),
        n => {
            let n = usize::try_from(n).map_err(|_| RawError::BadValue)?;
            match bytes(p.cast::<u8>(), n) {
                Ok(b) => Ok(Some(b)),
                Err(_) => Err(RawError::BadValue),
            }
        }
    }
}

/// Copies a NUL-terminated C string into an owned byte vector, returning `None` if the input
/// pointer is null.
pub(crate) fn cstr_opt(p: *const c_char) -> Option<Vec<u8>> {
    cstr_ref(p).map(<[u8]>::to_vec)
}

/// Borrows a NUL-terminated C string as a byte slice without intermediate heap allocations.
///
/// Returns `None` if `p` is null. Borrowed references do not outlive the synchronous FFI
/// invocation.
pub(crate) fn cstr_ref<'a>(p: *const c_char) -> Option<&'a [u8]> {
    if p.is_null() {
        return None;
    }
    // SAFETY: A non-null C string pointer passed across the private ABI contract is guaranteed
    // to be valid, readable, and properly NUL-terminated for the duration of the call.
    let s = unsafe { CStr::from_ptr(p) };
    Some(s.to_bytes())
}

/// Copies a NUL-terminated sensitive string directly into a zeroizing heap buffer.
///
/// Used for TLS passphrase strings. Pre-allocating the exact capacity avoids leaving secondary
/// copies of sensitive passphrase material across memory.
#[cfg_attr(
    not(feature = "tls"),
    allow(dead_code, reason = "only a TLS build reads a passphrase")
)]
pub(crate) fn cstr_secret(p: *const c_char) -> Option<Zeroizing<Vec<u8>>> {
    if p.is_null() {
        return None;
    }
    // SAFETY: The string pointer satisfies the same validity and NUL-termination invariants as in
    // `cstr_ref`.
    let s = unsafe { CStr::from_ptr(p) };
    Some(Zeroizing::new(s.to_bytes().to_vec()))
}

/// Converts a `zkrs_bytes` C buffer descriptor into an owned, zeroizing `SecretBytes` container.
///
/// An explicit `is_null` flag indicates an absent value (`None`). Inconsistent configurations
/// (such as `is_null` set alongside non-zero length or non-null pointers, or lengths exceeding
/// `0x7fffffff`) yield [`RawError::BadValue`] or [`RawError::TooLong`].
pub(crate) fn view_bytes(v: &zkrs_bytes) -> Result<Option<SecretBytes>, RawError> {
    if v.is_null != 0 {
        if v.len != 0 || !v.p.is_null() {
            return Err(RawError::BadValue);
        }
        return Ok(None);
    }
    if v.len > 0x7fff_ffff {
        return Err(RawError::TooLong);
    }
    let n = usize::try_from(v.len).map_err(|_| RawError::TooLong)?;
    let b = bytes(v.p, n)?;
    Ok(Some(SecretBytes::new(b.to_vec())))
}

/// Constructs a borrowed slice of `repr(C)` records from a raw pointer and element count.
///
/// Counts less than or equal to zero return an empty slice `&[]`. A null pointer accompanied
/// by a positive count returns [`RawError::NullData`].
pub(crate) fn array<'a, T>(p: *const T, count: i32) -> Result<&'a [T], RawError> {
    let n = array_len(p, count)?;
    if n == 0 {
        return Ok(&[]);
    }
    // SAFETY: C guarantees `count` initialized, properly aligned records of type `T` at the
    // non-null pointer `p` for this call, fitting within `isize::MAX` addressable bytes.
    Ok(unsafe { slice::from_raw_parts(p, n) })
}

/// Validates array bounds and returns the element count without constructing references to
/// individual elements.
///
/// Accommodates partially initialized structures (such as `zoo_op_t` unions where only the variant
/// matching the operation type is validly populated) without risking undefined behavior.
pub(crate) fn array_len<T>(p: *const T, count: i32) -> Result<usize, RawError> {
    let Ok(n) = usize::try_from(count) else {
        return Ok(0);
    };
    if n == 0 {
        return Ok(0);
    }
    if p.is_null() {
        return Err(RawError::NullData);
    }
    let fits = n
        .checked_mul(size_of::<T>())
        .is_some_and(|b| isize::try_from(b).is_ok());
    if !fits {
        return Err(RawError::TooLong);
    }
    Ok(n)
}

/// Allocates an owned, NUL-terminated boxed byte slice suitable for C consumers.
pub(crate) fn c_bytes(b: &[u8]) -> Box<[u8]> {
    let mut v = Vec::with_capacity(b.len().saturating_add(1));
    v.extend_from_slice(b);
    v.push(0);
    v.into_boxed_slice()
}

/// Converts a raw handle pointer received from C into a new strong `Arc<Inner>` reference.
///
/// Increments the reference count of the `Arc` originally created in `zkrs_handle_new` before
/// reconstructing the smart pointer. A null pointer triggers an immediate abort.
pub(crate) fn handle_arc(p: *const zkrs_handle) -> Arc<Inner> {
    let p = p.cast::<Inner>();
    if p.is_null() {
        abort_with("zk-ffi: NULL zkrs_handle");
    }
    // SAFETY: `p` originated from `Arc::into_raw` in `handle_into_raw`, and the C caller
    // holds an active strong reference ensuring the reference count remains positive.
    unsafe { Arc::increment_strong_count(p) };
    // SAFETY: The explicit increment above grants this reconstructed `Arc` its own owned reference.
    unsafe { Arc::from_raw(p) }
}

/// Transfers ownership of a strong handle reference across the FFI boundary as a raw pointer.
pub(crate) fn handle_into_raw(h: Arc<Inner>) -> *const zkrs_handle {
    Arc::into_raw(h).cast::<zkrs_handle>()
}

/// Releases one strong handle reference held by the C runtime.
///
/// Invoked by `zkrs_handle_release` and `zkrs_handle_abandon`. A null pointer triggers an immediate
/// abort.
pub(crate) fn handle_release(p: *const zkrs_handle) {
    let p = p.cast::<Inner>();
    if p.is_null() {
        abort_with("zk-ffi: NULL zkrs_handle");
    }
    // SAFETY: `p` originated from `handle_into_raw`, and the caller relinquishes one strong
    // reference it legitimately owns.
    unsafe { Arc::decrement_strong_count(p) };
}

/// Stores the outbox in `ob->impl` as an opaque C pointer.
///
/// Transfers the heap allocation via `Box::into_raw` without performing integer-to-pointer casts.
/// A null outbox container pointer aborts execution.
pub(crate) fn outbox_into_impl(ob: *mut zkrs_outbox, b: Box<FfiOutbox>) {
    let p = Box::into_raw(b).cast::<c_void>();
    write_out(ob, zkrs_outbox { r#impl: p });
}

/// Takes ownership of the outbox stored in `ob->impl` and clears the pointer to null.
///
/// Returns `None` if `impl` is null. The caller assumes sole ownership of the returned box
/// and is responsible for either restoring it via [`outbox_into_impl`] or dropping it.
pub(crate) fn outbox_take(ob: *mut zkrs_outbox) -> Option<Box<FfiOutbox>> {
    let cur = read_struct(ob.cast_const());
    let p = cur.r#impl.cast::<FfiOutbox>();
    if p.is_null() {
        return None;
    }
    write_out(
        ob,
        zkrs_outbox {
            r#impl: std::ptr::null_mut(),
        },
    );
    // SAFETY: A non-null `impl` pointer originates strictly from `outbox_into_impl`.
    // Clearing the pointer to null guarantees unique ownership for the returned `Box`.
    Some(unsafe { Box::from_raw(p) })
}

/// Borrows the outbox located in `ob->impl` for the duration of a synchronous FFI call.
///
/// Returns `None` if `impl` is null. Because client outbox draining is serialized on a single
/// thread at a time, no conflicting concurrent references exist.
pub(crate) fn outbox_from_impl<'a>(ob: *mut zkrs_outbox) -> Option<&'a mut FfiOutbox> {
    let cur = read_struct(ob.cast_const());
    let p = cur.r#impl.cast::<FfiOutbox>();
    // SAFETY: A non-null `impl` pointer originates from `outbox_into_impl`. The calling thread
    // holds exclusive access to `ob` for the duration of the call, satisfying aliasing invariants.
    unsafe { p.as_mut() }
}

/// Writes value `v` to out-parameter `dst` via raw pointer write.
///
/// Performs a direct pointer write without constructing mutable Rust references into C-managed
/// memory, avoiding potential aliasing violations. A null pointer triggers an immediate abort.
pub(crate) fn write_out<T: Copy>(dst: *mut T, v: T) {
    if dst.is_null() {
        abort_with("zk-ffi: NULL out-parameter");
    }
    // SAFETY: C callers provide a valid, properly aligned, writable memory location for type `T`.
    unsafe { dst.write(v) };
}

/// Reads a copy of a `repr(C)` structure passed by pointer.
///
/// Pointers embedded within the resulting copy must be dereferenced through the audited readers
/// above. A null pointer triggers an immediate abort.
pub(crate) fn read_struct<T: Copy>(p: *const T) -> T {
    if p.is_null() {
        abort_with("zk-ffi: NULL input struct");
    }
    // SAFETY: C callers provide a valid, properly aligned, and fully initialized memory location
    // for type `T`.
    unsafe { p.read() }
}

/// Resolved network socket addresses and bypassed address families accumulated during DNS
/// resolution.
#[derive(Debug, Default)]
pub(crate) struct HostAddrs {
    /// Successfully resolved IPv4 and IPv6 socket addresses, preserving resolution order and
    /// duplicates.
    pub(crate) addrs: Vec<SocketAddr>,
    /// Protocol family constants (`ai_family`) for unsupported address families encountered during
    /// resolution.
    pub(crate) skipped_families: Vec<i32>,
}

/// Aborts process execution when encountering an unpopulated host vtable function pointer.
fn missing(entry: &str) -> ! {
    abort_with(&format!("zk-ffi: missing host vtable entry {entry}"))
}

/// Invokes the `discard_token` vtable function pointer for a completed or aborted asynchronous
/// token.
pub(crate) fn vt_discard_token_fn(f: unsafe extern "C" fn(usize), token: usize) {
    // SAFETY: Function pointer `f` represents the immutable `discard_token` callback provided
    // during handle creation and accepts any token associated with this handle exactly once.
    unsafe { f(token) }
}

/// Discards an asynchronous token via the host vtable without triggering completion callbacks.
#[cfg(test)]
pub(crate) fn vt_discard_token(vt: &zkrs_host_vtable, token: usize) {
    let Some(f) = vt.discard_token else {
        missing("discard_token")
    };
    vt_discard_token_fn(f, token);
}

/// Queries the host vtable to obtain a snapshot of the handle's default session watcher
/// configuration.
pub(crate) fn vt_snapshot_default_watcher(vt: &zkrs_host_vtable, host: usize) -> zkrs_watcher {
    let Some(f) = vt.snapshot_default_watcher else {
        missing("snapshot_default_watcher")
    };
    let mut w = zkrs_watcher::default();
    // SAFETY: The host function writes a single `zkrs_watcher` structure to `out`, which points
    // to a valid stack local, and performs only non-destructive reads on host state.
    unsafe { f(host, &raw mut w) };
    w
}

/// Persists negotiated session credentials (identifier and password) into the host client state.
pub(crate) fn vt_store_client_id(vt: &zkrs_host_vtable, host: usize, id: i64, passwd: &[u8; 16]) {
    let Some(f) = vt.store_client_id else {
        missing("store_client_id")
    };
    // SAFETY: The host function reads exactly 16 bytes from `passwd`, which remains valid and
    // pinned.
    unsafe { f(host, id, passwd.as_ptr()) };
}

/// Queries the host logging subsystem to determine whether a given log level is active.
pub(crate) fn vt_log_enabled(vt: &zkrs_host_vtable, level: u32) -> bool {
    let Some(f) = vt.log_enabled else {
        missing("log_enabled")
    };
    // SAFETY: The host function performs a nonblocking read against exported logging configuration
    // state.
    unsafe { f(level) != 0 }
}

/// Queries whether server ensemble connection randomization is disabled via
/// `zoo_deterministic_conn_order`.
pub(crate) fn vt_conn_permute_disabled(vt: &zkrs_host_vtable) -> bool {
    let Some(f) = vt.conn_permute_disabled else {
        missing("conn_permute_disabled")
    };
    // SAFETY: The host function reads process-global configuration without concurrency conflicts.
    unsafe { f() != 0 }
}

/// Invokes the host runtime's legacy PRNG (`random()`) implementation.
pub(crate) fn vt_rng_random(vt: &zkrs_host_vtable) -> i64 {
    let Some(f) = vt.rng_random else {
        missing("rng_random")
    };
    // SAFETY: The host function executes libc `random()` without accessing Rust synchronization
    // primitives.
    let r = unsafe { f() };
    #[allow(clippy::useless_conversion, reason = "c_long is i32 on Windows")]
    i64::from(r)
}

/// Invokes the host runtime's double-precision PRNG (`drand48()`) implementation.
pub(crate) fn vt_rng_drand48(vt: &zkrs_host_vtable) -> f64 {
    let Some(f) = vt.rng_drand48 else {
        missing("rng_drand48")
    };
    // SAFETY: The host function executes libc `drand48()` without accessing Rust synchronization
    // primitives.
    unsafe { f() }
}

/// Reseeds host PRNG state from system entropy without holding synchronization locks.
pub(crate) fn vt_rng_reseed(vt: &zkrs_host_vtable, _u: &Unlocked) {
    let Some(f) = vt.rng_reseed else {
        missing("rng_reseed")
    };
    // SAFETY: The host function reads `/dev/urandom` and seeds libc state; the `Unlocked`
    // witness confirms that no internal Rust mutexes are held during execution.
    unsafe { f() };
}

/// Synchronously resolves a hostname via `getaddrinfo` through the host vtable without holding
/// locks.
///
/// Dispatches resolved socket addresses to `out` via `resolve_push` and `resolve_skip`, returning
/// `(errno, gai_rc)`.
pub(crate) fn vt_resolve(
    vt: &zkrs_host_vtable,
    _u: &Unlocked,
    name: &CStr,
    port: &CStr,
    out: &mut HostAddrs,
) -> (i32, i32) {
    let Some(f) = vt.resolve else {
        missing("resolve")
    };
    let mut gai_rc: i32 = 0;
    let sink = std::ptr::from_mut(out).cast::<c_void>();
    // SAFETY: The host function reads valid hostname/port strings, writes resolution status to
    // `gai_rc`, and delivers callbacks to `sink` prior to returning. The `Unlocked` witness
    // guarantees that neither the protocol `core` lock nor any leaf lock is held.
    let err = unsafe {
        f(
            name.as_ptr(),
            port.as_ptr(),
            &raw mut gai_rc,
            Some(resolve_push),
            Some(resolve_skip),
            sink,
        )
    };
    (err, gai_rc)
}

/// Reconstructs a mutable reference to the active [`HostAddrs`] accumulator from an untyped
/// callback context pointer.
pub(crate) fn sink_from_ptr<'a>(sink: *mut c_void) -> &'a mut HostAddrs {
    let p = sink.cast::<HostAddrs>();
    if p.is_null() {
        abort_with("zk-ffi: NULL resolve sink");
    }
    // SAFETY: `sink` is the `&mut HostAddrs` of the executing `vt_resolve` frame, which remains
    // pinned and unmodified elsewhere until resolution completes.
    unsafe { &mut *p }
}

/// Host resolution callback invoked for each resolved IPv4 or IPv6 socket address.
///
/// cbindgen:ignore
extern "C" fn resolve_push(sink: *mut c_void, addr: *const zkrs_sockaddr_view) {
    ffi_guard(|| {
        let v = read_struct(addr);
        if let Some(a) = v.to_addr() {
            sink_from_ptr(sink).addrs.push(a);
        }
    });
}

/// Host resolution callback invoked for unrecognized or unsupported address families.
///
/// cbindgen:ignore
extern "C" fn resolve_skip(sink: *mut c_void, family: i32) {
    ffi_guard(|| sink_from_ptr(sink).skipped_families.push(family));
}

#[cfg(test)]
pub(crate) mod tests;
