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

//! Execution framing, panic boundaries, and handle dereferencing guards for C ABI exports.
//!
//! Every exported `zkrs_*` entry point executes within the protective context established by
//! [`ffi_guard`]. To maintain absolute ABI safety, unwinding panics are strictly forbidden from
//! crossing the C boundary. Any captured panic triggers an immediate, safe process abort. The
//! [`with_handle`] wrapper augments this boundary by validating opaque handle pointers, retaining
//! temporary ownership references, and synthesizing static synchronization proofs (`Unlocked`).

use std::io::Write as _;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

use zk_driver::Unlocked;

use super::handle::Inner;
use super::raw;
use super::types::zkrs_handle;

/// Executes the provided closure `f` within a panic safe boundary, aborting the process if an
/// unwinding panic occurs.
///
/// Serving as the outermost execution frame for foreign exports, this guard guarantees that
/// undefined behavior arising from cross-language stack unwinding into C callers is prevented.
pub(crate) fn ffi_guard<R>(f: impl FnOnce() -> R) -> R {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(_) => abort_with("zk-ffi: panic at the C boundary"),
    }
}

/// Resolves an opaque handle pointer, acquiring an incremental `Arc` reference and synchronization
/// proof for `f`.
///
/// While the calling C code maintains a baseline reference across the call, [`raw::handle_arc`]
/// clones a secondary reference for the duration of the invocation. This ensures handle memory
/// remains fully valid even if the closure initiates terminal teardown or releases the primary
/// reference.
pub(crate) fn with_handle<R>(
    p: *const zkrs_handle,
    f: impl FnOnce(&Arc<Inner>, &mut Unlocked) -> R,
) -> R {
    ffi_guard(|| {
        let h = raw::handle_arc(p);
        let mut u = Unlocked::new_at_entry();
        f(&h, &mut u)
    })
}

/// Emits a diagnostic error message to standard error and terminates the process via
/// `std::process::abort`.
///
/// Invoked when non-recoverable C contract violations are detected at runtime, such as null
/// pointers for mandatory ABI buffers, incomplete virtual dispatch tables, or post-release handle
/// dereferences.
pub(crate) fn abort_with(msg: &str) -> ! {
    let mut e = std::io::stderr().lock();
    // Flush the diagnostic payload best effort before terminating the host process.
    let _ = writeln!(e, "{msg}");
    std::process::abort()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_guard_returns_the_value() {
        assert_eq!(ffi_guard(|| 7), 7);
    }
}
