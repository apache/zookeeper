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

//! Verification of shared driver core abstractions and synchronization primitives.
//!
//! Asserts invariant guarantees of public driver primitives:
//! - Monotonicity and manual advances of time providers ([`crate::clock`]).
//! - Flow control saturation and replenishment across backpressure gates ([`crate::dispatch`]).
//! - Host adapter forwarding, filtering, and log site evaluation ([`crate::host`]).
//! - Reentrancy tracking and final close coordination ([`crate::lifecycle`]).
//! - Outbox FIFO ordering, publication delegation, and requeue preservation ([`crate::outbox`]).
//! - Compile-time proof token tracking and mutex synchronization semantics ([`crate::sync`]).
//! - Uninitialized socket sentinel states of I/O engines ([`crate::iocore`]).

#![allow(missing_docs)]
#![allow(clippy::disallowed_macros)]

mod unit_util;

mod clock {
    use zk_proto::Millis;

    use zk_driver::clock::*;

    #[test]
    fn monotonic_never_goes_back() {
        let c = MonotonicClock::new();
        let a = c.now();
        let b = c.now();
        assert!(b >= a);
    }

    #[test]
    fn test_clock_moves_on_request() {
        let c = TestClock::new(Millis(5));
        c.advance(10);
        assert_eq!(c.now(), Millis(15));
        c.set(Millis(1));
        assert_eq!(c.now(), Millis(1));
        c.set(Millis(u64::MAX));
        c.advance(1);
        assert_eq!(c.now(), Millis(u64::MAX));
    }
}

mod dispatch {
    use zk_driver::dispatch::*;

    #[test]
    fn gate_closes_at_capacity_and_reopens() {
        let g = ReadGate::new(2);
        g.routed();
        assert!(g.is_open());
        g.routed();
        assert!(!g.is_open());
        assert!(g.taken());
        assert!(g.is_open());
        assert!(!g.taken());
        assert!(!g.taken());
        assert_eq!(g.count(), 0);
    }
}

mod host {
    use std::sync::Arc;

    use zk_proto::{LogLevel, LogSite};
    use zk_testkit::tokens::TestTokens;

    use zk_driver::host::*;

    use crate::unit_util::TestHost;

    #[test]
    fn adapters_forward_to_the_leaves() {
        let host: Arc<dyn HostLeaves<TestTokens>> = Arc::new(TestHost::new());
        let (mut rng, log, dw) = connection_hooks(&host);
        rng.on_resolution();
        assert_eq!(rng.random(), 7);
        assert!(log.enabled(LogLevel::Error));
        assert!(dw.snapshot().is_none());
    }

    #[test]
    fn log_record_follows_the_filter() {
        let host = TestHost::new();
        let rec = log_record::<TestTokens>(&host, LogSite::TcpNoDelay, Vec::new());
        assert!(rec.is_some_and(|r| r.level == LogLevel::Warn));
        host.set_log_level(None);
        assert!(log_record::<TestTokens>(&host, LogSite::TcpNoDelay, Vec::new()).is_none());
    }
}

mod lifecycle {
    use zk_driver::lifecycle::*;

    #[test]
    fn last_epilog_after_close_runs_the_final_close() {
        let l = Lifecycle::new();
        assert_eq!(l.prolog(), 1);
        assert_eq!(l.prolog(), 2);
        assert!(!l.epilog());
        assert!(l.request_close());
        assert!(!l.request_close());
        assert!(l.epilog());
        assert!(!l.epilog());
        assert!(l.begin_final());
        assert!(!l.begin_final());
        assert!(!l.is_forked_child());
    }
}

mod outbox {
    #![allow(clippy::unwrap_used, clippy::indexing_slicing)]

    use std::sync::Arc;

    use zk_proto::{DriverMode, LogRecord, LogSite};
    use zk_testkit::tokens::TestTokens;

    use zk_driver::outbox::*;
    use zk_driver::sync::Mutex;

    use crate::unit_util::{TestHost, test_connection};

    #[derive(Default)]
    struct Fifo(Mutex<Vec<Item<TestTokens>>>);

    impl Publish<TestTokens> for Fifo {
        fn publish(&self, item: Item<TestTokens>) {
            self.0.lock_owner().push(item);
        }
    }

    fn log() -> Item<TestTokens> {
        Item::Log(LogRecord {
            site: LogSite::TcpNoDelay,
            level: LogSite::TcpNoDelay.level(),
            args: Vec::new(),
        })
    }

    #[test]
    fn publish_happens_on_take() {
        let fifo = Arc::new(Fifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push(log());
        ob.push_publish(Item::AuthLockpoint);
        ob.push(Item::AuthLockpoint);
        assert!(ob.has_pending_logs());
        assert!(matches!(ob.pop(), Some(Item::Log(_))));
        assert!(fifo.0.lock_owner().is_empty());
        assert!(matches!(ob.pop(), Some(Item::AuthLockpoint)));
        assert_eq!(fifo.0.lock_owner().len(), 1);
        assert!(ob.pop().is_none());
    }

    #[test]
    fn requeue_and_restore_keep_the_order() {
        let mut ob: Outbox<TestTokens> = Outbox::new();
        ob.push(log());
        ob.push(Item::AuthLockpoint);
        ob.set_resume(ResumeAt::IoStep);
        let rest = ob.requeue();
        assert!(ob.is_empty());
        assert_eq!(rest.resume_at(), Some(ResumeAt::IoStep));
        assert_eq!(ob.resume_at(), None);
        ob.push(Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Io,
        });
        ob.restore(rest);
        assert_eq!(ob.len(), 3);
        assert!(matches!(ob.pop(), Some(Item::Log(_))));
        assert!(matches!(ob.pop(), Some(Item::AuthLockpoint)));
        assert!(matches!(ob.pop(), Some(Item::Thread { .. })));
    }

    #[test]
    fn pump_of_a_new_connection_is_drained() {
        let host = TestHost::new();
        let mut conn = test_connection(DriverMode::MT);
        let mut ob = Outbox::new();
        assert_eq!(
            pump(&mut conn, &mut ob, FifoBound::Publish, &host),
            Pumped::Drained
        );
    }
}

mod sync {
    use std::time::Duration;

    use zk_driver::sync::*;

    #[test]
    fn lock_borrows_the_proof() {
        let m = Mutex::new(1_u32);
        let mut u = Unlocked::new_at_entry();
        {
            let mut g = m.lock(&mut u);
            *g = 2;
        }
        assert_eq!(*m.lock(&mut u), 2);
    }

    #[test]
    fn try_lock_fails_while_held() {
        let m = Mutex::new(());
        let g = m.lock_owner();
        assert!(m.try_lock().is_none());
        drop(g);
        assert!(m.try_lock().is_some());
    }

    #[test]
    fn wait_timeout_reports_the_timeout() {
        let m = Mutex::new(());
        let cv = Condvar::new();
        let (_g, timed_out) = cv.wait_timeout(m.lock_owner(), Duration::from_millis(1));
        assert!(timed_out);
    }
}

mod iocore {
    use zk_driver::iocore::*;

    #[test]
    fn new_core_has_no_socket() {
        let io = IoCore::new();
        assert_eq!(io.fd(), -1);
        assert!(!io.is_suspended());
        assert!(!io.buffered_ready(false, true));
    }
}
