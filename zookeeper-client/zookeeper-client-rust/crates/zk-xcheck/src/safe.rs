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

//! Safe abstraction layer wrapping the C differential testing harness.
//!
//! The compiled C test harness maintains extensive process wide mutable state,
//! including mock pseudo-random number feeds, an in-memory diagnostic log ring buffer,
//! DNS resolution records, address vector buffers, and watcher dispatch tables.
//! To prevent race conditions and cross test interference during parallel test
//! execution, access to this C state is strictly serialized via a global re-entrancy lock.
//!
//! The primary entry point is [`with_c`], which acquires exclusive access to the
//! harness and provides a transient [`CApi`] reference to a user-provided closure.
//! Derived data structures borrowing from C-allocated memory are tied to the
//! lifetime of this reference, preventing memory safety violations or dangling
//! references after the lock is released.

#![allow(unsafe_code)]

use std::cell::{Cell, RefCell};
use std::ffi::{CStr, CString, c_char, c_int, c_long, c_uint, c_ushort, c_void};
use std::marker::PhantomData;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::ptr;
use std::sync::{Mutex, PoisonError};

use crate::ffi;

static LOCK: Mutex<()> = Mutex::new(());

/// Executes a closure with exclusive, serialized access to the underlying C harness.
///
/// Acquires the global harness mutex to ensure only one thread interacts with
/// the process-global C state at any time. Lock poisoning from earlier test panics
/// is recovered automatically to prevent cascading failures across the test suite.
pub fn with_c<R>(f: impl FnOnce(&CApi) -> R) -> R {
    let _guard = LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    // SAFETY: Querying host character signedness via `zkx_char_is_signed` reads static compiler
    // definitions and carries no preconditions.
    let native_signed = unsafe { ffi::zkx_char_is_signed() } != 0;
    let api = CApi {
        feeds: RefCell::new(Feeds::default()),
        zt_signed: Cell::new(native_signed),
        _not_send: PhantomData,
    };
    f(&api)
}

#[derive(Default)]
struct Feeds {
    random: Vec<c_long>,
    drand: Vec<f64>,
}

/// Thread-exclusive handle providing safe access to legacy C harness operations.
///
/// Encapsulates operations on the C runtime state while the global harness lock
/// is held. Instances of this type cannot be sent across threads or outlive
/// the invocation scope established by [`with_c`].
pub struct CApi {
    feeds: RefCell<Feeds>,
    /// Indicates whether watcher table driver operations (`zt_*`) target the signed
    /// or unsigned `char` compilation variant of the C implementation.
    zt_signed: Cell<bool>,
    _not_send: PhantomData<*const ()>,
}

/// Function pointer dispatch table for a specific compilation variant of the C watcher driver.
struct ZtFns {
    reset: unsafe extern "C" fn(),
    set_default: unsafe extern "C" fn(c_int, usize),
    set_chroot: unsafe extern "C" fn(*const c_char),
    activate: unsafe extern "C" fn(c_int, *const c_char, c_int, usize),
    event: unsafe extern "C" fn(c_int, *const c_char) -> c_int,
    dlv_get: unsafe extern "C" fn(
        c_int,
        *mut c_int,
        *mut usize,
        *mut c_int,
        *mut *const c_char,
    ) -> c_int,
    has: unsafe extern "C" fn(*const c_char, c_int, c_int, usize) -> c_int,
    remove: unsafe extern "C" fn(*const c_char, c_int, c_int, usize),
    #[allow(clippy::type_complexity)]
    dump: unsafe extern "C" fn(
        c_int,
        *mut *const c_char,
        c_int,
        *mut c_int,
        *mut c_int,
        *mut c_int,
        *mut usize,
        c_int,
    ) -> c_int,
}

/// Watcher table driver symbols compiled assuming a signed `char` representation.
const ZT_SIGNED: ZtFns = ZtFns {
    reset: ffi::zts::reset,
    set_default: ffi::zts::set_default,
    set_chroot: ffi::zts::set_chroot,
    activate: ffi::zts::activate,
    event: ffi::zts::event,
    dlv_get: ffi::zts::dlv_get,
    has: ffi::zts::has,
    remove: ffi::zts::remove,
    dump: ffi::zts::dump,
};

/// Watcher table driver symbols compiled assuming an unsigned `char` representation.
const ZT_UNSIGNED: ZtFns = ZtFns {
    reset: ffi::ztu::reset,
    set_default: ffi::ztu::set_default,
    set_chroot: ffi::ztu::set_chroot,
    activate: ffi::ztu::activate,
    event: ffi::ztu::event,
    dlv_get: ffi::ztu::dlv_get,
    has: ffi::ztu::has,
    remove: ffi::ztu::remove,
    dump: ffi::ztu::dump,
};

