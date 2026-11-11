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

//! Host resolution and platform environment integration implementing [`zk_driver::HostLeaves`].
//!
//! Separates lock-free platform queries (such as DNS resolution) from state machine invariants.
//! Pure leaf operations execute synchronously under the protocol core lock without invoking
//! arbitrary user closures. Blocking hostname resolution ([`NativeHost::lookup`]) executes
//! with all protocol locks released, wrapped within [`catch_unwind`] and tagged with an I/O thread
//! re-entrancy marker to guard against deadlock and illegal nested blocking calls.

use std::net::{SocketAddr, ToSocketAddrs};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

use zk_driver::host_std::EAI_NONAME;
use zk_driver::{Discarded, HostLeaves, Unlocked};
use zk_proto::{ClientId, HostResolution, HostSpec, LogLevel, ResolveError};

use crate::builder::{Resolve, ServerOrder};
use crate::observe::Observer;
use crate::rng::NativeRng;
use crate::sync::enter_io_side;
use crate::tokens::{NativeTokens, WatchId};

/// Host-layer integration adapter binding client configuration to the `zk-driver` environment.
pub(crate) struct NativeHost {
    /// Client-isolated pseudorandom number generator for ensemble server shuffling.
    rng: NativeRng,
    /// Pluggable hostname resolution service (defaults to [`StdResolve`]).
    resolver: Arc<dyn Resolve>,
    /// Configured ensemble connection order policy.
    order: ServerOrder,
    /// Telemetry and structured logging sink.
    observe: Arc<Observer>,
    /// Numeric identifier identifying the associated client instance for thread tracking.
    client: u64,
}

impl std::fmt::Debug for NativeHost {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NativeHost")
            .field("order", &self.order)
            .field("client", &self.client)
            .finish_non_exhaustive()
    }
}

impl NativeHost {
    /// Constructs a host leaf adapter for the specified client configuration.
    pub(crate) fn new(
        resolver: Arc<dyn Resolve>,
        order: ServerOrder,
        observe: Arc<Observer>,
        client: u64,
    ) -> Self {
        Self {
            rng: NativeRng::new(),
            resolver,
            order,
            observe,
            client,
        }
    }

    /// Executes address resolution for a single ensemble host specification.
    ///
    /// The resolver invocation is protected against user panics and marked with the active I/O
    /// thread context to trap reentrant blocking attempts. Empty address lists, I/O errors, or
    /// caught panics translate cleanly into resolution failures without aborting the client
    /// process.
    fn lookup(&self, spec: &HostSpec) -> Result<Vec<SocketAddr>, ResolveError> {
        let HostSpec::Native(entry) = spec else {
            return Err(not_found(0));
        };
        let outcome = {
            let _io = enter_io_side(self.client);
            catch_unwind(AssertUnwindSafe(|| {
                self.resolver.resolve(&entry.host, entry.port)
            }))
        };
        match outcome {
            Ok(Ok(addrs)) if !addrs.is_empty() => Ok(addrs),
            Ok(Ok(_)) => Err(not_found(0)),
            Ok(Err(e)) => Err(not_found(e.raw_os_error().unwrap_or(0))),
            Err(payload) => {
                self.observe.hook_panicked(
                    "the resolver panicked, so the host fails this resolution",
                    payload,
                );
                Err(not_found(0))
            }
        }
    }
}

/// Constructs a standardized resolution failure matching `zk_driver::StdHostLeaves` conventions.
fn not_found(errno: i32) -> ResolveError {
    ResolveError {
        gai_rc: EAI_NONAME,
        errno,
    }
}

impl HostLeaves<NativeTokens> for NativeHost {
    /// Discards an in-flight token upon connection teardown or cancellation.
    ///
    /// Token destruction automatically closes the associated rendezvous slot, signaling terminal
    /// disconnection.
    fn discard_token(&self, token: Discarded<NativeTokens>) {
        drop(token);
    }

    /// The native client relies on dedicated per-operation watch sinks rather than a legacy
    /// default watcher.
    fn snapshot_default_watcher(&self) -> Option<WatchId> {
        None
    }

    /// No-op in native mode: session credentials are read directly from `Connection::client_id`
    /// under core synchronization.
    fn store_client_id(&self, _id: &ClientId) {}

    fn log_enabled(&self, level: LogLevel) -> bool {
        self.observe.log_enabled(level)
    }

