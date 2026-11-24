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

//! Integration tests for the debug mode handle registry (`add`, `check`, and `remove`).
//!
//! Because failed handle assertions abort the process immediately, negative test cases
//! reinvoke this test binary in a child process and inspect its standard error and
//! termination signal.

#![cfg(unix)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::print_stderr
)]
#![allow(clippy::disallowed_macros)]

use std::env;
use std::ffi::{CStr, c_void};
use std::os::unix::process::ExitStatusExt as _;
use std::process::Command;

use std::ffi::c_char;

// Link against `zk-ffi` so C-ABI exports are resolved by symbol name as in the C shim.
use zk_ffi as _;

#[allow(unsafe_code, reason = "the declarations of the C exports")]
unsafe extern "C" {
    safe fn zkrs_registry_add(zh: *const c_void);
    safe fn zkrs_registry_remove(zh: *const c_void);
    safe fn zkrs_registry_check(zh: *const c_void, func: *const c_char);
}

/// Environment variable selecting the negative test case executed by the child subprocess.
const CHILD: &str = "ZKRS_REGISTRY_CHILD";
const FUNC: &CStr = c"zoo_aget";

/// Derives a unique opaque handle pointer from a heap-allocated box reference.
fn handle(b: &u64) -> *const c_void {
    std::ptr::from_ref(b).cast()
}

/// Executes `case` inside a child process, asserting termination via `SIGABRT` and returning
/// the captured standard error stream.
fn child_aborts(case: &str) -> String {
    let out = Command::new(env::current_exe().unwrap())
        .args(["child", "--exact", "--nocapture", "--test-threads=1"])
        .env(CHILD, case)
        .output()
        .unwrap();
    assert_eq!(out.status.signal(), Some(libc::SIGABRT), "{out:?}");
    String::from_utf8(out.stderr).unwrap()
}

/// Verifies that a registered active handle passes validation repeatedly without mutating
/// registry state.
#[test]
fn a_live_handle_passes_the_check() {
    let b = Box::new(1u64);
    let zh = handle(&b);
    zkrs_registry_add(zh);
    zkrs_registry_check(zh, FUNC.as_ptr());
    zkrs_registry_check(zh, FUNC.as_ptr());
    zkrs_registry_remove(zh);
}

/// Verifies that deregistering an unknown handle address is a safe no-op that leaves
/// existing registrations intact.
#[test]
fn removing_an_unknown_handle_does_nothing() {
    let b = Box::new(2u64);
    let other = Box::new(3u64);
    zkrs_registry_add(handle(&b));
    zkrs_registry_remove(handle(&other));
    zkrs_registry_check(handle(&b), FUNC.as_ptr());
    zkrs_registry_remove(handle(&b));
}

/// Subprocess entry point for abort-inducing registry test cases; returns immediately when
/// invoked outside child mode.
#[test]
fn child() {
    let Ok(case) = env::var(CHILD) else {
        return;
    };
    let b = Box::new(4u64);
    let zh = handle(&b);
    eprintln!("addr={:#x}", zh.addr());
    match case.as_str() {
        "unknown" => zkrs_registry_check(zh, FUNC.as_ptr()),
        "closed" => {
            zkrs_registry_add(zh);
            zkrs_registry_check(zh, FUNC.as_ptr());
            zkrs_registry_remove(zh);
            zkrs_registry_check(zh, c"zookeeper_close".as_ptr());
        }
        _ => panic!("unknown case {case}"),
    }
    panic!("the check returned");
}

/// Splits the child subprocess stderr output into the recorded handle address and diagnostic
/// error line.
fn lines(err: &str) -> (String, String) {
    let mut it = err.lines();
    let addr = it.next().unwrap().strip_prefix("addr=").unwrap().to_owned();
    (addr, it.next().unwrap_or_default().to_owned())
}

/// Verifies that validating an unregistered handle aborts with a diagnostic message naming
/// the entry point and hexadecimal handle address.
#[test]
fn an_unknown_handle_aborts_with_the_message() {
    let err = child_aborts("unknown");
    let (addr, msg) = lines(&err);
    assert!(addr.starts_with("0x"), "{err}");
    assert_eq!(
        msg,
        format!(
            "zookeeper: zoo_aget: zhandle_t {addr} is closed or invalid \
             (use after close or double close)"
        )
    );
}

/// Verifies that validating a handle after deregistration aborts, diagnosing use-after-close
/// or double-close defects.
#[test]
fn a_removed_handle_aborts_the_check() {
    let err = child_aborts("closed");
    let (addr, msg) = lines(&err);
    assert_eq!(
        msg,
        format!(
            "zookeeper: zookeeper_close: zhandle_t {addr} is closed or \
             invalid (use after close or double close)"
        )
    );
}
