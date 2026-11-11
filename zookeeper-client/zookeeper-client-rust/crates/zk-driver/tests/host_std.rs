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

//! Verification of standard platform host adapters and thread spawner implementations.
//!
//! Asserts invariant guarantees of standard environment leaves:
//! - Logging filter thresholds and default configurations ([`crate::host_std::StdHostLeaves`]).
//! - PRNG statistical range bounds matching POSIX random(3) and drand48(3) contracts.
//! - Deterministic IP literal and DNS resolution parsing across legacy and native host
//!   specifications.
//! - Thread spawner thread naming and stack configuration controls
//!   ([`crate::spawn_std::StdSpawner`]).

#![allow(missing_docs)]
#![allow(clippy::disallowed_macros)]

mod host_std {
    #![allow(
        clippy::unwrap_used,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use zk_proto::legacy::connect::HostSpec as LegacyHostSpec;
    use zk_proto::legacy::connect::NativeHost;
    use zk_proto::{HostResolution, HostSpec, LogLevel, ResolveError};
    use zk_testkit::tokens::TestTokens;

    use zk_driver::host::HostLeaves;
    use zk_driver::host_std::*;
    use zk_driver::sync::Unlocked;

    fn native(host: &str, port: u16) -> HostSpec {
        HostSpec::Native(NativeHost {
            host: host.into(),
            port,
        })
    }

    fn resolve(leaves: &StdHostLeaves, host: usize, spec: &HostSpec) -> HostResolution {
        HostLeaves::<TestTokens>::resolve(leaves, &Unlocked::new_at_entry(), host, spec)
    }

    #[test]
    fn defaults_shuffle_and_log_nothing() {
        let leaves = StdHostLeaves::default();
        assert!(!HostLeaves::<TestTokens>::conn_permute_disabled(&leaves));
        assert!(!HostLeaves::<TestTokens>::log_enabled(
            &leaves,
            LogLevel::Error
        ));
    }

    #[test]
    fn log_level_is_an_upper_bound() {
        let mut leaves = StdHostLeaves::new();
        leaves.log_level = Some(LogLevel::Warn);
        assert!(HostLeaves::<TestTokens>::log_enabled(
            &leaves,
            LogLevel::Error
        ));
        assert!(HostLeaves::<TestTokens>::log_enabled(
            &leaves,
            LogLevel::Warn
        ));
        assert!(!HostLeaves::<TestTokens>::log_enabled(
            &leaves,
            LogLevel::Info
        ));
        assert!(!HostLeaves::<TestTokens>::log_enabled(
            &leaves,
            LogLevel::Debug
        ));
    }

    #[test]
    fn random_values_stay_in_range() {
        let leaves = StdHostLeaves::new();
        let mut seen_high = false;
        for _ in 0..10_000 {
            let r = HostLeaves::<TestTokens>::rng_random(&leaves);
            assert!((0..1_i64 << 31).contains(&r));
            seen_high |= r >= 1 << 30;
            let d = HostLeaves::<TestTokens>::rng_drand48(&leaves);
            assert!((0.0..1.0).contains(&d));
        }
        assert!(seen_high);
    }

    #[test]
    fn ip_literals_resolve_with_their_port() {
        let leaves = StdHostLeaves::new();
        let r = resolve(&leaves, 3, &native("127.0.0.1", 2181));
        assert_eq!(r.host, 3);
        assert_eq!(r.result.unwrap(), vec!["127.0.0.1:2181".parse().unwrap()]);
        assert!(r.skipped_families.is_empty());
        let r = resolve(&leaves, 0, &native("::1", 2182));
        assert_eq!(r.result.unwrap(), vec!["[::1]:2182".parse().unwrap()]);
    }

    #[test]
    fn legacy_entries_use_the_c_port() {
        let leaves = StdHostLeaves::new();
        let spec = HostSpec::Legacy(LegacyHostSpec {
            host: b"10.0.0.1".to_vec(),
            service: b"2181".to_vec(),
            port: 2181,
        });
        let r = resolve(&leaves, 1, &spec);
        assert_eq!(r.result.unwrap(), vec!["10.0.0.1:2181".parse().unwrap()]);
        let bad = HostSpec::Legacy(LegacyHostSpec {
            host: b"10.0.0.1".to_vec(),
            service: b"70000".to_vec(),
            port: 70_000,
        });
        let e = resolve(&leaves, 1, &bad).result.unwrap_err();
        assert_eq!(e.gai_rc, EAI_NONAME);
    }

    #[test]
    fn a_bad_host_is_a_resolver_error() {
        let leaves = StdHostLeaves::new();
        let spec = HostSpec::Legacy(LegacyHostSpec {
            host: vec![0xff, 0xfe],
            service: b"2181".to_vec(),
            port: 2181,
        });
        let e = resolve(&leaves, 0, &spec).result.unwrap_err();
        assert_eq!(
            e,
            ResolveError {
                gai_rc: EAI_NONAME,
                errno: 0
            }
        );
    }
}

#[cfg(feature = "std-thread")]
mod spawn_std {
    #![allow(clippy::unwrap_used)]

    use std::sync::mpsc;

    use zk_driver::native::Spawn;
    use zk_driver::spawn_std::*;

    fn run_on(spawner: &StdSpawner) -> Option<String> {
        let (tx, rx) = mpsc::channel();
        spawner
            .spawn(
                zk_driver::native::IO_THREAD_NAME,
                Box::new(move || {
                    let name = std::thread::current().name().map(str::to_owned);
                    let _ = tx.send(name);
                }),
            )
            .unwrap();
        rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap()
    }

    #[test]
    fn the_thread_has_the_given_name() {
        assert_eq!(run_on(&StdSpawner::new()).as_deref(), Some("zk-io"));
    }

    #[test]
    fn an_owned_name_is_used() {
        let (tx, rx) = mpsc::channel();
        StdSpawner::new()
            .spawn_owned(
                "zk-io-custom".to_owned(),
                Box::new(move || {
                    let name = std::thread::current().name().map(str::to_owned);
                    let _ = tx.send(name);
                }),
            )
            .unwrap();
        let name = rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
        assert_eq!(name.as_deref(), Some("zk-io-custom"));
    }

    #[test]
    fn a_stack_size_is_accepted() {
        let spawner = StdSpawner {
            stack_size: Some(262_144),
        };
        assert_eq!(run_on(&spawner).as_deref(), Some("zk-io"));
    }
}