impl Drop for CApi {
    fn drop(&mut self) {
        // SAFETY: Passing a null pointer with length 0 disengages the C mock feed buffer so
        // subsequent C calls do not read deallocated vector memory.
        unsafe { ffi::zkx_set_random_feed(ptr::null(), 0) };
        // SAFETY: Disengages the C mock `drand48` feed buffer prior to releasing backing storage.
        unsafe { ffi::zkx_set_drand_feed(ptr::null(), 0) };
    }
}

/// Captured diagnostic log entry recorded by the C client runtime.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LogLine {
    /// Severity level string (`ERROR`, `WARN`, `INFO`, or `DEBUG`).
    pub level: String,
    /// Unformatted printf format string passed to the C logger.
    pub fmt: String,
    /// Formatted log message payload rendered by the C logger.
    pub msg: Vec<u8>,
}

/// Single watcher callback invocation dispatched by the legacy C runtime.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Delivery {
    /// Numeric identifier for the registered watcher function pointer (`0..=3`).
    pub func: i32,
    /// User-supplied context token registered with the watcher callback.
    pub ctx: usize,
    /// ZooKeeper event type code triggering the notification (e.g., `CREATED_EVENT_DEF`).
    pub event_type: i32,
    /// ZooKeeper znode path delivered to the watcher callback.
    pub path: Vec<u8>,
}

/// Znode path and its ordered list of registered watcher descriptors `(func_id, context)`
/// within a C watcher table.
pub type ZtEntry = (Vec<u8>, Vec<(i32, usize)>);

/// Converts a byte slice into an owned [`CString`], truncating at the first NUL byte to
/// match C string semantics.
fn cstring(bytes: &[u8]) -> CString {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    CString::new(bytes.get(..end).unwrap_or_default()).unwrap_or_default()
}

/// Copies the bytes of a NUL-terminated C string into a `Vec<u8>`, returning an empty vector
/// if `p` is null.
///
/// # Safety
///
/// If non-null, `p` must point to a valid NUL-terminated C string that remains allocated and
/// unmodified for the duration of this call.
unsafe fn copy_cstr(p: *const c_char) -> Vec<u8> {
    if p.is_null() {
        return Vec::new();
    }
    // SAFETY: Caller guarantees `p` is non-null and points to a valid NUL-terminated C string.
    unsafe { CStr::from_ptr(p) }.to_bytes().to_vec()
}

fn to_c_int(v: usize) -> c_int {
    c_int::try_from(v).unwrap_or(c_int::MAX)
}

fn to_c_uint(v: usize) -> c_uint {
    c_uint::try_from(v).unwrap_or(c_uint::MAX)
}

fn decode_addr(family: c_int, ip: &[u8; 16], port: c_ushort) -> Option<SocketAddr> {
    match family {
        4 => {
            let v4 = Ipv4Addr::new(ip[0], ip[1], ip[2], ip[3]);
            Some(SocketAddr::new(IpAddr::V4(v4), port))
        }
        6 => Some(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(*ip)), port)),
        _ => None,
    }
}

/// Safe wrapper around the legacy ZooKeeper C client hash table implementation.
///
/// Encapsulates a raw `hashtable` pointer storing NUL-terminated string keys and integer
/// values. Lifetime `'a` ties the table to the [`CApi`] harness lock guard from which it
/// was allocated.
pub struct CHashTable<'a> {
    h: *mut c_void,
    _api: PhantomData<&'a CApi>,
}

impl Drop for CHashTable<'_> {
    fn drop(&mut self) {
        // SAFETY: `self.h` was allocated via `zkx_ht_new` and is freed exclusively on drop.
        unsafe { ffi::zkx_ht_free(self.h) };
    }
}

