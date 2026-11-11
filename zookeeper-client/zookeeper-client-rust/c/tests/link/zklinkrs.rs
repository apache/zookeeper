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

//! Independent Rust `staticlib` used by symbol-isolation verification.
//!
//! Compiles with its own embedded copy of the Rust standard library (`std`) and
//! `panic=abort`, and is statically linked alongside the Rust ZooKeeper client
//! archive into a single C executable. Prelinking and symbol localization on
//! the ZooKeeper archive ensure zero duplicate-symbol collisions and verify
//! that two independent `std` runtimes coexist within the same process (testing
//! heap allocation, thread spawning, MPSC channels, `HashMap`, and TCP I/O).
//!
//! This crate intentionally omits `rustls` and `ring`; in TLS-enabled test
//! configurations, the runner builds `zklinkring`, which includes this module
//! and adds independent `rustls` and `ring` instances.

use std::collections::HashMap;
use std::ffi::{c_char, c_int, CStr};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

/// Executes the `std` runtime coexistence probe, optionally connecting to a TCP
/// endpoint specified by `host` (`"host:port"` or null). Returns 0 on success
/// or the 1-based index of the failing step.
///
/// # Safety
///
/// `host` must be either null or a valid pointer to a NUL-terminated C string.
#[no_mangle]
pub unsafe extern "C" fn zklinkrs_probe(host: *const c_char) -> c_int {
    let (tx, rx) = mpsc::channel::<u64>();
    let worker = thread::spawn(move || {
        let mut map: HashMap<String, u64> = HashMap::new();
        for i in 0..1000u64 {
            map.insert(format!("key-{i}"), i);
        }
        let _ = tx.send(map.values().sum());
    });
    if rx.recv_timeout(Duration::from_secs(10)) != Ok(499_500) {
        return 1;
    }
    if worker.join().is_err() {
        return 2;
    }
    if host.is_null() {
        return 0;
    }
    // SAFETY: caller guarantees `host` is a non-null, NUL-terminated C string.
    let host = match unsafe { CStr::from_ptr(host) }.to_str() {
        Ok(h) => h,
        Err(_) => return 3,
    };
    let addr = match host.to_socket_addrs().ok().and_then(|mut a| a.next()) {
        Some(a) => a,
        None => return 4,
    };
    match TcpStream::connect_timeout(&addr, Duration::from_secs(10)) {
        Ok(_) => 0,
        Err(_) => 5,
    }
}

/// Formats an integer via `std::fmt` and returns the resulting byte length.
#[no_mangle]
pub extern "C" fn zklinkrs_format_len(n: c_int) -> c_int {
    let s = format!("zklinkrs:{n:08}");
    c_int::try_from(s.len()).unwrap_or(-1)
}
