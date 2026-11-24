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

//! Synthetic mock implementation of the host platform vtable for unit testing.
//!
//! Provides a test harness stand-in for [`zkrs_host_vtable`] that intercepts host interactions
//! (such as DNS address resolution, random number generation, log filtering, and token retirement)
//! and logs call records into thread-local storage. The [`recorder`] guard clears existing state on
//! instantiation and resets thread-local state upon drop, ensuring complete isolation across test
//! cases.

#![allow(unsafe_code, reason = "the resolve stand-in calls the C callbacks")]

use std::cell::RefCell;
use std::ffi::{c_char, c_double, c_int, c_long, c_void};

use super::types::{zkrs_host_vtable, zkrs_sockaddr_view, zkrs_watcher};

/// Deterministic pseudorandom value returned by the mock `rng_random` callback.
pub(crate) const RANDOM: i64 = 4242;

/// Sentinel function pointer address assigned to synthetic default watchers in unit tests.
pub(crate) const DEFAULT_WATCHER_FN: usize = 0x10;

#[derive(Default)]
struct Record {
    discarded: Vec<usize>,
    client_ids: Vec<(usize, i64, [u8; 16])>,
    reseeds: u32,
    resolve: (Vec<zkrs_sockaddr_view>, Vec<i32>, c_int, i32),
    resolved: Vec<(Vec<u8>, Vec<u8>)>,
}

thread_local! {
    static REC: RefCell<Record> = RefCell::new(Record::default());
}

/// RAII guard that initializes and clears mock call records in thread-local storage.
pub(crate) struct Recorder;

impl Drop for Recorder {
    fn drop(&mut self) {
        REC.with(|r| *r.borrow_mut() = Record::default());
    }
}

/// Initializes a clean test recording session and returns an RAII cleanup guard.
pub(crate) fn recorder() -> Recorder {
    REC.with(|r| *r.borrow_mut() = Record::default());
    Recorder
}

/// Returns the sequence of tokens passed to `discard_token`, in order of invocation.
pub(crate) fn discarded() -> Vec<usize> {
    REC.with(|r| r.borrow().discarded.clone())
}

/// Returns the sequence of `(host_ctx, client_id, password)` records captured by `store_client_id`.
pub(crate) fn client_ids() -> Vec<(usize, i64, [u8; 16])> {
    REC.with(|r| r.borrow().client_ids.clone())
}

/// Returns the total count of invocations received by `rng_reseed`.
pub(crate) fn reseeds() -> u32 {
    REC.with(|r| r.borrow().reseeds)
}

/// Returns the list of `(hostname, port)` query pairs received by the mock DNS resolver.
pub(crate) fn resolved() -> Vec<(Vec<u8>, Vec<u8>)> {
    REC.with(|r| r.borrow().resolved.clone())
}

/// Pre-configures synthetic DNS resolution outcomes returned by subsequent `resolve` invocations.
pub(crate) fn set_resolve(addrs: Vec<zkrs_sockaddr_view>, skipped: Vec<i32>, err: c_int, gai: i32) {
    REC.with(|r| r.borrow_mut().resolve = (addrs, skipped, err, gai));
}

/// Instantiates a populated [`zkrs_host_vtable`] backed by the mock implementation functions.
pub(crate) fn vtable() -> zkrs_host_vtable {
    zkrs_host_vtable {
        discard_token: Some(discard_token),
        snapshot_default_watcher: Some(snapshot_default_watcher),
        store_client_id: Some(store_client_id),
        log_enabled: Some(log_enabled),
        conn_permute_disabled: Some(conn_permute_disabled),
        rng_random: Some(rng_random),
        rng_drand48: Some(rng_drand48),
        rng_reseed: Some(rng_reseed),
        resolve: Some(resolve),
    }
}

/// Records a token retirement invocation in thread-local test state.
pub(crate) extern "C" fn discard_token(token: usize) {
    REC.with(|r| r.borrow_mut().discarded.push(token));
}

extern "C" fn snapshot_default_watcher(host: usize, out: *mut zkrs_watcher) {
    let w = zkrs_watcher {
        r#fn: DEFAULT_WATCHER_FN,
        ctx: host,
    };
    // SAFETY: The host caller provides a valid, aligned destination pointer for the watcher
    // snapshot.
    unsafe { out.write(w) };
}

extern "C" fn store_client_id(host: usize, id: i64, passwd: *const u8) {
    let mut p = [0u8; 16];
    // SAFETY: The caller provides a pointer to a valid 16-byte session password buffer.
    unsafe { std::ptr::copy_nonoverlapping(passwd, p.as_mut_ptr(), 16) };
    REC.with(|r| r.borrow_mut().client_ids.push((host, id, p)));
}

/// Filters log messages up to WARN severity (levels 0 through 2), mimicking default legacy log
/// levels.
extern "C" fn log_enabled(level: u32) -> c_int {
    c_int::from(level <= 2)
}

extern "C" fn conn_permute_disabled() -> c_int {
    1
}

extern "C" fn rng_random() -> c_long {
    c_long::try_from(RANDOM).unwrap_or(0)
}

extern "C" fn rng_drand48() -> c_double {
    0.5
}

extern "C" fn rng_reseed() {
    REC.with(|r| {
        let mut r = r.borrow_mut();
        r.reseeds = r.reseeds.saturating_add(1);
    });
}

fn cstr(p: *const c_char) -> Vec<u8> {
    // SAFETY: The resolver callback receives null-terminated C string pointers from the runtime.
    unsafe { std::ffi::CStr::from_ptr(p) }.to_bytes().to_vec()
}

extern "C" fn resolve(
    name: *const c_char,
    port: *const c_char,
    gai_rc: *mut i32,
    push: Option<unsafe extern "C" fn(sink: *mut c_void, addr: *const zkrs_sockaddr_view)>,
    skip: Option<unsafe extern "C" fn(sink: *mut c_void, family: i32)>,
    sink: *mut c_void,
) -> c_int {
    let (n, p) = (cstr(name), cstr(port));
    let (addrs, skipped, err, gai) = REC.with(|r| {
        let mut r = r.borrow_mut();
        r.resolved.push((n, p));
        r.resolve.clone()
    });
    for a in &addrs {
        if let Some(f) = push {
            // SAFETY: Invokes the verified address sink callback with the provided sink context and
            // address view.
            unsafe { f(sink, a) };
        }
    }
    for fam in skipped {
        if let Some(f) = skip {
            // SAFETY: Invokes the verified address skip callback with the provided sink context and
            // address family.
            unsafe { f(sink, fam) };
        }
    }
    // SAFETY: Writes the synthetic GAI return code into the caller's valid output pointer.
    unsafe { gai_rc.write(gai) };
    err
}