impl CHashTable<'_> {
    /// Inserts a key-value pair into the hash table (`hashtable_insert`), permitting duplicate
    /// keys per legacy C semantics.
    pub fn insert(&mut self, key: &[u8], value: i32) -> bool {
        let k = cstring(key);
        // SAFETY: `self.h` is a valid table pointer and `k` is a NUL-terminated C string.
        unsafe { ffi::zkx_ht_insert(self.h, k.as_ptr(), value) != 0 }
    }

    /// Searches the hash table for `key` (`hashtable_search`), returning its associated value
    /// if present.
    pub fn search(&self, key: &[u8]) -> Option<i32> {
        let k = cstring(key);
        let mut out: c_int = 0;
        // SAFETY: `self.h` is a valid table pointer, `k` is NUL-terminated, and `out` is valid.
        let found = unsafe { ffi::zkx_ht_search(self.h, k.as_ptr(), &mut out) };
        (found != 0).then_some(out)
    }

    /// Removes the first entry matching `key` (`hashtable_remove`), returning its value if
    /// found.
    pub fn remove(&mut self, key: &[u8]) -> Option<i32> {
        let k = cstring(key);
        let mut out: c_int = 0;
        // SAFETY: `self.h` is a valid table pointer, `k` is NUL-terminated, and `out` is valid.
        let found = unsafe { ffi::zkx_ht_remove(self.h, k.as_ptr(), &mut out) };
        (found != 0).then_some(out)
    }

    /// Returns the number of entries currently stored in the hash table.
    pub fn count(&self) -> u32 {
        // SAFETY: `self.h` points to an initialized C hash table.
        unsafe { ffi::zkx_ht_count(self.h) }
    }

    /// Returns the bucket count of the hash table's backing array.
    pub fn table_length(&self) -> u32 {
        // SAFETY: `self.h` points to an initialized C hash table.
        unsafe { ffi::zkx_ht_table_length(self.h) }
    }

    /// Returns the entry count threshold that triggers a table resize.
    pub fn load_limit(&self) -> u32 {
        // SAFETY: `self.h` points to an initialized C hash table.
        unsafe { ffi::zkx_ht_load_limit(self.h) }
    }

    /// Computes the hash value for `key` using the table's configured hash function.
    pub fn hash(&self, key: &[u8]) -> u32 {
        let k = cstring(key);
        // SAFETY: `self.h` is a valid table pointer and `k` is a NUL-terminated C string.
        unsafe { ffi::zkx_ht_hash(self.h, k.as_ptr()) }
    }

    /// Returns all key-value entries in C hash table iterator traversal order.
    pub fn dump(&self) -> Vec<(Vec<u8>, i32)> {
        let n = usize::try_from(self.count()).unwrap_or(0);
        let mut keys: Vec<*const c_char> = vec![ptr::null(); n];
        let mut vals: Vec<c_int> = vec![0; n];
        // SAFETY: `keys` and `vals` are preallocated with capacity `n`.
        let got =
            unsafe { ffi::zkx_ht_dump(self.h, keys.as_mut_ptr(), vals.as_mut_ptr(), to_c_int(n)) };
        let got = usize::try_from(got).unwrap_or(0).min(n);
        keys.iter()
            .zip(vals.iter())
            .take(got)
            // SAFETY: Key pointers reference live C table strings valid for this immediate copy.
            .map(|(&k, &v)| (unsafe { copy_cstr(k) }, v))
            .collect()
    }
}

impl CApi {
    /// Returns `true` if the host C compiler treats unqualified `char` as signed.
    pub fn char_is_signed(&self) -> bool {
        // SAFETY: Pure query of a compiler-defined property with no preconditions.
        unsafe { ffi::zkx_char_is_signed() != 0 }
    }

    /// Installs a deterministic return sequence for C `random()` calls and resets the call
    /// counter. Once exhausted, subsequent `random()` calls return `0`.
    pub fn set_random_feed(&self, vals: &[i64]) {
        let v: Vec<c_long> = vals
            .iter()
            .map(|&x| c_long::try_from(x).unwrap_or(0))
            .collect();
        // SAFETY: `v` is stored in `self.feeds` without reallocation, keeping its buffer valid
        // until replaced or cleared on drop.
        unsafe { ffi::zkx_set_random_feed(v.as_ptr(), v.len()) };
        self.feeds.borrow_mut().random = v;
    }

    /// Returns the number of C `random()` calls since the last feed reset.
    pub fn random_calls(&self) -> usize {
        // SAFETY: Reads an internal counter under the harness lock with no preconditions.
        unsafe { ffi::zkx_random_calls() }
    }

    /// Installs a deterministic return sequence for C `drand48()` calls and resets the call
    /// counter. Once exhausted, subsequent `drand48()` calls return `0.0`.
    pub fn set_drand_feed(&self, vals: &[f64]) {
        let v = vals.to_vec();
        // SAFETY: `v` is stored in `self.feeds` without reallocation, keeping its buffer valid
        // until replaced or cleared on drop.
        unsafe { ffi::zkx_set_drand_feed(v.as_ptr(), v.len()) };
        self.feeds.borrow_mut().drand = v;
    }

    /// Returns the number of C `drand48()` calls since the last feed reset.
    pub fn drand_calls(&self) -> usize {
        // SAFETY: Reads an internal counter under the harness lock with no preconditions.
        unsafe { ffi::zkx_drand_calls_get() }
    }

