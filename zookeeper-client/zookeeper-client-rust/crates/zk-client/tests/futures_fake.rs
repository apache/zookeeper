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

//! Evaluates `ResponseFuture` semantics against synthetic server fixtures: cancellation safety,
//! flow-control backpressure (read gate), synchronous blocking interfaces
//! (`wait`, `wait_timeout`), backpressure coordination (`ready`), and lifecycle termination
//! contracts.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::{Duration, Instant};

use apache_zookeeper::{Client, ClientBuilder, Error, ResponseFuture};
use common::{WAIT, builder, fake};
use zk_fakeserver::FakeServer;

/// Silence threshold used to assert that held or backpressured responses are not delivered
/// prematurely.
const QUIET: Duration = Duration::from_millis(300);

/// Instantiates a test server initialized with test node `/a` populated with data payload `"v"`.
fn server() -> FakeServer {
    let fs = fake();
    fs.cluster().create("/a", b"v", 0).unwrap();
    fs
}

/// Establishes an asynchronous client session against the fake server fixture using customized
/// builder options.
async fn connect(fs: &FakeServer, f: impl FnOnce(ClientBuilder) -> ClientBuilder) -> Client {
    tokio::time::timeout(WAIT, f(builder(&fs.connect_string())).connect())
        .await
        .expect("connect in time")
        .expect("connect")
}

