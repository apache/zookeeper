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

//! Serialization and projection writers for ZooKeeper operation results and libc allocators.
//!
//! This module provides routines that reconstruct legacy C structures (such as `Stat`,
//! `String_vector`, `ACL_vector`, and transaction `COpResult` arrays) from staged views in the
//! outbox completion arena or synchronous reply records. The generated structures conform to
//! the memory layout expected by the legacy C client, matching the format produced by Jute
//! deserialization routines.
//!
//! To satisfy C memory management requirements, dynamic memory allocations are performed
//! via [`c_malloc`] or [`c_calloc`] using the host C runtime allocator (`libc`). This ensures
//! that returned structures can be safely deallocated by C callers using `free()` or Jute's
//! `deallocate_*` destructors. Allocation failures emit a diagnostic to standard error and
//! terminate the process via `abort()`, replacing unchecked NULL pointer dereferences in the
//! legacy C client with a deterministic fail-stop boundary.
//!
//! To facilitate standalone fuzzing and testing without coupling to the entire runtime, this
//! module depends solely on `abi/types.rs`, `abi/legacy.rs`, `libc`, and `std`.

use std::ffi::{c_char, c_int};
use std::io::Write as _;
use std::mem::offset_of;
use std::ptr;

use super::super::legacy::{CAcl, CAclVector, CId, COpResult, CStat, CStringVector};
use super::super::types::{
    ZKRS_K_ACLLIST, ZKRS_K_DATA, ZKRS_K_MULTI, ZKRS_K_STAT, ZKRS_K_STRING, ZKRS_K_STRING_STAT,
    ZKRS_K_STRINGLIST, ZKRS_K_STRINGLIST_STAT, ZKRS_ORIGIN_TEARDOWN, zkrs_multi_copy,
    zkrs_result_dst, zkrs_result_view, zkrs_stat, zkrs_str, zkrs_sync_dst, zkrs_sync_src,
};

/// Allocates uninitialized heap storage through the host C runtime allocator (`libc::malloc`) so
/// returned pointers can be released directly by C callers via `free()`.
///
/// Non-zero allocation failures emit a diagnostic to standard error and abort the process.
/// Zero-sized requests delegate directly to the platform allocator.
pub(crate) fn c_malloc(n: usize) -> *mut u8 {
    // SAFETY: `libc::malloc` is invoked with a validated byte size. Memory ownership is transferred
    // to the C caller upon return, which assumes responsibility for eventual deallocation.
    let p = unsafe { libc::malloc(n) }.cast::<u8>();
    if p.is_null() && n != 0 {
        out_of_memory(n);
    }
    p
}

/// Allocates zero-initialized array storage through the host C runtime allocator (`libc::calloc`).
///
/// Negative element counts (such as `-1` representing an absent Jute vector) return a null pointer
/// without allocating. Non-zero allocation failures emit a diagnostic to standard error and abort
/// the process.
pub(crate) fn c_calloc(count: i32, size: usize) -> *mut u8 {
    let Ok(n) = usize::try_from(count) else {
        return std::ptr::null_mut();
    };
    // SAFETY: Invokes `libc::calloc` with validated count and size parameters. The underlying
    // allocator handles overflow checks during element multiplication.
    let p = unsafe { libc::calloc(n, size) }.cast::<u8>();
    if p.is_null() && n != 0 && size != 0 {
        out_of_memory(n.saturating_mul(size));
    }
    p
}

/// Marshals a [`zkrs_stat`] representation into a target [`CStat`] structure.
///
/// Populates each field of the destination `struct Stat` individually using the [`CStat`]
/// layout mirror, insulating the transfer from layout differences between Rust FFI types
/// and platform C structs. A null destination pointer is safely ignored without writing.
pub(crate) fn write_stat(dst: *mut CStat, s: &zkrs_stat) {
    // Field assignment is performed explicitly by name through the mirror struct, ensuring
    // ABI compatibility across platforms regardless of struct padding differences.
    let c = CStat {
        czxid: s.czxid,
        mzxid: s.mzxid,
        ctime: s.ctime,
        mtime: s.mtime,
        version: s.version,
        cversion: s.cversion,
        aversion: s.aversion,
        ephemeral_owner: s.ephemeralOwner,
        data_length: s.dataLength,
        num_children: s.numChildren,
        pzxid: s.pzxid,
    };
    put(dst, c);
}

