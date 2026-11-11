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

//! Protocol adapter bridging [`SaslMechanism`] to internal `zk_proto::SaslSession` lifecycles.
//!
//! The driver retains a single adapter instance across the lifespan of a client connection. Each
//! invocation of `start` queries the mechanism factory to instantiate a clean mechanism instance,
//! ensuring that no negotiation state leaks across consecutive connection attempts. The adapter
//! maps high-level [`SaslError`] occurrences into wire level protocol representations while
//! recording failure details in [`SessionInfo::last_error`]. Panics arising within mechanism state
//! machines or credential providers are caught via unwinding boundaries and translated into
//! structured [`SaslErrorKind::Mechanism`] failures to prevent worker thread termination.

use std::sync::{Arc, Mutex};

use crate::sync::LockExt;

use super::{
    SaslConfig, SaslContext, SaslError, SaslErrorKind, SaslMechanism, SaslMechanismFactory,
};

/// Wire protocol error code returned upon internal SASL mechanism failure.
const ZSYSTEMERROR: i32 = -1;

/// Shared slot recording the most recent high-level SASL error for inspection via
/// [`SessionInfo::last_error`].
pub(crate) type LastSaslError = Arc<Mutex<Option<SaslError>>>;

/// Internal SASL session coordinator implementing [`zk_proto::SaslSession`].
pub(crate) struct NativeSasl {
    /// Factory creating fresh mechanism state machines per connection attempt.
    factory: Arc<dyn SaslMechanismFactory>,
    /// Connection endpoint details supplied by the protocol layer prior to negotiation.
    ctx: Option<SaslContext>,
    /// Active mechanism state machine executing the current handshake.
    mech: Option<Box<dyn SaslMechanism>>,
    /// Shared reference recording diagnostic authentication errors.
    last_error: LastSaslError,
}

impl NativeSasl {
    /// Constructs a new protocol adapter for the specified SASL configuration.
    pub(crate) fn new(config: SaslConfig, last_error: LastSaslError) -> Self {
        Self {
            factory: factory(config),
            ctx: None,
            mech: None,
            last_error,
        }
    }
}

impl NativeSasl {
    /// Instantiates the mechanism state machine for the pending connection and triggers the
    /// initial handshake step.
    ///
    /// Consumes the staged [`SaslContext`]. Subsequent attempts without refreshed connection
    /// details fail immediately.
    fn begin(&mut self) -> Result<(String, zk_proto::SaslStep), SaslError> {
        let ctx = self.ctx.take().ok_or_else(|| {
            SaslError::new(SaslErrorKind::Mechanism, "the SASL connection is not known")
        })?;
        let factory = Arc::clone(&self.factory);
        let mut mech = guard(|| factory.create(&ctx))?;
        let first = guard(|| mech.start());
        let name = guard(|| Ok(mech.name().to_owned()));
        let (first, name) = (first?, name?);
        self.mech = Some(mech);
        Ok((name, to_proto_step(first)))
    }

    /// Records diagnostic error details into shared state and converts errors into protocol wire
    /// representations.
    fn answer<T>(&mut self, result: Result<T, SaslError>) -> Result<T, zk_proto::SaslError> {
        result.map_err(|e| {
            self.mech = None;
            let proto = to_proto(&e);
            *self.last_error.lock_unpoisoned() = Some(e);
            proto
        })
    }
}

/// Resolves the concrete [`SaslMechanismFactory`] corresponding to the provided configuration.
fn factory(config: SaslConfig) -> Arc<dyn SaslMechanismFactory> {
    match config {
        SaslConfig::DigestMd5 {
            username,
            password,
            service,
            server_name,
        } => Arc::new(super::digest_md5::DigestMd5Factory::new(
            username,
            password,
            service,
            server_name,
        )),
        #[cfg(feature = "sasl-gssapi")]
        SaslConfig::Gssapi {
            service,
            server_name,
        } => Arc::new(super::gssapi::GssapiFactory::new(service, server_name)),
        SaslConfig::Custom(f) => f,
    }
}

impl zk_proto::SaslSession for NativeSasl {
    /// Discards any stale mechanism state, constructs a new mechanism for the staged connection
    /// context, and dispatches the initial handshake step.
    fn start(&mut self) -> Result<(String, zk_proto::SaslStep), zk_proto::SaslError> {
        self.mech = None;
        let result = self.begin();
        self.answer(result)
    }

    /// Advances the active mechanism state machine by processing the provided server challenge.
    fn step(
        &mut self,
        challenge: Option<&[u8]>,
    ) -> Result<zk_proto::SaslStep, zk_proto::SaslError> {
        let result = match self.mech.as_mut() {
            Some(mech) => guard(|| mech.step(challenge)),
            None => Err(SaslError::new(
                SaslErrorKind::Mechanism,
                "no SASL mechanism is running",
            )),
        };
        self.answer(result.map(to_proto_step))
    }

