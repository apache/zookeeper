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

//! Parity test suite validating concurrent synchronous operations corresponding to
//! `TestOperations.cc`.
//!
//! Evaluates multithreaded client handle sharing across synchronous blocking facade invocations,
//! verifying thread safe request multiplexing, server disconnection handling, and clean error
//! propagation.

#![cfg(feature = "blocking")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{Client, CreateMode, Error, Runtime, ThreadOptions};
use common::{WAIT, builder};

/// Concurrency worker count allocated per job cohort, matching C test harness thread pool sizing.
const THREADS: usize = 10;

/// Establishes a synchronous blocking client instance powered by a dedicated OS worker thread.
fn connect(cs: &str) -> Client {
    builder(cs)
        .runtime(Runtime::Thread(ThreadOptions::default()))
        .connect()
        .wait()
        .unwrap()
}

/// Executes a synchronous data read bounded by the test timeout budget.
fn get(c: &Client, path: &str) -> Result<Vec<u8>, Error> {
    match c.get_data(path).wait_timeout(WAIT) {
        Ok(r) => r.map(|(d, _)| d),
        Err(_) => panic!("get_data {path} did not finish in {WAIT:?}"),
    }
}

/// Dispatches concurrent read iterations across worker threads, returning individual thread
/// execution outcomes.
fn read_jobs(c: &Client, path: &'static str, reps: usize) -> Vec<Result<(), Error>> {
    let jobs: Vec<_> = (0..THREADS)
        .map(|_| {
            let c = c.clone();
            thread::spawn(move || {
                for _ in 0..reps {
                    get(&c, path)?;
                }
                Ok(())
            })
        })
        .collect();
    jobs.into_iter().map(|j| j.join().unwrap()).collect()
}

/// C parity test case (`Zookeeper_operations::testConcurrentOperations1`). Asserts thread safety
/// when multiple concurrent threads multiplex data reads through a single shared client handle.
#[test]
fn test_concurrent_operations1_mt() {
    for _ in 0..5 {
        let fs = common::fake();
        fs.cluster().create("/x", b"", 0).unwrap();
        fs.cluster().create("/x/y", b"", 0).unwrap();
        fs.cluster().create("/x/y/z", b"1", 0).unwrap();
        let c = connect(&fs.connect_string());
        for r in read_jobs(&c, "/x/y/z", 100) {
            assert_eq!(r, Ok(()));
        }
        c.close().wait().unwrap();
    }
}

/// C parity test case (`Zookeeper_operations::testOperationsAndDisconnectConcurrently1`). Asserts
/// that server termination while concurrent read pipelines are active terminates all in-flight
/// requests with [`Error::ConnectionLoss`].
#[test]
fn test_operations_and_disconnect_concurrently1_mt() {
    for _ in 0..5 {
        let mut fs = common::fake();
        fs.cluster().create("/x", b"", 0).unwrap();
        fs.cluster().create("/x/y", b"", 0).unwrap();
        fs.cluster().create("/x/y/z", b"1", 0).unwrap();
        let c = connect(&fs.connect_string());
        let started = Arc::new(AtomicBool::new(false));
        let jobs: Vec<_> = (0..THREADS)
            .map(|_| {
                let c = c.clone();
                let started = Arc::clone(&started);
                thread::spawn(move || {
                    // Loop continuously until the injected connection loss terminates operation
                    // with an error.
                    for _ in 0..1_000_000_u32 {
                        get(&c, "/x/y/z")?;
                        started.store(true, Ordering::Release);
                    }
                    Ok::<(), Error>(())
                })
            })
            .collect();
        while !started.load(Ordering::Acquire) {
            thread::sleep(Duration::from_millis(1));
        }
        thread::sleep(Duration::from_millis(4));
        // Abrupt server termination severs underlying TCP connections.
        fs.stop();
        for j in jobs {
            assert_eq!(j.join().unwrap(), Err(Error::ConnectionLoss));
        }
        drop(c);
    }
}

/// C parity test case (`Zookeeper_operations::testOperationsAndDisconnectConcurrently2`). Verifies
/// multithreaded read pipeline execution against a real standalone ZooKeeper daemon.
#[test]
#[ignore = "real-server"]
fn test_operations_and_disconnect_concurrently2_mt() {
    let Some(server) = common::fresh_server(9) else {
        return;
    };
    let cs = server.connect_string();
    let c = connect(&cs);
    match c
        .create("/xyz", b"1", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .wait()
    {
        Ok(_) | Err(Error::NodeExists) => {}
        Err(e) => panic!("create /xyz: {e:?}"),
    }
    c.close().wait().unwrap();
    for _ in 0..5 {
        let c = connect(&cs);
        for r in read_jobs(&c, "/xyz", 200) {
            assert_eq!(r, Ok(()));
        }
        c.close().wait().unwrap();
    }
    drop(server);
}
