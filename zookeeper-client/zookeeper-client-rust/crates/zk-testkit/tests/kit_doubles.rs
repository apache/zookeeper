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

//! Unit tests for `zk-testkit` test doubles and recording types: chunking, manual clock, hooks,
//! simulated network, event recorder, PRNG replicas, scripted SASL, and tokens.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::float_cmp
)]
#![allow(clippy::disallowed_macros)]

mod chunk {
    use zk_testkit::chunk::*;

    #[test]
    fn whole_and_fixed() {
        let mut w = Chunker::new(Chunking::Whole);
        assert_eq!(w.next_len(10), 10);
        assert_eq!(w.next_len(0), 0);
        let mut f = Chunker::new(Chunking::ONE_BYTE);
        assert_eq!(f.split(b"abc"), vec![&b"a"[..], b"b", b"c"]);
        let mut z = Chunker::new(Chunking::Fixed(0));
        assert_eq!(z.next_len(4), 1);
    }

    #[test]
    fn script_repeats() {
        let mut s = Chunker::new(Chunking::Script(vec![2, 0, 5]));
        assert_eq!(s.next_len(10), 2);
        assert_eq!(s.next_len(10), 1);
        assert_eq!(s.next_len(3), 3);
        assert_eq!(s.next_len(10), 2);
        let mut e = Chunker::new(Chunking::Script(Vec::new()));
        assert_eq!(e.next_len(7), 7);
    }

    #[test]
    fn seeded_is_reproducible_and_covers_input() {
        let data: Vec<u8> = (0..=255).collect();
        let plan = Chunking::Seeded { seed: 42, max: 7 };
        let a: Vec<usize> = Chunker::new(plan.clone())
            .split(&data)
            .iter()
            .map(|c| c.len())
            .collect();
        let b: Vec<usize> = Chunker::new(plan)
            .split(&data)
            .iter()
            .map(|c| c.len())
            .collect();
        assert_eq!(a, b);
        assert_eq!(a.iter().sum::<usize>(), data.len());
        assert!(a.iter().all(|n| (1..=7).contains(n)));
    }

    proptest::proptest! {
        #[test]
        fn split_concatenates(data in proptest::collection::vec(0u8..=255, 0..300),
                              seed in 0u64..1000, max in 0usize..20) {
            let mut c = Chunker::new(Chunking::Seeded { seed, max });
            let parts = c.split(&data);
            proptest::prop_assert!(parts.iter().all(|p| !p.is_empty()));
            let joined: Vec<u8> = parts.concat();
            proptest::prop_assert_eq!(&joined, &data);
        }
    }
}

mod clock {
    use zk_proto::Millis;
    use zk_testkit::clock::*;

    #[test]
    fn clock_is_monotonic() {
        let mut c = ManualClock::new(Millis(10));
        assert_eq!(c.now(), Millis(10));
        assert_eq!(c.advance(5), Millis(15));
        assert_eq!(c.advance_to(Millis(12)), Millis(15));
        assert_eq!(c.advance_to(Millis(40)), Millis(40));
        assert_eq!(c.advance(u64::MAX), Millis(u64::MAX));
    }
}

mod hooks {
    use zk_proto::{DefaultWatcher, LogFilter, LogLevel};
    use zk_testkit::hooks::*;
    use zk_testkit::tokens::WatcherId;

    #[test]
    fn filters() {
        let f = LevelFilter::up_to(LogLevel::Warn);
        assert!(f.enabled(LogLevel::Error));
        assert!(!f.enabled(LogLevel::Info));
        assert!(!LevelFilter::off().enabled(LogLevel::Error));
        let s = SharedLogFilter::new(None);
        assert!(!s.enabled(LogLevel::Error));
        s.set(Some(LogLevel::Debug));
        assert!(s.clone().enabled(LogLevel::Debug));
    }

    #[test]
    fn default_watcher_slot() {
        let s = SharedDefaultWatcher::new(None);
        let core_side = s.clone();
        assert_eq!(core_side.snapshot(), None);
        s.set(Some(WatcherId::new(1, 1)));
        assert_eq!(core_side.snapshot(), Some(WatcherId::new(1, 1)));
    }
}

mod net {
    use zk_proto::legacy::connect::{HostSpec as LegacyHost, NativeHost};
    use zk_proto::{HostSpec, ResolveError};
    use zk_testkit::net::*;

    #[test]
    fn resolves_literals_names_and_failures() {
        let mut n = Network::new();
        let lit = HostSpec::Legacy(LegacyHost {
            host: b"10.0.0.1".to_vec(),
            service: b"2181".to_vec(),
            port: 2181,
        });
        let r = n.resolve(0, &lit);
        assert_eq!(r.result, Ok(vec!["10.0.0.1:2181".parse().unwrap()]));
        let named = HostSpec::Native(NativeHost {
            host: "zk1".to_owned(),
            port: 2182,
        });
        assert!(n.resolve(1, &named).result.is_err());
        n.add_name("zk1", vec!["10.0.0.2".parse().unwrap()]);
        assert_eq!(
            n.resolve(1, &named).result,
            Ok(vec!["10.0.0.2:2182".parse().unwrap()])
        );
        let e = ResolveError {
            gai_rc: EAI_AGAIN,
            errno: 0,
        };
        n.fail_name("zk1", e, Some(1));
        assert_eq!(n.resolve(1, &named).result, Err(e));
        assert!(n.resolve(1, &named).result.is_ok());
        assert_eq!(n.resolve_calls(), 5);
    }
}

mod recorder {
    use zk_proto::{Millis, OpResult, Output};
    use zk_proto::{Origin, Route};
    use zk_testkit::recorder::*;
    use zk_testkit::tokens::TokenMint;