    /// Returns the cumulative count of `setup_random()` invocations in the C runtime.
    // Explicit widening conversion supports targets where `c_ulong` is 32 bits.
    #[allow(clippy::useless_conversion)]
    pub fn setup_random_count(&self) -> u64 {
        // SAFETY: Reads an internal counter under the harness lock with no preconditions.
        u64::from(unsafe { ffi::zkx_setup_random_count() })
    }

    /// Returns the cumulative count of `close_zsock()` invocations in the C runtime.
    // Explicit widening conversion supports targets where `c_ulong` is 32 bits.
    #[allow(clippy::useless_conversion)]
    pub fn close_count(&self) -> u64 {
        // SAFETY: Reads an internal counter under the harness lock with no preconditions.
        u64::from(unsafe { ffi::zkx_close_count() })
    }

    /// Clears all captured entries from the C diagnostic log buffer.
    pub fn log_clear(&self) {
        // SAFETY: Resetting the log ring buffer is safe while holding the harness lock.
        unsafe { ffi::zkx_log_clear() };
    }

    /// Returns all diagnostic log entries captured by the C runtime since the last clear.
    pub fn logs(&self) -> Vec<LogLine> {
        // SAFETY: Reads the log entry count under the harness lock.
        let n = unsafe { ffi::zkx_log_count() };
        let mut out = Vec::new();
        for i in 0..n {
            let mut level: *const c_char = ptr::null();
            let mut fmt: *const c_char = ptr::null();
            let mut msg: *const c_char = ptr::null();
            // SAFETY: Out-pointers reference valid stack variables.
            let ok = unsafe { ffi::zkx_log_get(i, &mut level, &mut fmt, &mut msg) };
            if ok == 0 {
                break;
            }
            // SAFETY: Returned pointers reference static or ring-buffer strings valid while the
            // harness lock is held.
            let level = unsafe { copy_cstr(level) };
            // SAFETY: Format string pointer remains valid under the harness lock.
            let fmt = unsafe { copy_cstr(fmt) };
            // SAFETY: Message buffer pointer remains valid under the harness lock.
            let msg = unsafe { copy_cstr(msg) };
            out.push(LogLine {
                level: String::from_utf8_lossy(&level).into_owned(),
                fmt: String::from_utf8_lossy(&fmt).into_owned(),
                msg,
            });
        }
        out
    }

    /// Clears recorded `getaddrinfo` invocations from the harness resolver log.
    pub fn gai_clear(&self) {
        // SAFETY: Resetting the resolver call buffer is safe under the harness lock.
        unsafe { ffi::zkx_gai_clear() };
    }

    /// Returns recorded `getaddrinfo` invocations as `(host, service)` byte string pairs.
    pub fn gai_calls(&self) -> Vec<(Vec<u8>, Vec<u8>)> {
        // SAFETY: Reads the recorded call count under the harness lock.
        let n = unsafe { ffi::zkx_gai_count() };
        let mut out = Vec::new();
        for i in 0..n {
            let mut host: *const c_char = ptr::null();
            let mut service: *const c_char = ptr::null();
            // SAFETY: Out-pointers reference valid stack variables.
            let ok = unsafe { ffi::zkx_gai_get(i, &mut host, &mut service) };
            if ok == 0 {
                break;
            }
            // SAFETY: Host string pointer references harness storage valid under the lock.
            let host = unsafe { copy_cstr(host) };
            // SAFETY: Service string pointer references harness storage valid under the lock.
            let service = unsafe { copy_cstr(service) };
            out.push((host, service));
        }
        out
    }