    fn connection(&mut self, host: &[u8], local: std::net::SocketAddr, peer: std::net::SocketAddr) {
        self.ctx = Some(SaslContext {
            server_host: String::from_utf8_lossy(host).into_owned(),
            server_addr: peer,
            local_addr: local,
        });
    }
}

/// Translates a high-level [`SaslError`] into a wire-level protocol error record.
pub(crate) fn to_proto(e: &SaslError) -> zk_proto::SaslError {
    zk_proto::SaslError {
        rc: ZSYSTEMERROR,
        errstring: e.message.as_bytes().to_vec(),
        errdetail: Vec::new(),
    }
}

/// Converts a public [`super::SaslStep`] into a protocol [`zk_proto::SaslStep`], wrapping response
/// tokens in zeroizing buffers.
pub(crate) fn to_proto_step(s: super::SaslStep) -> zk_proto::SaslStep {
    zk_proto::SaslStep {
        token: s.token.map(zk_proto::SecretBytes::from),
        done: s.done,
    }
}

/// Executes a closure across an unwinding boundary, converting any caught panic into a structured
/// mechanism error.
pub(crate) fn guard<R>(f: impl FnOnce() -> Result<R, SaslError>) -> Result<R, SaslError> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(_) => Err(SaslError::new(
            SaslErrorKind::Mechanism,
            "the SASL mechanism panicked",
        )),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic, reason = "test code")]
mod tests {
    use std::net::SocketAddr;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use zk_proto::SaslSession;

    use super::*;
    use crate::sasl::SaslStep;

    /// Test factory tracking instantiation counts and simulating panics.
    struct Counting {
        made: AtomicUsize,
        panic: bool,
    }

    impl SaslMechanismFactory for Counting {
        fn create(&self, ctx: &SaslContext) -> Result<Box<dyn SaslMechanism>, SaslError> {
            assert!(!self.panic, "the factory panics");
            self.made.fetch_add(1, Ordering::SeqCst);
            Ok(Box::new(Once(ctx.server_host.clone())))
        }
    }

    struct Once(String);

    impl SaslMechanism for Once {
        fn name(&self) -> &str {
            "ONCE"
        }

        fn start(&mut self) -> Result<SaslStep, SaslError> {
            Ok(SaslStep {
                token: Some(self.0.as_bytes().to_vec()),
                done: false,
            })
        }

        fn step(&mut self, _challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
            Ok(SaslStep {
                token: None,
                done: true,
            })
        }
    }

    fn adapter(panic: bool) -> (NativeSasl, Arc<Counting>, LastSaslError) {
        let f = Arc::new(Counting {
            made: AtomicUsize::new(0),
            panic,
        });
        let last = LastSaslError::default();
        let factory: Arc<dyn SaslMechanismFactory> = f.clone();
        let s = NativeSasl::new(SaslConfig::Custom(factory), Arc::clone(&last));
        (s, f, last)
    }

    fn addr() -> SocketAddr {
        SocketAddr::from(([127, 0, 0, 1], 2181))
    }

    fn kind(last: &LastSaslError) -> Option<SaslErrorKind> {
        last.lock_unpoisoned().as_ref().map(|e| e.kind.clone())
    }

    // Verify that every distinct connection attempt allocates a fresh mechanism instance with
    // accurate host context.
    #[test]
    fn each_start_makes_a_new_mechanism() {
        let (mut s, f, last) = adapter(false);
        for host in ["zk1", "zk2"] {
            s.connection(host.as_bytes(), addr(), addr());
            let (name, first) = s.start().unwrap();
            assert_eq!(name, "ONCE");
            assert_eq!(first.token.unwrap().expose(), host.as_bytes());
            assert!(s.step(Some(b"x")).unwrap().done);
        }
        assert_eq!(f.made.load(Ordering::SeqCst), 2);
        assert!(kind(&last).is_none());
        assert!(s.start().is_err());
        assert_eq!(kind(&last), Some(SaslErrorKind::Mechanism));
    }

    // Verify that stepping an uninitialized mechanism produces a structured protocol error.
    #[test]
    fn a_step_with_no_mechanism_fails() {
        let (mut s, _, last) = adapter(false);
        let e = s.step(None).unwrap_err();
        assert_eq!(e.rc, ZSYSTEMERROR);
        assert_eq!(e.errstring, b"no SASL mechanism is running");
        assert_eq!(kind(&last), Some(SaslErrorKind::Mechanism));
    }

    // Verify that factory panics are caught at the boundary and converted into mechanism errors.
    #[test]
    fn a_factory_panic_is_a_mechanism_error() {
        let (mut s, _, last) = adapter(true);
        s.connection(b"zk1", addr(), addr());
        let e = s.start().unwrap_err();
        assert_eq!(e.errstring, b"the SASL mechanism panicked");
        assert_eq!(kind(&last), Some(SaslErrorKind::Mechanism));
        assert!(s.mech.is_none());
    }
}
