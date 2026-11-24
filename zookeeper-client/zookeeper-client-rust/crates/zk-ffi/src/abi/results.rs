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

//! Stateless C FFI entry points for result object projection and buffer copy-out.
//!
//! Exposes top-level C-callable dispatchers that wrap the low-level serialization and memory
//! formatting routines in [`crate::abi::raw::writers`] behind standard [`ffi_guard`] panic
//! boundaries. These functions operate purely on caller supplied view records and output
//! destination pointers without requiring an active handle or outbox context.

use std::ffi::{c_char, c_void};

use super::entry::ffi_guard;
use super::legacy::COpResult;
use super::raw::writers;
use super::types::{zkrs_result_dst, zkrs_result_view, zkrs_str, zkrs_sync_dst, zkrs_sync_src};

/// Projects decoded ZooKeeper completion results into caller supplied C output structures.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_write_result(v: *const zkrs_result_view, d: *const zkrs_result_dst) {
    ffi_guard(|| writers::write_result(v, d));
}

/// Allocates and copies a watcher event path into a null-terminated C string (`malloc(len + 1)`).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_write_path(s: *const zkrs_str) -> *mut c_char {
    ffi_guard(|| writers::write_path(s))
}

/// Marshals multi-operation transaction results into an array of legacy `zoo_op_result_t`
/// structures.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_write_multi(
    v: *const zkrs_result_view,
    results: *mut c_void,
    op_kind: *const u8,
    count: i32,
) {
    ffi_guard(|| writers::write_multi(v, results.cast::<COpResult>(), op_kind, count));
}

/// Transfers synchronous operation results from an internal record image to caller output buffers.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_sync_copy_out(s: *const zkrs_sync_src, d: *const zkrs_sync_dst) {
    ffi_guard(|| writers::sync_copy_out(s, d));
}