    /// Allocates a C hash table using the DJB2 hash function with the specified `char`
    /// signedness.
    pub fn ht_new(&self, min_size: u32, c_char_signed: bool) -> Option<CHashTable<'_>> {
        // SAFETY: `zkx_ht_new` returns a newly allocated table pointer or null on failure.
        let h = unsafe { ffi::zkx_ht_new(min_size, c_int::from(!c_char_signed)) };
        (!h.is_null()).then_some(CHashTable {
            h,
            _api: PhantomData,
        })
    }

    /// Computes the DJB2 string hash using signed or unsigned `char` arithmetic.
    pub fn djb2(&self, key: &[u8], c_char_signed: bool) -> u32 {
        let k = cstring(key);
        let p = k.as_ptr().cast_mut().cast::<c_void>();
        if c_char_signed {
            // SAFETY: `p` points to a valid NUL-terminated C string read immutably by DJB2.
            unsafe { ffi::zkx_djb2_signed(p) }
        } else {
            // SAFETY: `p` points to a valid NUL-terminated C string read immutably by DJB2.
            unsafe { ffi::zkx_djb2_unsigned(p) }
        }
    }

    /// Resets and frees the C harness address vector (`addrvec_free`).
    pub fn av_clear(&self) {
        // SAFETY: Resetting the harness address vector is safe under the harness lock.
        unsafe { ffi::zkx_av_clear() };
    }

    /// Appends an IPv4 address and port to the harness address vector (`addrvec_append`).
    pub fn av_append(&self, ip: u32, port: u16) {
        // SAFETY: Appending scalar IPv4 and port values is safe under the harness lock.
        unsafe { ffi::zkx_av_append(ip, port) };
    }

    /// Advances and returns the next entry from the harness address vector (`addrvec_next`),
    /// or `None` if exhausted.
    pub fn av_next(&self) -> Option<(u32, u16)> {
        let mut ip: c_uint = 0;
        let mut port: c_ushort = 0;
        // SAFETY: Out-pointers reference valid stack variables.
        let family = unsafe { ffi::zkx_av_next(&mut ip, &mut port) };
        (family != 0).then_some((ip, port))
    }

    /// Returns the current entry in the harness address vector without advancing the cursor
    /// (`addrvec_peek`).
    pub fn av_peek(&self) -> Option<(u32, u16)> {
        let mut ip: c_uint = 0;
        let mut port: c_ushort = 0;
        // SAFETY: Out-pointers reference valid stack variables.
        let family = unsafe { ffi::zkx_av_peek(&mut ip, &mut port) };
        (family != 0).then_some((ip, port))
    }

    /// Returns `true` if unread addresses remain in the harness address vector
    /// (`addrvec_hasnext`).
    pub fn av_has_next(&self) -> bool {
        // SAFETY: Queries address vector state under the harness lock.
        unsafe { ffi::zkx_av_hasnext() != 0 }
    }

    /// Returns `true` if the address vector cursor has reached the end (`addrvec_atend`).
    pub fn av_at_end(&self) -> bool {
        // SAFETY: Queries address vector cursor state under the harness lock.
        unsafe { ffi::zkx_av_atend() != 0 }
    }

    /// Returns the current cursor index of the harness address vector.
    pub fn av_cursor(&self) -> u32 {
        // SAFETY: Queries scalar cursor state under the harness lock.
        unsafe { ffi::zkx_av_cursor() }
    }

    /// Returns all `(ipv4, port)` entries stored in the harness address vector.
    pub fn av_items(&self) -> Vec<(u32, u16)> {
        // SAFETY: Queries address vector length under the harness lock.
        let n = unsafe { ffi::zkx_av_count() };
        let mut out = Vec::new();
        for i in 0..n {
            let mut ip: c_uint = 0;
            let mut port: c_ushort = 0;
            // SAFETY: Out-pointers reference valid stack variables.
            unsafe { ffi::zkx_av_get(i, &mut ip, &mut port) };
            out.push((ip, port));
        }
        out
    }

    /// Permutes the harness address vector using the configured `random()` feed
    /// (`addrvec_shuffle`).
    pub fn av_shuffle(&self) {
        // SAFETY: Shuffles the initialized address vector using the locked random feed.
        unsafe { ffi::zkx_av_shuffle() };
    }

    /// Compares two `(ipv4, port)` slices using the legacy C `addrvec_eq` implementation.
    pub fn av_eq(&self, a: &[(u32, u16)], b: &[(u32, u16)]) -> bool {
        let (a_ips, a_ports): (Vec<c_uint>, Vec<c_ushort>) = a.iter().copied().unzip();
        let (b_ips, b_ports): (Vec<c_uint>, Vec<c_ushort>) = b.iter().copied().unzip();
        // SAFETY: Slice pointers and lengths match the allocated vector buffers.
        unsafe {
            ffi::zkx_av_eq(
                a_ips.as_ptr(),
                a_ports.as_ptr(),
                to_c_uint(a.len()),
                b_ips.as_ptr(),
                b_ports.as_ptr(),
                to_c_uint(b.len()),
            ) != 0
        }
    }

    /// Selects whether subsequent watcher table operations (`zt_*`) dispatch to the signed or
    /// unsigned `char` C driver variant.
    ///
    /// Each variant maintains independent state. [`with_c`] defaults to the host platform's
    /// native `char` signedness; callers typically invoke [`CApi::zt_reset`] after switching.
    pub fn zt_select(&self, char_signed: bool) {
        self.zt_signed.set(char_signed);
    }

    /// Returns `true` if the active watcher table driver uses signed `char` semantics.
    pub fn zt_char_signed(&self) -> bool {
        self.zt_signed.get()
    }

    fn zt(&self) -> &'static ZtFns {
        if self.zt_signed.get() {
            &ZT_SIGNED
        } else {
            &ZT_UNSIGNED
        }
    }

    /// Reinitializes all three C watcher tables (data, exist, child) and clears default
    /// watcher, chroot, and delivery state.
    pub fn zt_reset(&self) {
        // SAFETY: Reinitializing watcher table state is safe under the harness lock.
        unsafe { (self.zt().reset)() };
    }

    /// Sets the default session watcher callback identifier and context token.
    pub fn zt_set_default(&self, func: i32, ctx: usize) {
        // SAFETY: Updates scalar fields under the harness lock.
        unsafe { (self.zt().set_default)(func, ctx) };
    }

    /// Sets the client chroot prefix applied during watcher event path stripping.
    pub fn zt_set_chroot(&self, chroot: Option<&[u8]>) {
        let c = chroot.map(cstring);
        let p = c.as_ref().map_or(ptr::null(), |c| c.as_ptr());
        // SAFETY: `p` is null or a valid NUL-terminated C string copied immediately by C.
        unsafe { (self.zt().set_chroot)(p) };
    }

    /// Registers an active watcher in the specified table (`activateWatcher`): `0` for data,
    /// `1` for exist, and `2` for child watchers.
    pub fn zt_activate(&self, table: i32, path: &[u8], func: i32, ctx: usize) {
        let p = cstring(path);
        // SAFETY: `p` is a valid NUL-terminated C string copied by the watcher table.
        unsafe { (self.zt().activate)(table, p.as_ptr(), func, ctx) };
    }

    /// Collects and delivers watchers for an event on `path` (`collectWatchers` and
    /// `deliverWatchers`).
    pub fn zt_event(&self, event_type: i32, path: &[u8]) -> Vec<Delivery> {
        let p = cstring(path);
        let zt = self.zt();
        // SAFETY: `p` is a valid NUL-terminated C string read during event collection.
        let n = unsafe { (zt.event)(event_type, p.as_ptr()) };
        let mut out = Vec::new();
        for i in 0..n {
            let mut func: c_int = 0;
            let mut ctx: usize = 0;
            let mut ty: c_int = 0;
            let mut path: *const c_char = ptr::null();
            // SAFETY: Out-pointers reference valid stack variables.
            let ok = unsafe { (zt.dlv_get)(i, &mut func, &mut ctx, &mut ty, &mut path) };
            if ok == 0 {
                break;
            }
            // SAFETY: `path` points to a harness buffer valid under the harness lock.
            let path = unsafe { copy_cstr(path) };
            out.push(Delivery {
                func,
                ctx,
                event_type: ty,
                path,
            });
        }
        out
    }

    /// Returns `true` if a matching watcher is registered for `path` and `wtype`
    /// (`pathHasWatcher`), or any watcher when `watcher` is `None`.
    pub fn zt_has(&self, path: &[u8], wtype: i32, watcher: Option<(i32, usize)>) -> bool {
        let p = cstring(path);
        let (func, ctx) = watcher.unwrap_or((-1, 0));
        // SAFETY: `p` is a valid NUL-terminated C string.
        unsafe { (self.zt().has)(p.as_ptr(), wtype, func, ctx) != 0 }
    }

    /// Removes matching watchers for `path` and `wtype` (`removeWatchers`), or all matching
    /// watchers when `watcher` is `None`.
    pub fn zt_remove(&self, path: &[u8], wtype: i32, watcher: Option<(i32, usize)>) {
        let p = cstring(path);
        let (func, ctx) = watcher.unwrap_or((-1, 0));
        // SAFETY: `p` is a valid NUL-terminated C string.
        unsafe { (self.zt().remove)(p.as_ptr(), wtype, func, ctx) };
    }

    /// Returns all paths and watcher lists in `table` in C hash table iteration order.
    pub fn zt_dump(&self, table: i32) -> Vec<ZtEntry> {
        const CAP: usize = 4096;
        let mut keys: Vec<*const c_char> = vec![ptr::null(); CAP];
        let mut nkeys: c_int = 0;
        let mut key_idx: Vec<c_int> = vec![0; CAP];
        let mut funcs: Vec<c_int> = vec![0; CAP];
        let mut ctxs: Vec<usize> = vec![0; CAP];
        let zt = self.zt();
        // SAFETY: All output buffers have capacity `CAP`, matching the capacity arguments.
        let n = unsafe {
            (zt.dump)(
                table,
                keys.as_mut_ptr(),
                to_c_int(CAP),
                &mut nkeys,
                key_idx.as_mut_ptr(),
                funcs.as_mut_ptr(),
                ctxs.as_mut_ptr(),
                to_c_int(CAP),
            )
        };
        let nkeys = usize::try_from(nkeys).unwrap_or(0).min(CAP);
        let n = usize::try_from(n).unwrap_or(0).min(CAP);
        let mut out: Vec<ZtEntry> = keys
            .iter()
            .take(nkeys)
            // SAFETY: Key pointers reference live C table strings valid during extraction.
            .map(|&k| (unsafe { copy_cstr(k) }, Vec::new()))
            .collect();
        for ((&k, &f), &c) in key_idx.iter().zip(funcs.iter()).zip(ctxs.iter()).take(n) {
            if let Some(entry) = usize::try_from(k).ok().and_then(|k| out.get_mut(k)) {
                entry.1.push((f, c));
            }
        }
        out
    }

    /// Validates a znode path using the legacy C `isValidPath` implementation.
    pub fn is_valid_path(&self, path: &[u8], mode: i32) -> bool {
        let p = cstring(path);
        // SAFETY: `p` is a valid NUL-terminated C string.
        unsafe { ffi::zkx_is_valid_path(p.as_ptr(), mode) != 0 }
    }

    /// Evaluates `ZOOKEEPER_IS_SEQUENCE(mode)` in C.
    pub fn is_sequence(&self, mode: i32) -> bool {
        // SAFETY: Pure scalar bit-flag check with no preconditions.
        unsafe { ffi::zkx_is_sequence(mode) != 0 }
    }

    /// Counts comma-separated host specifications using the legacy C `count_hosts` function.
    pub fn count_hosts(&self, hosts: &[u8]) -> i32 {
        let h = cstring(hosts);
        // SAFETY: `h` is a valid NUL-terminated C string read immutably by `count_hosts`.
        unsafe { ffi::zkx_count_hosts(h.as_ptr()) }
    }

    fn chroot_call(
        f: unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_char,
        chroot: Option<&[u8]>,
        path: &[u8],
    ) -> Vec<u8> {
        let c = chroot.map(cstring);
        let cp = c.as_ref().map_or(ptr::null(), |c| c.as_ptr());
        let p = cstring(path);
        // SAFETY: `cp` is null or NUL-terminated, and `p` is NUL-terminated.
        let r = unsafe { f(cp, p.as_ptr()) };
        // SAFETY: `r` is null or points to a heap-allocated NUL-terminated C string.
        let out = unsafe { copy_cstr(r) };
        // SAFETY: `r` was allocated by the C wrapper and is freed once here.
        unsafe { ffi::zkx_free(r.cast::<c_void>()) };
        out
    }

    /// Prepends `chroot` to a client path using the legacy C `prepend_string` implementation.
    pub fn prepend(&self, chroot: Option<&[u8]>, path: &[u8]) -> Vec<u8> {
        Self::chroot_call(ffi::zkx_prepend, chroot, path)
    }

    /// Strips `chroot` from a server path using the legacy C `sub_string` implementation.
    pub fn sub_string(&self, chroot: Option<&[u8]>, path: &[u8]) -> Vec<u8> {
        Self::chroot_call(ffi::zkx_sub_string, chroot, path)
    }

    /// Parses an integer prefix via C `strtol(s, &end, 0)`, returning the parsed value and
    /// consumed byte count.
    // Explicit widening conversion supports targets where `c_long` is 32 bits.
    #[allow(clippy::useless_conversion)]
    pub fn strtol0(&self, s: &[u8]) -> (i64, usize) {
        let c = cstring(s);
        let mut used: usize = 0;
        // SAFETY: `c` is a NUL-terminated C string and `used` is a valid stack out-pointer.
        let v = unsafe { ffi::zkx_strtol0(c.as_ptr(), &mut used) };
        (i64::from(v), used)
    }

    /// Truncates a C `long` to `int` using target C cast semantics.
    pub fn long_to_int(&self, v: i64) -> i32 {
        let v = c_long::try_from(v).unwrap_or(0);
        // SAFETY: Pure scalar cast with no preconditions.
        unsafe { ffi::zkx_long_to_int(v) }
    }

    /// Resolves a connection string via C `resolve_hosts`, returning the return code and
    /// resolved socket addresses.
    pub fn resolve(&self, hosts: &[u8], permute: bool) -> (i32, Vec<SocketAddr>) {
        let h = cstring(hosts);
        // SAFETY: `h` is a valid NUL-terminated C string.
        let rc = unsafe { ffi::zkx_resolve(h.as_ptr(), c_int::from(permute)) };
        // SAFETY: Reads the resolved address count under the harness lock.
        let n = unsafe { ffi::zkx_resolved_count() };
        let mut out = Vec::new();
        for i in 0..n {
            let mut ip = [0_u8; 16];
            let mut port: c_ushort = 0;
            // SAFETY: `ip` is a 16-byte buffer and `port` is a valid stack out-pointer.
            let family = unsafe { ffi::zkx_resolved_get(i, ip.as_mut_ptr(), &mut port) };
            if let Some(a) = decode_addr(family, &ip, port) {
                out.push(a);
            }
        }
        (rc, out)
    }

    /// Resets the simulated C `zhandle_t` server-list state used by `update_addrs`.
    pub fn u_reset(&self) {
        // SAFETY: Resetting the simulated handle state is safe under the harness lock.
        unsafe { ffi::zkx_u_reset() };
    }

    /// Sets the simulated connection status on the C handle.
    pub fn u_set_connected(&self, connected: bool) {
        // SAFETY: Updates a scalar field under the harness lock.
        unsafe { ffi::zkx_u_set_connected(c_int::from(connected)) };
    }

    /// Returns `true` if the simulated C handle currently has an active connection.
    pub fn u_connected(&self) -> bool {
        // SAFETY: Reads a scalar field under the harness lock.
        unsafe { ffi::zkx_u_connected() != 0 }
    }

    /// Updates the host list on the simulated C handle and invokes `update_addrs`.
    pub fn u_update(&self, hosts: &[u8], permute: bool) -> i32 {
        let h = cstring(hosts);
        // SAFETY: `h` is a valid NUL-terminated C string duplicated by the harness.
        unsafe { ffi::zkx_u_update(h.as_ptr(), c_int::from(permute)) }
    }

    /// Advances the simulated C handle to the next server via `zoo_cycle_next_server`.
    pub fn u_cycle(&self) {
        // SAFETY: Operates on initialized handle state under the harness lock.
        unsafe { ffi::zkx_u_cycle() };
    }

    /// Returns `true` if a dynamic reconfiguration is active on the simulated C handle.
    pub fn u_reconfig(&self) -> bool {
        // SAFETY: Reads a scalar field under the harness lock.
        unsafe { ffi::zkx_u_reconfig() != 0 }
    }

    /// Returns the `pOld` migration probability weight from the simulated C handle.
    pub fn u_p_old(&self) -> f64 {
        // SAFETY: Reads a scalar field under the harness lock.
        unsafe { ffi::zkx_u_p_old() }
    }

    /// Returns the `pNew` migration probability weight from the simulated C handle.
    pub fn u_p_new(&self) -> f64 {
        // SAFETY: Reads a scalar field under the harness lock.
        unsafe { ffi::zkx_u_p_new() }
    }

    /// Returns the session state code (`state`) stored on the simulated C handle.
    pub fn u_state(&self) -> i32 {
        // SAFETY: Reads a scalar field under the harness lock.
        unsafe { ffi::zkx_u_state() }
    }

    /// Returns the address list and cursor index for a handle address vector: `0` for `addrs`,
    /// `1` for `addrs_old`, and `2` for `addrs_new`.
    pub fn u_vec(&self, which: i32) -> (Vec<SocketAddr>, u32) {
        // SAFETY: Reads the address vector count under the harness lock.
        let n = unsafe { ffi::zkx_u_vec_count(which) };
        let mut out = Vec::new();
        for i in 0..n {
            let mut ip = [0_u8; 16];
            let mut port: c_ushort = 0;
            // SAFETY: `ip` is a 16-byte buffer and `port` is a valid stack out-pointer.
            let family = unsafe { ffi::zkx_u_vec_get(which, i, ip.as_mut_ptr(), &mut port) };
            if let Some(a) = decode_addr(family, &ip, port) {
                out.push(a);
            }
        }
        // SAFETY: Reads the vector cursor index under the harness lock.
        let next = unsafe { ffi::zkx_u_vec_next(which) };
        (out, next)
    }

    /// Returns the active server address (`addr_cur`) on the simulated C handle, or `None` if
    /// unset.
    pub fn u_cur(&self) -> Option<SocketAddr> {
        let mut ip = [0_u8; 16];
        let mut port: c_ushort = 0;
        // SAFETY: `ip` is a 16-byte buffer and `port` is a valid stack out-pointer.
        let family = unsafe { ffi::zkx_u_cur(ip.as_mut_ptr(), &mut port) };
        decode_addr(family, &ip, port)
    }
}
