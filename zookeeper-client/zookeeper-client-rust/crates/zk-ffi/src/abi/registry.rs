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

//! Global handle registry for detecting invalid pointer usage in debug configurations.
//!
//! When compiled under debug configurations, the C ABI shim registers every instantiated
//! `zhandle_t` address into a thread-safe registry upon creation, and deregisters it when
//! the handle is finalized. Public FFI entry points query this registry to detect and intercept
//! invalid handle usage, such as use-after-close or double-close anomalies, immediately halting
//! execution with diagnostic details before corrupted memory is accessed. Release builds omit
//! these checks to avoid lock contention and overhead.
//!
//! The registry stores handle memory addresses as opaque integer identifiers. The internal
//! mutex is strictly leaf-locked (never held across user callbacks or driver dispatch calls),
//! and mutex poisoning is recovered automatically since registry updates represent isolated,
//! self-contained set operations.

use std::collections::HashSet;
use std::ffi::{c_char, c_void};
use std::sync::{LazyLock, Mutex, MutexGuard, PoisonError};

use super::entry::{abort_with, ffi_guard};
use super::raw;

/// Thread-safe registry containing memory addresses of all currently active `zhandle_t` pointers.
static LIVE: LazyLock<Mutex<HashSet<usize>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

fn live() -> MutexGuard<'static, HashSet<usize>> {
    LIVE.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Registers an active handle address into the debug registry, aborting on memory allocation
/// failure.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_registry_add(zh: *const c_void) {
    ffi_guard(|| {
        let mut set = live();
        if set.try_reserve(1).is_err() {
            drop(set);
            abort_with("zookeeper: handle registry: out of memory");
        }
        set.insert(zh.addr());
    });
}

/// Deregisters a handle address upon finalization; silently ignores handles not found in the
/// registry.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_registry_remove(zh: *const c_void) {
    ffi_guard(|| {
        live().remove(&zh.addr());
    });
}

/// Asserts that a handle address is currently registered as active, aborting with context if
/// invalid.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_registry_check(zh: *const c_void, func: *const c_char) {
    ffi_guard(|| {
        if live().contains(&zh.addr()) {
            return;
        }
        let func = raw::cstr_ref(func).map_or_else(|| "(null)".into(), String::from_utf8_lossy);
        abort_with(&format!(
            "zookeeper: {func}: zhandle_t {:#x} is closed or invalid \
             (use after close or double close)",
            zh.addr()
        ));
    });
}