/// Deserializes an operation completion result into caller supplied C output buffers.
///
/// Dispatches by operation payload kind (`ZKRS_K_*`), allocating and writing destination
/// structures:
/// - `Stat` structs copied field-by-field.
/// - Data buffers allocated via `malloc(len)` (or set to NULL for absent znode payloads).
/// - String paths copied into newly allocated null-terminated buffers (`malloc(len + 1)`).
/// - Child and ACL vector arrays allocated via `calloc` with individual string/ACL member buffers.
/// - Synchronous multi-operation results preserving individual sub-operation status and return
///   values.
///
/// On operation failure, destination pointers remain untouched, adhering to legacy C library
/// conventions.
pub(crate) fn write_result(v: *const zkrs_result_view, d: *const zkrs_result_dst) {
    let v = read_in(v, "zk-ffi: NULL zkrs_result_view");
    let d = read_in(d, "zk-ffi: NULL zkrs_result_dst");
    // Multi-operation transactions retain per-operation records regardless of overall transaction
    // status so that the synchronous completion dispatcher can unpack individual sub-results.
    if v.kind == ZKRS_K_MULTI {
        put_multi_copies(&v, &d);
        return;
    }
    // When the top-level operation indicates failure, output destinations are preserved in
    // their initial state matching legacy C behavior.
    if v.failed != 0 {
        return;
    }
    match v.kind {
        ZKRS_K_STAT => put_stat(&v, &d),
        ZKRS_K_DATA => {
            put_data(&v, &d);
            put_stat(&v, &d);
        }
        ZKRS_K_STRINGLIST => put_strings(&v, &d),
        ZKRS_K_STRINGLIST_STAT => {
            put_strings(&v, &d);
            put_stat(&v, &d);
        }
        ZKRS_K_ACLLIST => {
            put_acl(&v, &d);
            put_stat(&v, &d);
        }
        ZKRS_K_STRING => put_str(&v, &d),
        ZKRS_K_STRING_STAT => {
            put_str(&v, &d);
            put_stat(&v, &d);
        }
        // Void completions and unrecognized result kinds have no payload to marshal.
        _ => {}
    }
}

/// Allocates and copies a watcher event path into a null-terminated C string (`malloc(len + 1)`).
///
/// Watcher callbacks always receive a valid C string pointer. If the provided path view is NULL
/// or empty (as occurs with session events), an empty null-terminated string `""` is returned.
pub(crate) fn write_path(s: *const zkrs_str) -> *mut c_char {
    // Session state events lack a znode path; return an empty null-terminated string to satisfy
    // the C watcher callback contract expecting a non-null string pointer.
    if s.is_null() {
        return dup_str(&[]);
    }
    let s = read_in(s, "zk-ffi: NULL zkrs_str");
    dup_str(str_bytes(s.ptr, s.len).unwrap_or(&[]))
}