    fn conn_permute_disabled(&self) -> bool {
        self.order == ServerOrder::AsGiven
    }

    fn rng_random(&self) -> i64 {
        self.rng.random()
    }

    fn rng_drand48(&self) -> f64 {
        self.rng.drand48()
    }

    /// No-op: the pseudorandom generator maintains independent per-client state without global
    /// process seeds.
    fn rng_reseed(&self, _proof: &Unlocked) {}

    /// Performs DNS resolution for an ensemble host entry via the configured resolver.
    ///
    /// Implementation contract:
    /// - Operates exclusively on `HostSpec::Native` entries.
    /// - Runs within the I/O thread marker and unwinding boundary to safeguard against blocking
    ///   and panics.
    /// - Maps OS socket errors into canonical `ResolveError` structures with `EAI_NONAME`
    ///   semantics.
    /// - Rejects empty address lists as resolution failures.
    fn resolve(&self, _proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        HostResolution {
            host,
            result: self.lookup(spec),
            skipped_families: Vec::new(),
        }
    }
}

/// Default DNS resolver implementation backed by standard library socket address resolution.
#[derive(Copy, Clone, Debug, Default)]
pub(crate) struct StdResolve;

impl Resolve for StdResolve {
    /// Resolves a host-port pair into socket addresses, parsing IP literals directly without DNS
    /// queries.
    fn resolve(&self, host: &str, port: u16) -> std::io::Result<Vec<SocketAddr>> {
        Ok((host, port).to_socket_addrs()?.collect())
    }
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use zk_proto::native::NativeHost as NativeEntry;

    use super::*;
    use crate::sync::{LockExt, blocking_refused, on_io_side};

    /// Mock DNS resolver implementation delegating to a test closure while recording invocation
    /// frequency.
    struct FnResolve<F> {
        f: F,
        calls: Arc<AtomicUsize>,
    }

    impl<F> Resolve for FnResolve<F>
    where
        F: Fn(&str, u16) -> io::Result<Vec<SocketAddr>> + Send + Sync + 'static,
    {
        fn resolve(&self, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            (self.f)(host, port)
        }
    }

    fn host_with<F>(f: F) -> (NativeHost, Arc<AtomicUsize>)
    where
        F: Fn(&str, u16) -> io::Result<Vec<SocketAddr>> + Send + Sync + 'static,
    {
        let calls = Arc::new(AtomicUsize::new(0));
        let r = FnResolve {
            f,
            calls: Arc::clone(&calls),
        };
        let h = NativeHost::new(
            Arc::new(r),
            ServerOrder::Shuffled,
            Arc::new(Observer::new(Some("t".into()), None)),
            7,
        );
        (h, calls)
    }

    fn native(host: &str, port: u16) -> HostSpec {
        HostSpec::Native(NativeEntry {
            host: host.into(),
            port,
        })
    }

    fn run(h: &NativeHost, index: usize, spec: &HostSpec) -> HostResolution {
        let proof = Unlocked::new_at_entry();
        HostLeaves::<NativeTokens>::resolve(h, &proof, index, spec)
    }

    fn failed(errno: i32) -> Result<Vec<SocketAddr>, ResolveError> {
        Err(ResolveError {
            gai_rc: EAI_NONAME,
            errno,
        })
    }

    #[test]
    fn the_pure_leaves_need_no_driver() {
        let h = NativeHost::new(
            Arc::new(StdResolve),
            ServerOrder::AsGiven,
            Arc::new(Observer::new(None, None)),
            1,
        );
        assert!(h.conn_permute_disabled());
        assert!(h.snapshot_default_watcher().is_none());
        let d = h.rng_drand48();
        assert!((0.0..1.0).contains(&d));
    }

    #[test]
    fn a_resolver_success_keeps_the_order_and_the_duplicates() {
        let a: SocketAddr = "10.0.0.2:2181".parse().unwrap();
        let b: SocketAddr = "[::1]:2181".parse().unwrap();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let seen2 = Arc::clone(&seen);
        let (h, calls) = host_with(move |host, port| {
            seen2.lock_unpoisoned().push((host.to_owned(), port));
            Ok(vec![a, b, a])
        });
        let r = run(&h, 3, &native("zk.example", 2181));
        assert_eq!(r.host, 3);
        assert_eq!(r.result, Ok(vec![a, b, a]));
        assert!(r.skipped_families.is_empty());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert_eq!(
            *seen.lock_unpoisoned(),
            vec![("zk.example".to_owned(), 2181)]
        );
    }