    #[test]
    fn exactly_once_counts_each_path() {
        let mut mint = TokenMint::new();
        let mut rec = Recorder::new();
        let (a, ta) = mint.completion();
        let (_b, tb) = mint.completion();
        let (_c, tc) = mint.completion();
        rec.issue(ta);
        rec.issue(tb);
        rec.issue(tc);
        rec.push(
            Millis(1),
            Event::Output(Output::Completion {
                token: a,
                route: Route::Queued,
                result: OpResult {
                    rc: 0,
                    zxid: 1,
                    origin: Origin::Reply,
                    reply: None,
                },
                delivery_logs: Vec::new(),
            }),
        );
        rec.push(Millis(1), Event::SubmitLocal(tb));
        assert!(rec.check_exactly_once().is_err());
        rec.leftover(tc);
        assert_eq!(rec.check_exactly_once(), Ok(()));
        assert_eq!(rec.completion(ta).map(|r| r.zxid), Some(1));
        assert_eq!(rec.delivery(tb), Some(Delivery::Local));
        assert_eq!(rec.completion_order(), vec![ta]);
        let m = rec.mark();
        rec.push(Millis(2), Event::Note("x".into()));
        assert_eq!(rec.since(m).len(), 1);
    }
}

mod rng {
    use zk_proto::Rng;
    use zk_testkit::rng::*;

    #[test]
    fn glibc_known_values() {
        // Initial outputs of unseeded glibc `random()`.
        let mut g = GlibcRandom::default();
        assert_eq!(g.next_u32(), 1_804_289_383);
        assert_eq!(g.next_u32(), 846_930_886);
        assert_eq!(g.next_u32(), 1_681_692_777);
        // Seed 0 is treated as seed 1 by glibc `srandom(0)`.
        assert_eq!(GlibcRandom::new(0), GlibcRandom::new(1));
    }

    #[test]
    fn drand48_known_values() {
        // `srand48(0)` yields 0.170828036106 as its initial sample.
        let mut d = Drand48::new(0);
        let v = d.next_f64();
        assert!((v - 0.170_828_036_106_087).abs() < 1e-12, "{v}");
        // Unseeded `drand48()` yields 0.396464773760275 as its initial sample.
        let mut u = Drand48::default();
        let w = u.next_f64();
        assert!((w - 0.396_464_773_760_275).abs() < 1e-12, "{w}");
    }

    #[test]
    fn seeded_rng_is_reproducible() {
        let log = RngLog::new();
        let mut a = SeededRng::new(9).with_log(log.clone());
        let mut b = SeededRng::new(9);
        for _ in 0..16 {
            assert_eq!(a.random(), b.random());
            let x = a.drand48();
            assert!((0.0..1.0).contains(&x));
            assert_eq!(x, b.drand48());
        }
        assert_eq!(log.len(), 32);
    }

    #[test]
    fn scripted_rng_uses_script_first() {
        let mut s = ScriptedRng::new(vec![5], vec![0.25], 1);
        assert_eq!(s.random(), 5);
        assert_eq!(s.drand48(), 0.25);
        let r = s.random();
        assert!((0..=i64::from(i32::MAX)).contains(&r));
    }

    #[test]
    fn libc_clone_reseeds() {
        let log = RngLog::new();
        let mut c = LibcRandomClone::new(vec![7]).with_log(log.clone());
        assert_eq!(c.random(), 1_804_289_383);
        c.on_resolution();
        let mut g = GlibcRandom::new(7);
        assert_eq!(c.random(), i64::from(g.next_u32()));
        c.on_resolution();
        let mut g = GlibcRandom::new(7);
        assert_eq!(c.random(), i64::from(g.next_u32()));
        assert_eq!(log.calls().get(1), Some(&RngCall::Reseed));
    }

    #[test]
    fn split_mix_ranges() {
        let mut s = SplitMix::new(3);
        for _ in 0..100 {
            let v = s.range_usize(2, 5);
            assert!((2..=5).contains(&v));
            let f = s.next_f64();
            assert!((0.0..1.0).contains(&f));
        }
        assert_eq!(s.below(0), 0);
    }
}

mod sasl {
    use zk_proto::SaslSession;
    use zk_testkit::sasl::*;

    #[test]
    fn follows_the_script() {
        let script = SaslScript {
            mechanism: "DIGEST-MD5".to_owned(),
            start: ScriptStep::Token {
                token: None,
                done: false,
            },
            steps: vec![ScriptStep::Fail { rc: -1 }],
            addr_failure: None,
            defer_ms: None,
        };
        let log = SaslLog::new();
        let mut s = ScriptedSasl::new(&script, log.clone());
        let (mech, step) = s.start().unwrap();
        assert_eq!(mech, "DIGEST-MD5");
        assert!(!step.done);
        assert_eq!(s.step(Some(b"ch")).unwrap_err().rc, -1);
        assert!(s.step(None).unwrap().done);
        assert_eq!(
            log.calls(),
            vec![
                SaslCallRecord::Start,
                SaslCallRecord::Step(Some(b"ch".to_vec())),
                SaslCallRecord::Step(None)
            ]
        );
    }
}

mod tokens {
    use zk_testkit::tokens::*;

    #[test]
    fn mint_gives_unique_ids() {
        let mut m = TokenMint::new();
        let (c, t) = m.completion();
        let (a, at) = m.auth();
        assert_eq!(c.ticket(), t);
        assert_eq!(a.ticket(), at);
        assert_ne!(c.id(), a.id());
        assert_eq!(CompletionId::new(7).id(), 7);
        assert_eq!(
            WatcherId::new(1, 2),
            WatcherId {
                fn_id: 1,
                ctx_id: 2
            }
        );
    }
}