/// Populates an array of legacy `zoo_op_result_t` structures from a multi-operation result view.
///
/// Mirrors the legacy C client's `op_result_string_completion` and `op_result_stat_completion`
/// routines across caller-allocated result arrays. For failed transactions where the server
/// returned no sub-operation details (e.g. transport errors or header-level failures), every
/// element is populated with an error code reflecting the overall failure status.
pub(crate) fn write_multi(
    v: *const zkrs_result_view,
    results: *mut COpResult,
    kinds: *const u8,
    count: i32,
) {
    let v = read_in(v, "zk-ffi: NULL zkrs_result_view");
    let Some(n) = multi_len(results, kinds, count) else {
        return;
    };
    // When a multi-operation fails at the envelope or transport level without per-op replies,
    // synthesize a failure record for each sub-op (mirroring legacy `cleanup_failed_multi`).
    if v.failed != 0 && v.nops <= 0 {
        fail_all(results, kinds, n, v.rc);
        return;
    }
    // Cap iteration to the caller's preallocated slice length to prevent buffer overruns
    // if the server returned unexpected trailing results.
    for (i, o) in records(v.ops, v.nops).iter().take(n).enumerate() {
        let out = OpOut {
            err: o.err,
            failed: o.failed != 0,
            path: str_bytes(o.path.ptr, o.path.len),
            stat: opt_stat(o.stat),
        };
        write_op(results.wrapping_add(i), kind_at(kinds, i), &out);
    }
}

/// Copies synchronous operation results from an internal completion record into caller supplied
/// output buffers.
///
/// Handles synchronous return semantics, including buffer truncation rules for data reads,
/// null-termination of path buffers, and multi-operation transaction unpacking on the calling
/// thread.
pub(crate) fn sync_copy_out(s: *const zkrs_sync_src, d: *const zkrs_sync_dst) {
    let s = read_in(s, "zk-ffi: NULL zkrs_sync_src");
    let d = read_in(d, "zk-ffi: NULL zkrs_sync_dst");
    // Synchronous multi-operation results are always processed across all sub-ops,
    // mirroring legacy `deserialize_multi` behavior regardless of the return code.
    if s.kind == ZKRS_K_MULTI {
        sync_multi(&s, &d);
        return;
    }
    // For single-operation commands, output buffers are only written upon successful execution
    // (ZOK).
    if s.rc != 0 || s.failed != 0 {
        return;
    }
    match s.kind {
        ZKRS_K_DATA => {
            sync_data(&s, &d);
            sync_stat(&s, &d);
        }
        ZKRS_K_STRING | ZKRS_K_STRING_STAT => {
            sync_path(&s, &d);
            sync_stat(&s, &d);
        }
        // Vector structures are allocated in C memory; only the metadata stat structure is copied
        // here.
        ZKRS_K_STAT | ZKRS_K_STRINGLIST | ZKRS_K_STRINGLIST_STAT | ZKRS_K_ACLLIST => {
            sync_stat(&s, &d);
        }
        // Void operations do not yield return values beyond the operation status code.
        _ => {}
    }
}

/// Intermediate representation of an unpacked multi-operation sub-result.
struct OpOut<'a> {
    /// Return code for this specific sub-operation (e.g. `ZOK`, `ZNONODE`, `ZNODEEXISTS`).
    err: i32,
    /// Indicates whether this sub-operation encountered a fatal error within the transaction.
    failed: bool,
    /// Created znode path for create sub-operations, or `None` if absent.
    path: Option<&'a [u8]>,
    /// Metadata stat structure for sub-operations returning node metadata.
    stat: Option<zkrs_stat>,
}

/// Dispatches completion values into a single `COpResult` slot based on the operation kind.
///
/// Executes the legacy completion routines (`op_result_string_completion`,
/// `op_result_stat_completion`, or `op_result_void_completion`). Existing destination buffer
/// pointers provided by the caller are populated in place without modifying buffer capacity
/// descriptors.
fn write_op(res: *mut COpResult, kind: u32, o: &OpOut<'_>) {
    put(field::<c_int>(res, offset_of!(COpResult, err)), o.err);
    match kind {
        ZKRS_K_STRING => {
            let value_at = field::<*mut c_char>(res, offset_of!(COpResult, value));
            // Fetch the caller-provided destination pointer; callers may supply preallocated
            // output buffers prior to synchronous completion dispatch.
            let value = get(value_at);
            match o.path {
                Some(p) if !value.is_null() && !o.failed => {
                    let cap = get(field::<c_int>(res, offset_of!(COpResult, valuelen)));
                    copy_c_string(value, cap, p);
                }
                _ => put(value_at, ptr::null_mut()),
            }
        }
        ZKRS_K_STAT => {
            let stat_at = field::<*mut CStat>(res, offset_of!(COpResult, stat));
            let st = get(stat_at);
            match o.stat {
                Some(s) if !st.is_null() && o.err == 0 && !o.failed => write_stat(st, &s),
                _ => put(stat_at, ptr::null_mut()),
            }
        }
        _ => {}
    }
}