    #[test]
    fn a_resolver_error_maps_like_the_std_leaves() {
        let (h, _) = host_with(|_, _| Err(io::Error::from_raw_os_error(61)));
        assert_eq!(run(&h, 0, &native("a", 1)).result, failed(61));
        let (h, _) = host_with(|_, _| Err(io::Error::other("no such host")));
        assert_eq!(run(&h, 0, &native("a", 1)).result, failed(0));
    }

    #[test]
    fn an_empty_list_fails_the_host() {
        let (h, _) = host_with(|_, _| Ok(Vec::new()));
        let r = run(&h, 1, &native("a", 1));
        assert_eq!(r.host, 1);
        assert_eq!(r.result, failed(0));
        assert!(r.skipped_families.is_empty());
    }

    #[test]
    fn a_resolver_panic_fails_the_host_and_the_next_call_runs() {
        let flip = Arc::new(AtomicUsize::new(0));
        let flip2 = Arc::clone(&flip);
        let good: SocketAddr = "127.0.0.1:2181".parse().unwrap();
        let (h, calls) = host_with(move |_, _| {
            if flip2.fetch_add(1, Ordering::SeqCst) == 0 {
                std::panic::panic_any("resolver bug");
            }
            Ok(vec![good])
        });
        assert_eq!(run(&h, 0, &native("a", 1)).result, failed(0));
        // Ensure thread-local execution context sentinel is cleared upon panic unwinding.
        assert!(!on_io_side());
        // Transient panics during resolution do not permanently disable subsequent resolver
        // invocations.
        assert_eq!(run(&h, 0, &native("a", 1)).result, Ok(vec![good]));
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the payload of the second panic leaks by design, and the Miri leak check fails on it"
    )]
    fn a_resolver_with_a_bad_payload_drop_does_not_unwind() {
        struct BadDrop;
        impl Drop for BadDrop {
            fn drop(&mut self) {
                std::panic::panic_any("drop bug");
            }
        }
        let (h, _) = host_with(|_, _| std::panic::panic_any(BadDrop));
        assert_eq!(run(&h, 0, &native("a", 1)).result, failed(0));
    }

    #[test]
    fn a_blocking_wait_inside_the_resolver_is_refused() {
        let inside = Arc::new(Mutex::new(None));
        let inside2 = Arc::clone(&inside);
        let (h, _) = host_with(move |_, _| {
            *inside2.lock_unpoisoned() = Some((on_io_side(), blocking_refused(false)));
            Ok(Vec::new())
        });
        assert!(!on_io_side());
        let _ = run(&h, 0, &native("a", 1));
        assert_eq!(*inside.lock_unpoisoned(), Some((true, true)));
        assert!(!on_io_side());
    }

    #[test]
    fn a_legacy_entry_fails_without_the_resolver() {
        let (h, calls) = host_with(|_, _| Ok(vec!["127.0.0.1:1".parse().unwrap()]));
        let spec = HostSpec::Legacy(zk_proto::legacy::connect::HostSpec {
            host: b"127.0.0.1".to_vec(),
            service: b"2181".to_vec(),
            port: 2181,
        });
        let r = run(&h, 2, &spec);
        assert_eq!(r.host, 2);
        assert_eq!(r.result, failed(0));
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn std_resolve_takes_ip_literals_without_dns() {
        let v4 = StdResolve.resolve("127.0.0.1", 2181).unwrap();
        assert_eq!(v4, vec!["127.0.0.1:2181".parse::<SocketAddr>().unwrap()]);
        let v6 = StdResolve.resolve("::1", 2182).unwrap();
        assert_eq!(v6, vec!["[::1]:2182".parse::<SocketAddr>().unwrap()]);
    }

    #[test]
    fn std_resolve_rejects_a_bad_name() {
        // A NUL byte fails before any lookup.
        assert!(StdResolve.resolve("bad\0host", 2181).is_err());
    }

    #[test]
    fn the_default_resolver_runs_through_the_leaf() {
        let h = NativeHost::new(
            Arc::new(StdResolve),
            ServerOrder::AsGiven,
            Arc::new(Observer::new(None, None)),
            1,
        );
        let r = run(&h, 0, &native("127.0.0.1", 2181));
        assert_eq!(r.result, Ok(vec!["127.0.0.1:2181".parse().unwrap()]));
    }
}