/// Polls the target response future until completion status is observed without consuming the
/// inner payload.
async fn until_ready<T>(f: &ResponseFuture<T>) -> bool {
    let end = Instant::now().checked_add(WAIT).unwrap();
    while Instant::now() < end {
        if f.is_ready() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    f.is_ready()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_timed_out_poll_keeps_the_result() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    fs.hold();
    let mut f = client.get_data("/a");
    assert!(tokio::time::timeout(QUIET, &mut f).await.is_err());
    tokio::select! {
        r = &mut f => panic!("a held reply arrived: {r:?}"),
        () = tokio::time::sleep(Duration::from_millis(50)) => {}
    }
    fs.release();
    let (data, _) = tokio::time::timeout(WAIT, f).await.unwrap().unwrap();
    assert_eq!(data, b"v");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn is_ready_does_not_take_the_value() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    let f = client.get_data("/a");
    assert!(until_ready(&f).await);
    assert!(f.is_ready());
    let (data, _) = f.await.unwrap();
    assert_eq!(data, b"v");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dropped_counted_result_opens_the_read_gate() {
    let fs = server();
    let client = connect(&fs, |b| b.dispatch_queue_capacity(2)).await;
    let f1 = client.get_data("/a");
    let f2 = client.get_data("/a");
    assert!(until_ready(&f1).await && until_ready(&f2).await);
    // Dispatch queue capacity reached; inbound reader suspends frame processing.
    let f3 = client.get_data("/a");
    tokio::time::sleep(QUIET).await;
    assert!(!f3.is_ready(), "the read gate is open at the capacity");
    drop(f1);
    assert!(
        until_ready(&f3).await,
        "the drop did not open the read gate"
    );
    assert_eq!(f3.await.unwrap().0, b"v");
    assert_eq!(f2.await.unwrap().0, b"v");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_taken_counted_result_opens_the_read_gate() {
    let fs = server();
    let client = connect(&fs, |b| b.dispatch_queue_capacity(2)).await;
    let f1 = client.get_data("/a");
    let f2 = client.get_data("/a");
    assert!(until_ready(&f1).await && until_ready(&f2).await);
    let f3 = client.get_data("/a");
    tokio::time::sleep(QUIET).await;
    assert!(!f3.is_ready(), "the read gate is open at the capacity");
    assert_eq!(f1.await.unwrap().0, b"v");
    assert!(
        until_ready(&f3).await,
        "the take did not open the read gate"
    );
    assert_eq!(f2.await.unwrap().0, b"v");
    assert_eq!(f3.await.unwrap().0, b"v");
    // Inbound read gate reopens, allowing subsequent queued request processing to resume.
    let f4 = tokio::time::timeout(WAIT, client.get_data("/a")).await;
    assert_eq!(f4.unwrap().unwrap().0, b"v");
}

/// Verifies that response futures retain flow control notification handles after the parent
/// client is dropped, ensuring unread buffered responses are drained.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_take_after_the_last_client_drop_opens_the_read_gate() {
    let fs = server();
    let client = connect(&fs, |b| b.dispatch_queue_capacity(2)).await;
    let f1 = client.get_data("/a");
    let f2 = client.get_data("/a");
    assert!(until_ready(&f1).await && until_ready(&f2).await);
    let more: Vec<_> = (0..6).map(|_| client.get_data("/a")).collect();
    tokio::time::sleep(QUIET).await;
    assert!(
        more.iter().all(|f| !f.is_ready()),
        "the gate is open at the capacity"
    );
    drop(client);
    assert_eq!(f1.await.unwrap().0, b"v");
    assert_eq!(f2.await.unwrap().0, b"v");
    for f in more {
        let r = tokio::time::timeout(WAIT, f).await.expect("a gated result");
        assert_eq!(r.unwrap().0, b"v");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn ready_without_a_limit_resolves_at_once() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    fs.hold();
    let _pending = client.get_data("/a");
    let r = tokio::time::timeout(Duration::from_millis(100), client.ready()).await;
    assert_eq!(r.unwrap(), Ok(()));
    fs.release();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn ready_waits_until_the_count_is_below_half_of_the_limit() {
    let fs = server();
    let client = connect(&fs, |b| b.max_pending_requests(4)).await;
    assert_eq!(client.ready().await, Ok(()));
    fs.hold();
    let held: Vec<_> = (0..4).map(|_| client.get_data("/a")).collect();
    let refused = client.get_data("/a");
    assert!(refused.is_ready());
    assert_eq!(refused.await, Err(Error::TooManyPendingRequests));
    let mut ready = client.ready();
    assert!(tokio::time::timeout(QUIET, &mut ready).await.is_err());
    fs.release();
    let r = tokio::time::timeout(WAIT, ready).await;
    assert_eq!(r.unwrap(), Ok(()));
    for f in held {
        assert_eq!(f.await.unwrap().0, b"v");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn ready_on_a_closed_client_gives_the_terminal_error() {
    let fs = server();
    let client = connect(&fs, |b| b.max_pending_requests(4)).await;
    assert_eq!(client.close().await, Ok(()));
    let r = tokio::time::timeout(WAIT, client.ready()).await;
    assert_eq!(r.unwrap(), Err(Error::Closing));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_second_close_gives_the_same_result() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    let first = tokio::time::timeout(WAIT, client.close()).await.unwrap();
    assert_eq!(first, Ok(()));
    let second = tokio::time::timeout(WAIT, client.close()).await.unwrap();
    assert_eq!(second, first);
    assert_eq!(client.get_data("/a").await, Err(Error::Closing));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dropped_close_future_still_closes() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    drop(client.close());
    assert_eq!(client.get_data("/a").await, Err(Error::Closing));
    let r = tokio::time::timeout(WAIT, client.close()).await.unwrap();
    assert_eq!(r, Ok(()));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn detach_ends_a_pending_future_with_closing() {
    let fs = server();
    let client = connect(&fs, |b| b).await;
    fs.hold();
    let f = client.get_data("/a");
    let _creds = client.detach();
    let r = tokio::time::timeout(WAIT, f).await.unwrap();
    assert_eq!(r, Err(Error::Closing));
    fs.release();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_future_outlives_its_client() {
    let fs = server();
    let client = connect(&fs, |b| b.close_timeout(Duration::from_millis(200))).await;
    fs.hold();
    let f = client.get_data("/a");
    drop(client);
    // Teardown exceeds close timeout; remaining outstanding futures are aborted with
    // `Error::Closing`.
    let r = tokio::time::timeout(WAIT, f).await.unwrap();
    assert_eq!(r, Err(Error::Closing));
    fs.release();
}

#[cfg(feature = "blocking")]
mod blocking {
    use super::*;

    fn connect_blocking(fs: &FakeServer, f: impl FnOnce(ClientBuilder) -> ClientBuilder) -> Client {
        f(builder(&fs.connect_string()))
            .connect()
            .wait()
            .expect("connect")
    }

    #[test]
    fn wait_gives_the_result() {
        let fs = server();
        let client = connect_blocking(&fs, |b| b);
        assert_eq!(client.get_data("/a").wait().unwrap().0, b"v");
        assert_eq!(client.get_data("/missing").wait(), Err(Error::NoNode));
    }

    #[test]
    fn wait_timeout_gives_the_future_back_after_the_time() {
        let fs = server();
        let client = connect_blocking(&fs, |b| b);
        fs.hold();
        let started = Instant::now();
        let f = match client
            .get_data("/a")
            .wait_timeout(Duration::from_millis(100))
        {
            Err(f) => f,
            Ok(r) => panic!("a held reply arrived: {r:?}"),
        };
        assert!(started.elapsed() >= Duration::from_millis(100));
        assert!(!f.is_ready());
        fs.release();
        let r = f.wait_timeout(WAIT).expect("the reply in time");
        assert_eq!(r.unwrap().0, b"v");
    }

    #[test]
    fn ready_wait_blocks_until_the_count_is_below_half() {
        let fs = server();
        let client = connect_blocking(&fs, |b| b.max_pending_requests(2));
        fs.hold();
        let f1 = client.get_data("/a");
        let f2 = client.get_data("/a");
        std::thread::scope(|s| {
            s.spawn(|| {
                std::thread::sleep(Duration::from_millis(100));
                fs.release();
            });
            assert_eq!(client.ready().wait(), Ok(()));
        });
        assert_eq!(f1.wait().unwrap().0, b"v");
        assert_eq!(f2.wait().unwrap().0, b"v");
    }

    #[test]
    fn close_wait_returns_after_the_driver_exits() {
        let fs = server();
        let client = connect_blocking(&fs, |b| b.max_pending_requests(2));
        assert_eq!(client.close().wait(), Ok(()));
        assert_eq!(client.close().wait(), Ok(()));
        assert_eq!(client.ready().wait(), Err(Error::Closing));
        assert_eq!(client.get_data("/a").wait(), Err(Error::Closing));
    }

    /// Validates runtime execution detection when compiled with the `tokio` feature flag.
    #[cfg(feature = "tokio")]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn every_wait_inside_a_tokio_runtime_is_refused() {
        let fs = server();
        let client = connect(&fs, |b| b).await;
        assert_eq!(client.get_data("/a").wait(), Err(Error::InvalidState));
        assert_eq!(
            client.get_data("/a").wait_timeout(WAIT).ok(),
            Some(Err(Error::InvalidState))
        );
        assert_eq!(client.ready().wait(), Err(Error::InvalidState));
        // Rejection of blocking wait inside asynchronous runtime does not invalidate subsequent
        // client operations.
        assert_eq!(client.get_data("/a").await.unwrap().0, b"v");
        assert_eq!(client.close().wait(), Err(Error::InvalidState));
    }
}