/// Populates all `n` result elements with a uniform transaction failure error code.
fn fail_all(results: *mut COpResult, kinds: *const u8, n: usize, rc: i32) {
    let out = OpOut {
        err: rc,
        failed: true,
        path: None,
        stat: None,
    };
    for i in 0..n {
        write_op(results.wrapping_add(i), kind_at(kinds, i), &out);
    }
}

/// Validates and computes the effective element count for a multi-operation result array.
///
/// Returns `None` if the count is zero or the destination pointer is null. Aborts the process
/// if the destination array is non-null but the corresponding operation kinds array is null,
/// violating the API precondition established by request creation.
fn multi_len(results: *mut COpResult, kinds: *const u8, count: i32) -> Option<usize> {
    let n = usize::try_from(count).ok().filter(|&n| n != 0)?;
    if results.is_null() {
        return None;
    }
    if kinds.is_null() {
        fail("zk-ffi: NULL multi op kinds");
    }
    Some(n)
}

/// Resolves the operation kind discriminant for the sub-operation at index `i`.
fn kind_at(kinds: *const u8, i: usize) -> u32 {
    // SAFETY: The `kinds` pointer references a caller supplied array sized to match `count`
    // elements, and index `i` is guaranteed to be within bounds.
    u32::from(unsafe { kinds.wrapping_add(i).read() })
}

/// Transfers multi-operation sub-results from synchronous completion records into caller buffers.
fn sync_multi(s: &zkrs_sync_src, d: &zkrs_sync_dst) {
    let results = d.results.cast::<COpResult>();
    let Some(n) = multi_len(results, s.op_kind, d.count) else {
        return;
    };
    // On handle teardown or envelope-level protocol error, synthesized failure records are written
    // to all sub-operation slots to prevent callers from reading uninitialized or stale data.
    let teardown = u32::try_from(s.origin) == Ok(ZKRS_ORIGIN_TEARDOWN);
    if teardown || (s.failed != 0 && s.nops <= 0) {
        fail_all(results, s.op_kind, n, s.rc);
        return;
    }
    for (i, c) in records(s.ops, s.nops).iter().take(n).enumerate() {
        let out = OpOut {
            err: c.err,
            failed: c.failed != 0,
            path: str_bytes(c.path, c.path_len),
            stat: (c.has_stat != 0).then_some(c.stat),
        };
        write_op(results.wrapping_add(i), kind_at(s.op_kind, i), &out);
    }
}

/// Copies node data into a caller supplied byte buffer, enforcing capacity bounds.
///
/// Copies `min(data_len, cap)` bytes into the destination buffer and writes the resulting length.
/// Negative buffer capacities are handled safely without writing, avoiding undefined behavior.
fn sync_data(s: &zkrs_sync_src, d: &zkrs_sync_dst) {
    if d.buffer_len.is_null() {
        return;
    }
    let cap = get(d.buffer_len);
    let len = if s.data_len <= cap { s.data_len } else { cap };
    if !d.buffer.is_null() {
        let src = buf_bytes(s.data, s.data_len);
        if let (Ok(n), Some(b)) = (usize::try_from(len), src) {
            copy_to(d.buffer.cast::<u8>(), b.get(..n).unwrap_or(b));
        }
    }
    put(d.buffer_len, len);
}

