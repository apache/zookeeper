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

//! Verification of driver initialization state machine progression and start pathways.
//!
//! Asserts invariant guarantees across client bootstrap phases:
//! - Multi-step drop-in initialization sequence progression ([`crate::init::Init`]).
//! - SASL authentication barrier points during hostname resolution.
//! - Pre-resolution input syntax and chroot prefix validation.
//! - Granular address parsing error logging and recovery.
//! - Native threaded driver bootstrap and thread spawning failure handling
//!   ([`crate::native::start`]).

#![allow(missing_docs)]
#![allow(clippy::disallowed_macros)]

mod unit_util;

mod init {
    #![allow(
        clippy::unwrap_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use std::net::SocketAddr;
    use std::sync::Arc;

    use zk_proto::legacy::connect::EINVAL;
    use zk_proto::{Compat, Config, DriverMode, LogSite};
    use zk_testkit::tokens::TestTokens;

    use zk_driver::host::HostLeaves;
    use zk_driver::init::*;
    use zk_driver::outbox::{Flavor, Item, Outbox, ResumeAt};
    use zk_driver::state::Env;
    use zk_driver::sync::Unlocked;

    use crate::unit_util::TestHost;

    fn args(hosts: &[u8], sasl: bool) -> InitArgs {
        let mut cfg = Config::legacy(10_000);
        cfg.sasl = sasl;
        InitArgs {
            cfg,
            host_string: hosts.to_vec(),
            compat: Compat::LEGACY_C,
            mode: DriverMode::ST,
            flavor: Flavor::St,
        }
    }

    fn host_with(names: &[&[u8]]) -> Arc<TestHost> {
        let host = Arc::new(TestHost::new());
        for (i, name) in names.iter().enumerate() {
            let a: SocketAddr = format!("10.0.0.{}:2181", i + 1).parse().unwrap();
            host.set_addrs(name, vec![a]);
        }
        host
    }

    fn new_init(
        host: &Arc<TestHost>,
        hosts: &[u8],
        sasl: bool,
        ob: &mut Outbox<TestTokens>,
    ) -> Result<Init<TestTokens>, InitError> {
        let dyn_host: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        Init::new(args(hosts, sasl), Env::new(dyn_host), ob)
    }

    /// Extracts all emitted diagnostic log sites from the outbox in chronological order.
    fn drain(ob: &mut Outbox<TestTokens>) -> Vec<LogSite> {
        let mut sites = Vec::new();
        while let Some(item) = ob.pop() {
            if let Item::Log(r) = item {
                sites.push(r.site);
            }
        }
        sites
    }

    fn is_gai(site: &LogSite) -> bool {
        matches!(site, LogSite::ResolveGai | LogSite::ResolveGaiErrno)
    }