/// Copies a path string into the caller's path buffer, ensuring null termination.
///
/// Replaces null string pointers with an empty string representation to avoid null dereferences.
fn sync_path(s: &zkrs_sync_src, d: &zkrs_sync_dst) {
    let p = str_bytes(s.path, s.path_len).unwrap_or(&[]);
    copy_c_string(d.path_buffer, d.path_buffer_len, p);
}

/// Copies node stat metadata into the caller's `CStat` buffer when both pointers are valid.
fn sync_stat(s: &zkrs_sync_src, d: &zkrs_sync_dst) {
    if s.stat.is_null() {
        return;
    }
    let st = get(s.stat.cast::<CStat>());
    put(d.stat.cast::<CStat>(), st);
}

/// Copies a byte slice into a destination C string buffer, truncating at capacity and ensuring null
/// termination.
///
/// The effective copied length is bounded by `min(strlen(p) + 1, cap)`. If capacity permits, `len -
/// 1` bytes are copied followed by a null terminator byte.
fn copy_c_string(dst: *mut c_char, cap: i32, p: &[u8]) {
    let n = p.iter().position(|&b| b == 0).unwrap_or(p.len());
    let want = i64::try_from(n).unwrap_or(i64::MAX).saturating_add(1);
    let len = want.min(i64::from(cap));
    if len <= 0 || dst.is_null() {
        return;
    }
    let Ok(k) = usize::try_from(len.saturating_sub(1)) else {
        return;
    };
    let d = dst.cast::<u8>();
    copy_to(d, p.get(..k).unwrap_or(p));
    // SAFETY: The caller buffer contains at least `cap >= len = k + 1` writable bytes,
    // ensuring offset `k` falls strictly within the allocated boundary.
    unsafe { d.wrapping_add(k).write(0) };
}

/// Allocates and copies a node data payload into caller-provided value and length pointers.
fn put_data(v: &zkrs_result_view, d: &zkrs_result_dst) {
    match buf_bytes(v.data.ptr, v.data.len) {
        None => {
            put(d.value, ptr::null_mut());
            put(d.value_len, -1);
        }
        Some(b) => {
            // Allocate heap storage only when a destination pointer is supplied to avoid leaking
            // memory.
            if !d.value.is_null() {
                put(d.value, dup_bytes(b));
            }
            put(d.value_len, len_i32(b.len()));
        }
    }
}

/// Allocates and copies a znode path string into caller-provided string and length pointers.
fn put_str(v: &zkrs_result_view, d: &zkrs_result_dst) {
    let b = str_bytes(v.path.ptr, v.path.len);
    if !d.str.is_null() {
        put(d.str, opt_dup_str(b));
    }
    put(d.str_len, len_i32(b.map_or(0, <[u8]>::len)));
}

/// Marshals node stat metadata into a caller destination pointer.
///
/// If stat metadata is absent from the result view, writes a zero-initialized `Stat` structure
/// to ensure callers never observe uninitialized memory.
fn put_stat(v: &zkrs_result_view, d: &zkrs_result_dst) {
    let s = opt_stat(v.stat).unwrap_or_default();
    write_stat(d.stat.cast::<CStat>(), &s);
}

/// Marshals a list of child node names into a `String_vector` allocated with libc heap memory.
fn put_strings(v: &zkrs_result_view, d: &zkrs_result_dst) {
    if d.strings.is_null() {
        return;
    }
    let items = records(v.children, v.nchildren);
    let data = c_calloc(v.nchildren, size_of::<*mut c_char>()).cast::<*mut c_char>();
    for (i, s) in items.iter().enumerate() {
        put(data.wrapping_add(i), opt_dup_str(str_bytes(s.ptr, s.len)));
    }
    let sv = CStringVector {
        count: v.nchildren,
        data,
    };
    put(d.strings.cast::<CStringVector>(), sv);
}

/// Marshals an ACL list into an `ACL_vector` allocated with libc heap memory.
fn put_acl(v: &zkrs_result_view, d: &zkrs_result_dst) {
    if d.acl.is_null() {
        return;
    }
    let items = records(v.acl, v.nacl);
    let data = c_calloc(v.nacl, size_of::<CAcl>()).cast::<CAcl>();
    for (i, a) in items.iter().enumerate() {
        let acl = CAcl {
            perms: a.perms,
            id: CId {
                scheme: opt_dup_str(str_bytes(a.scheme.ptr, a.scheme.len)),
                id: opt_dup_str(str_bytes(a.id.ptr, a.id.len)),
            },
        };
        put(data.wrapping_add(i), acl);
    }
    let av = CAclVector {
        count: v.nacl,
        data,
    };
    put(d.acl.cast::<CAclVector>(), av);
}

/// Marshals multi-operation sub-results into an array of completion copies for synchronous
/// dispatch.
///
/// Requires both the destination array pointer and count pointer; if either is null, no write is
/// performed.
fn put_multi_copies(v: &zkrs_result_view, d: &zkrs_result_dst) {
    if d.ops.is_null() || d.nops.is_null() {
        return;
    }
    let items = records(v.ops, v.nops);
    if items.is_empty() {
        put(d.ops, ptr::null_mut());
        put(d.nops, 0);
        return;
    }
    let ops = c_calloc(v.nops, size_of::<zkrs_multi_copy>()).cast::<zkrs_multi_copy>();
    for (i, o) in items.iter().enumerate() {
        let path = str_bytes(o.path.ptr, o.path.len);
        let stat = opt_stat(o.stat);
        let c = zkrs_multi_copy {
            err: o.err,
            failed: o.failed,
            path: opt_dup_str(path),
            path_len: len_i32(path.map_or(0, <[u8]>::len)),
            has_stat: u32::from(stat.is_some()),
            stat: stat.unwrap_or_default(),
        };
        put(ops.wrapping_add(i), c);
    }
    put(d.ops, ops);
    put(d.nops, v.nops);
}

/// Computes a pointer to the field at byte offset `off` within `*res`.
fn field<T>(res: *mut COpResult, off: usize) -> *mut T {
    res.wrapping_byte_add(off).cast::<T>()
}

/// Converts an optional raw stat pointer into an owned [`zkrs_stat`] value.
fn opt_stat(p: *const zkrs_stat) -> Option<zkrs_stat> {
    if p.is_null() {
        return None;
    }
    Some(get(p))
}

/// Constructs a borrowed byte slice from a raw C string pointer and length descriptor.
fn str_bytes<'a>(p: *const c_char, len: i32) -> Option<&'a [u8]> {
    if p.is_null() {
        return None;
    }
    Some(borrow(p, usize::try_from(len).unwrap_or(0)))
}

/// Constructs a borrowed byte slice from a raw payload data buffer pointer and length descriptor.
fn buf_bytes<'a>(p: *const c_char, len: i32) -> Option<&'a [u8]> {
    let n = usize::try_from(len).ok()?;
    if n == 0 {
        return Some(&[]);
    }
    if p.is_null() {
        return None;
    }
    Some(borrow(p, n))
}

/// Constructs a borrowed byte slice of length `n` from a non-null raw pointer.
fn borrow<'a>(p: *const c_char, n: usize) -> &'a [u8] {
    if n == 0 {
        return &[];
    }
    // SAFETY: The view or completion record guarantees that `p` points to at least `n` contiguous,
    // readable bytes valid for the duration of the writer invocation.
    unsafe { std::slice::from_raw_parts(p.cast::<u8>(), n) }
}