    #[test]
    fn good_string_resolves_and_is_ready() {
        let host = host_with(&[b"a", b"b"]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1,b:2/app", false, &mut ob).unwrap();
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(init.step(&mut proof, &mut ob), InitStep::Ready);
        assert_eq!(host.resolves(), 2);
        assert_eq!(host.reseeds(), 1);
        assert_eq!(init.step(&mut proof, &mut ob), InitStep::Ready);
    }

    #[test]
    fn sasl_point_comes_after_the_resolution() {
        let host = host_with(&[b"a"]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1", true, &mut ob).unwrap();
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(init.step(&mut proof, &mut ob), InitStep::SaslPoint);
        assert_eq!(ob.take_resume(), Some(ResumeAt::Init));
        assert_eq!(host.resolves(), 1);
        assert_eq!(init.step(&mut proof, &mut ob), InitStep::Ready);
    }

    #[test]
    fn empty_and_bad_chroot_strings_fail_before_any_resolution() {
        let host = host_with(&[b"a"]);
        let mut ob = Outbox::new();
        let einval = InitError {
            errno: Some(EINVAL),
        };
        assert_eq!(new_init(&host, b"", false, &mut ob).err(), Some(einval));
        assert_eq!(
            new_init(&host, b"\0a:1", false, &mut ob).err(),
            Some(einval)
        );
        let bad_root = new_init(&host, b"a:1/x/", false, &mut ob).err();
        assert_eq!(bad_root, Some(einval));
        assert_eq!(host.resolves(), 0);
    }

    #[test]
    fn port_error_comes_after_the_prefix_lines() {
        let host = host_with(&[]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1,c:2,b", false, &mut ob).unwrap();
        let mut proof = Unlocked::new_at_entry();
        // The line of host a waits before the leaf call for host c.
        assert_eq!(init.step(&mut proof, &mut ob), InitStep::More);
        assert_eq!(ob.take_resume(), Some(ResumeAt::Init));
        let first = drain(&mut ob);
        assert!(first.len() == 1 && first.iter().all(is_gai));
        let failed = InitStep::Failed(InitError {
            errno: Some(EINVAL),
        });
        assert_eq!(init.step(&mut proof, &mut ob), failed);
        let rest = drain(&mut ob);
        assert_eq!(rest.len(), 2);
        assert!(is_gai(&rest[0]));
        assert_eq!(rest[1], LogSite::ResolveNoPort);
        assert_eq!(host.resolves(), 2);
        assert_eq!(host.reseeds(), 0);
        // A failed init stays failed.
        assert_eq!(init.step(&mut proof, &mut ob), failed);
        init.abandon();
        assert!(host.discarded().is_empty());
    }

    #[test]
    fn invalid_port_runs_no_reseed() {
        let host = host_with(&[b"a"]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1,b:x", false, &mut ob).unwrap();
        let mut proof = Unlocked::new_at_entry();
        let step = init.step(&mut proof, &mut ob);
        assert_eq!(
            step,
            InitStep::Failed(InitError {
                errno: Some(EINVAL)
            })
        );
        assert_eq!(drain(&mut ob), [LogSite::ResolveBadPort]);
        assert_eq!(host.resolves(), 1);
        assert_eq!(host.reseeds(), 0);
    }

    #[test]
    fn unresolved_hosts_fail_with_the_hint() {
        let host = host_with(&[]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1", false, &mut ob).unwrap();
        let mut proof = Unlocked::new_at_entry();
        let step = init.step(&mut proof, &mut ob);
        assert!(matches!(
            step,
            InitStep::Failed(InitError { errno: Some(_) })
        ));
        assert!(drain(&mut ob).iter().all(is_gai));
    }
}

mod native {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use std::sync::Arc;

    use zk_proto::{Compat, Connection, DriverMode, LogLevel, Millis};
    use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
    use zk_testkit::rng::SeededRng;
    use zk_testkit::tokens::TestTokens;

    use zk_driver::dispatch::RouteSink;
    use zk_driver::host::HostLeaves;
    use zk_driver::native::*;
    use zk_driver::outbox::Item;
    use zk_driver::state::Env;

    use crate::unit_util::{TestHost, test_config};

    /// Test routing sink recording debug representations of all routed items.
    #[derive(Default)]
    struct Collect(std::sync::Mutex<Vec<String>>);

    impl RouteSink<TestTokens> for Collect {
        fn route(&self, item: Item<TestTokens>) -> bool {
            let counts = matches!(item, Item::Result { .. } | Item::AuthResult { .. });
            self.0.lock().unwrap().push(format!("{item:?}"));
            counts
        }
    }

    /// Mock thread spawner simulating operating system thread creation failures.
    #[derive(Default)]
    struct NoSpawn(std::sync::Mutex<Vec<&'static str>>);

    impl Spawn for NoSpawn {
        fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()> {
            self.0.lock().unwrap().push(name);
            drop(body);
            Err(std::io::Error::other("no threads in unit tests"))
        }
    }

    fn native_conn() -> Box<Connection<TestTokens>> {
        Box::new(Connection::new(
            test_config(b"127.0.0.1:2181"),
            Compat::NATIVE,
            DriverMode::NATIVE,
            Box::new(SeededRng::new(1)),
            Box::new(LevelFilter::up_to(LogLevel::Debug)),
            Box::new(SharedDefaultWatcher::new(None)),
            Millis(0),
        ))
    }

    fn native_env(host: &Arc<TestHost>, capacity: usize) -> Env<TestTokens> {
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        let mut env = Env::new(leaves);
        env.cfg.cloexec = true;
        env.cfg.dispatch_queue_capacity = capacity;
        env
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn start_reports_a_failed_spawn() {
        let host = Arc::new(TestHost::new());
        let spawner = Arc::new(NoSpawn::default());
        let mut env = native_env(&host, 8);
        env.cfg.cloexec = false;
        let r = start(
            native_conn(),
            env,
            Arc::new(Collect::default()),
            None,
            spawner.clone(),
        );
        assert!(matches!(r, Err(StartError::Spawn(_))));
        assert_eq!(*spawner.0.lock().unwrap(), vec![IO_THREAD_NAME]);
    }
}