/// Constructs a borrowed slice over a contiguous array of `count` items from raw pointer `p`.
///
/// Aborts if `count` is positive but `p` is null or if total byte size overflows `isize`.
fn records<'a, T>(p: *const T, count: i32) -> &'a [T] {
    let Ok(n) = usize::try_from(count) else {
        return &[];
    };
    if n == 0 {
        return &[];
    }
    let fits = n
        .checked_mul(size_of::<T>())
        .is_some_and(|b| isize::try_from(b).is_ok());
    if p.is_null() || !fits {
        fail("zk-ffi: bad array in a result view");
    }
    // SAFETY: The view guarantees that `p` references `count` contiguous, properly aligned,
    // initialized elements that remain valid for the duration of the writer call.
    unsafe { std::slice::from_raw_parts(p, n) }
}

/// Allocates a new heap buffer via [`c_malloc`] and copies the provided byte slice into it.
fn dup_bytes(b: &[u8]) -> *mut c_char {
    let p = c_malloc(b.len());
    copy_to(p, b);
    p.cast::<c_char>()
}

/// Allocates a new heap buffer via [`c_malloc`], copies slice `b`, and appends a null terminator
/// byte.
fn dup_str(b: &[u8]) -> *mut c_char {
    let Some(n) = b.len().checked_add(1) else {
        fail("zk-ffi: string too long");
    };
    let p = c_malloc(n);
    copy_to(p, b);
    // SAFETY: Buffer `p` is allocated with `len + 1` bytes; offset `len` is within bounds.
    unsafe { p.wrapping_add(b.len()).write(0) };
    p.cast::<c_char>()
}

/// Helper that applies [`dup_str`] when a byte slice is present, or returns a null pointer if
/// `None`.
fn opt_dup_str(b: Option<&[u8]>) -> *mut c_char {
    b.map_or(ptr::null_mut(), dup_str)
}

/// Copies bytes from slice `b` into destination pointer `dst`. No operation is performed if `b` is
/// empty.
fn copy_to(dst: *mut u8, b: &[u8]) {
    if b.is_empty() {
        return;
    }
    // SAFETY: The caller guarantees that `dst` points to at least `b.len()` contiguous writable
    // bytes not overlapping with `b`.
    unsafe { ptr::copy_nonoverlapping(b.as_ptr(), dst, b.len()) };
}

/// Converts a `usize` buffer length back to a signed 32-bit integer, saturating at `i32::MAX`.
fn len_i32(n: usize) -> i32 {
    i32::try_from(n).unwrap_or(i32::MAX)
}

/// Dereferences a required struct pointer passed from C, aborting if null.
fn read_in<T: Copy>(p: *const T, msg: &str) -> T {
    if p.is_null() {
        fail(msg);
    }
    get(p)
}

/// Reads a `Copy` value from a non-null raw pointer.
fn get<T: Copy>(p: *const T) -> T {
    // SAFETY: Caller guarantees that `p` is non-null, properly aligned, and points to an
    // initialized `T`.
    unsafe { p.read() }
}

/// Writes value `v` to the destination pointer `dst` if non-null.
fn put<T>(dst: *mut T, v: T) {
    if dst.is_null() {
        return;
    }
    // SAFETY: When non-null, `dst` must point to an aligned, writable memory location for type `T`.
    unsafe { dst.write(v) };
}

/// Reports a failed C allocation on stderr and aborts the process.
///
/// The message formats only an integer, so it does not need the heap that just failed.
fn out_of_memory(bytes: usize) -> ! {
    let mut e = std::io::stderr().lock();
    // The process aborts next, so an I/O error of this write has no effect.
    let _ = writeln!(e, "zk-ffi: out of memory allocating {bytes} bytes");
    std::process::abort()
}

/// Outputs a fatal error message to stderr and terminates the process immediately.
///
/// Standalone writer routines use direct stderr emission and process aborts rather than
/// full FFI entry guard machinery to allow standalone testing and fuzzing harnesses.
fn fail(msg: &str) -> ! {
    let mut e = std::io::stderr().lock();
    // The process aborts immediately; ignore I/O errors during stderr write.
    let _ = writeln!(e, "{msg}");
    std::process::abort()
}
