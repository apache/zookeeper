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

//! In-memory protocol verification of TLS session framing, verifiers, and connectors.
//!
//! Asserts transport behavior against rustls test server instances:
//! - Full ClientHello / ServerHello TLS 1.2 and TLS 1.3 handshake exchange.
//! - Bidirectional application record framing, chunk reassembly, and fragment buffering.
//! - Explicit error latching: premature EOF, corrupted record mac/padding, and fatal alerts.
//! - Cryptographic teardown via reciprocal `close_notify` alert propagation.
//! - Chain-only vs. WebPKI certificate verifiers, SAN hostname checks, and temporal validity.
//! - Dynamic CA reloading on connector instances and SNI negotiation.

#![cfg(feature = "tls")]
#![allow(missing_docs)]
#![allow(clippy::disallowed_macros)]

mod tls_util;
mod unit_util;

#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
mod conn {
    use std::io::Write;

    use zk_driver::net::tls::{TlsConn, TlsFailure};

    use crate::tls_util::{
        TLS12, TLS13, client, connected, exchange, server, server_in, server_out,
    };

    /// TLS ContentType 22 (handshake record frame).
    const HANDSHAKE: u8 = 22;

    /// Drains all available decrypted application plaintext from a TLS session.
    fn read_all(c: &mut dyn TlsConn) -> (Vec<u8>, Result<usize, TlsFailure>) {
        let mut out = Vec::new();
        let first = c.read_plaintext(&mut out);
        (out, first)
    }

    /// Asserts that initial outbound ciphertext emits a valid TLS ClientHello record frame.
    #[test]
    fn first_ciphertext_is_the_client_hello() {
        let mut c = client("ca.pem");
        assert!(c.is_handshaking());
        assert!(c.has_ciphertext());
        let mut ct = Vec::new();
        let n = c.take_ciphertext(&mut ct);
        assert_eq!(n, ct.len());
        assert_eq!(ct.first(), Some(&HANDSHAKE));
        // Five-byte record header precedes handshake body; ClientHello handshake type is 1.
        assert_eq!(ct.get(5), Some(&1));
        assert!(!c.has_ciphertext());
        assert_eq!(c.take_ciphertext(&mut ct), 0);
    }

    /// Asserts that the TLS handshake engine survives single-byte network chunks without
    /// corruption.
    #[test]
    fn handshake_survives_one_byte_reads() {
        let mut c = client("ca.pem");
        let mut s = server(TLS13);
        exchange(c.as_mut(), &mut s, true).unwrap();
        assert!(!c.is_handshaking());
        s.writer().write_all(b"split").unwrap();
        for b in server_out(&mut s) {
            c.feed(&[b]).unwrap();
        }
        assert_eq!(read_all(c.as_mut()), (b"split".to_vec(), Ok(5)));
    }

    /// Asserts bidirectional application record transmission across TLS 1.2 and TLS 1.3 sessions.
    #[test]
    fn frames_round_trip_both_ways() {
        for versions in [TLS13, TLS12] {
            let (mut c, mut s) = connected(versions);
            c.write_frame(b"hello").unwrap();
            assert!(c.has_ciphertext());
            let mut ct = Vec::new();
            c.take_ciphertext(&mut ct);
            assert_eq!(server_in(&mut s, &ct), b"hello");

            s.writer().write_all(b"world").unwrap();
            c.feed(&server_out(&mut s)).unwrap();
            assert_eq!(read_all(c.as_mut()), (b"world".to_vec(), Ok(5)));
            assert_eq!(read_all(c.as_mut()), (Vec::new(), Ok(0)));
        }
    }

    /// Asserts that large application payloads are fragmented into standard TLS records and
    /// reassembled wholly.
    #[test]
    fn large_chunks_are_taken_whole() {
        let (mut c, mut s) = connected(TLS13);
        let big: Vec<u8> = (0..100_000u32).map(|i| i.to_le_bytes()[0]).collect();
        c.write_frame(&big).unwrap();
        let mut ct = Vec::new();
        c.take_ciphertext(&mut ct);
        assert_eq!(server_in(&mut s, &ct), big);

        let reply: Vec<u8> = (0..200_000u32).map(|i| i.to_le_bytes()[1]).collect();
        s.writer().write_all(&reply).unwrap();
        c.feed(&server_out(&mut s)).unwrap();
        assert_eq!(read_all(c.as_mut()), (reply.clone(), Ok(reply.len())));
    }

    /// Asserts that peer `close_notify` alerts latch session termination following unread
    /// plaintext.
    #[test]
    fn close_notify_is_latched_after_the_plaintext() {
        let (mut c, mut s) = connected(TLS13);
        s.writer().write_all(b"abc").unwrap();
        s.send_close_notify();
        assert_eq!(c.feed(&server_out(&mut s)), Err(TlsFailure::Closed));
        assert_eq!(read_all(c.as_mut()), (b"abc".to_vec(), Ok(3)));
        assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Closed)));
        assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Closed)));
        assert_eq!(c.feed(b"more"), Err(TlsFailure::Closed));
        assert_eq!(c.feed_eof(), Err(TlsFailure::Closed));
        assert_eq!(c.write_frame(b"x"), Err(TlsFailure::Closed));
        c.send_close_notify();
        assert!(!c.has_ciphertext());
    }

    /// Asserts that corrupted record payloads latch fatal data errors after draining prior valid
    /// records.
    #[test]
    fn bad_record_is_latched_after_the_plaintext() {
        for versions in [TLS13, TLS12] {
            let (mut c, mut s) = connected(versions);
            s.writer().write_all(b"abc").unwrap();
            let mut ct = server_out(&mut s);
            s.writer().write_all(b"def").unwrap();
            let mut bad = server_out(&mut s);
            let last = bad.len() - 1;
            bad[last] ^= 0x55;
            ct.extend_from_slice(&bad);
            assert_eq!(c.feed(&ct), Err(TlsFailure::Data));
            assert_eq!(read_all(c.as_mut()), (b"abc".to_vec(), Ok(3)));
            assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Data)));
            assert!(c.has_ciphertext());
            let mut alert = Vec::new();
            c.take_ciphertext(&mut alert);
            server_in(&mut s, &alert);
            assert!(s.process_new_packets().is_err());
            assert_eq!(c.feed_eof(), Err(TlsFailure::Data));
        }
    }

    /// Asserts that premature transport EOF during handshake negotiation latches a handshake
    /// failure.
    #[test]
    fn eof_in_the_handshake_is_a_handshake_failure() {
        let mut c = client("ca.pem");
        assert_eq!(c.feed_eof(), Err(TlsFailure::Handshake));
        assert_eq!(
            read_all(c.as_mut()),
            (Vec::new(), Err(TlsFailure::Handshake))
        );

        let mut c = client("ca.pem");
        let mut s = server(TLS13);
        let mut ct = Vec::new();
        c.take_ciphertext(&mut ct);
        server_in(&mut s, &ct);
        let flight = server_out(&mut s);
        c.feed(&flight[..flight.len() / 2]).unwrap();
        assert!(c.is_handshaking());
        assert_eq!(c.feed_eof(), Err(TlsFailure::Handshake));
        assert_eq!(
            read_all(c.as_mut()),
            (Vec::new(), Err(TlsFailure::Handshake))
        );
    }

    /// Asserts that transport EOF following handshake completion latches a closed state after
    /// reading valid records.
    #[test]
    fn eof_after_the_handshake_latches_closed_after_the_plaintext() {
        let (mut c, mut s) = connected(TLS13);
        s.writer().write_all(b"xyz").unwrap();
        c.feed(&server_out(&mut s)).unwrap();
        assert_eq!(c.feed_eof(), Err(TlsFailure::Closed));
        assert_eq!(read_all(c.as_mut()), (b"xyz".to_vec(), Ok(3)));
        assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Closed)));
        assert!(!c.has_ciphertext());
    }

    /// Asserts that unexpected EOF truncating a record frame latches an immediate closed error.
    #[test]
    fn eof_inside_a_record_latches_closed() {
        let (mut c, mut s) = connected(TLS13);
        s.writer().write_all(b"xyz").unwrap();
        let ct = server_out(&mut s);
        c.feed(&ct[..ct.len() - 3]).unwrap();
        assert_eq!(c.feed_eof(), Err(TlsFailure::Closed));
        assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Closed)));
    }

    /// Asserts that certificate verification failure emits a fatal alert and latches a handshake
    /// error.
    #[test]
    fn verify_failure_latches_a_handshake_failure_with_an_alert() {
        let mut c = client("wrong-ca.pem");
        let mut s = server(TLS13);
        assert_eq!(
            exchange(c.as_mut(), &mut s, false),
            Err(TlsFailure::Handshake)
        );
        assert!(c.is_handshaking());
        assert!(c.has_ciphertext());
        assert_eq!(
            read_all(c.as_mut()),
            (Vec::new(), Err(TlsFailure::Handshake))
        );
    }

    /// Asserts that receiving a fatal TLS alert during handshake progression latches a handshake
    /// failure.
    #[test]
    fn received_alert_in_the_handshake_is_a_handshake_failure() {
        let mut c = client("ca.pem");
        let mut ct = Vec::new();
        c.take_ciphertext(&mut ct);
        let alert = [21u8, 3, 3, 0, 2, 2, 40];
        assert_eq!(c.feed(&alert), Err(TlsFailure::Handshake));
        assert!(!c.has_ciphertext());
    }

    /// Asserts that peer fatal alerts received during application data exchange latch closed
    /// connection status.
    #[test]
    fn received_alert_in_the_data_phase_is_a_close() {
        for versions in [TLS13, TLS12] {
            let (mut c, mut s) = connected(versions);
            c.write_frame(b"x").unwrap();
            let mut ct = Vec::new();
            c.take_ciphertext(&mut ct);
            let last = ct.len() - 1;
            ct[last] ^= 0x55;
            s.read_tls(&mut &ct[..]).unwrap();
            assert!(s.process_new_packets().is_err());
            let alert = server_out(&mut s);
            assert!(!alert.is_empty());
            assert_eq!(c.feed(&alert), Err(TlsFailure::Closed));
            assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Closed)));
        }
    }

    /// Asserts that malformed continuous record streams are rejected without entering non-
    /// terminating busy loops.
    #[test]
    fn garbage_records_stop_without_a_busy_loop() {
        let (mut c, _s) = connected(TLS13);
        let mut junk = Vec::new();
        for _ in 0..64 {
            junk.extend_from_slice(&[23, 3, 3, 0x40, 0x00]);
            junk.extend(std::iter::repeat_n(0u8, 0x4000));
        }
        assert_eq!(c.feed(&junk), Err(TlsFailure::Data));
        assert_eq!(read_all(c.as_mut()), (Vec::new(), Err(TlsFailure::Data)));
    }

    /// Asserts that local invocation of `send_close_notify` emits a valid alert record received
    /// by the peer.
    #[test]
    fn close_notify_reaches_the_server() {
        let (mut c, mut s) = connected(TLS13);
        c.send_close_notify();
        assert!(c.has_ciphertext());
        let mut ct = Vec::new();
        c.take_ciphertext(&mut ct);
        server_in(&mut s, &ct);
        let mut buf = [0u8; 8];
        assert_eq!(std::io::Read::read(&mut s.reader(), &mut buf).unwrap(), 0);
    }

    /// Asserts that invoking `send_close_notify` prior to handshake completion is safely
    /// suppressed.
    #[test]
    fn close_notify_in_the_handshake_sends_nothing() {
        let mut c = client("ca.pem");
        let mut ct = Vec::new();
        c.take_ciphertext(&mut ct);
        c.send_close_notify();
        assert!(!c.has_ciphertext());
    }

    /// Asserts that application frames queued during active handshake negotiation are transmitted
    /// upon establishment.
    #[test]
    fn frames_written_in_the_handshake_go_out_after_it() {
        let mut c = client("ca.pem");
        let mut s = server(TLS13);
        c.write_frame(b"early").unwrap();
        assert_eq!(exchange(c.as_mut(), &mut s, false).unwrap(), b"early");
    }
}

#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
mod verify {
    use std::sync::Arc;
    use std::time::Duration;

    use rustls::client::danger::{ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::{CertificateDer, IpAddr, ServerName, UnixTime};
    use rustls::{CertificateError, Error, RootCertStore};

    use zk_driver::net::tls::verify::*;
    use zk_driver::net::tls::{TlsResourceAt, TlsSetupError};

    use crate::tls_util::{certs, provider, roots};

    /// Timestamp falling within fixture validity bounds (2030-01-01T00:00:00Z).
    fn valid_time() -> UnixTime {
        UnixTime::since_unix_epoch(Duration::from_secs(1_893_456_000))
    }

    /// Timestamp exceeding fixture validity bounds (2200-01-01T00:00:00Z).
    fn late_time() -> UnixTime {
        UnixTime::since_unix_epoch(Duration::from_secs(7_258_118_400))
    }

    fn dns(name: &'static str) -> ServerName<'static> {
        ServerName::try_from(name).unwrap()
    }

    fn ip(octets: [u8; 4]) -> ServerName<'static> {
        ServerName::IpAddress(IpAddr::from(std::net::IpAddr::from(octets)))
    }

    fn check(
        v: &dyn ServerCertVerifier,
        leaf: &str,
        name: &ServerName<'_>,
        now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let chain = certs(leaf);
        v.verify_server_cert(&chain[0], &chain[1..], name, &[], now)
    }

    /// Asserts that chain-only verifiers validate trust hierarchy while disregarding subject
    /// alternative names.
    #[test]
    fn chain_only_accepts_any_name() {
        let v = ChainOnlyVerifier::new(roots("ca.pem"), provider());
        for name in [
            dns("example.com"),
            dns("localhost"),
            ip([10, 0, 0, 1]),
            ip([127, 0, 0, 1]),
        ] {
            check(&v, "server.pem", &name, valid_time()).unwrap();
        }
        // Auxiliary OCSP payloads are ignored under chain-only mode.
        let chain = certs("server.pem");
        v.verify_server_cert(&chain[0], &[], &dns("x.invalid"), b"junk", valid_time())
            .unwrap();
    }

    /// Asserts that chain-only verifiers reject certificates signed by untrusted authorities.
    #[test]
    fn chain_only_rejects_another_ca() {
        let v = ChainOnlyVerifier::new(roots("wrong-ca.pem"), provider());
        assert_eq!(
            check(&v, "server.pem", &ip([127, 0, 0, 1]), valid_time()).unwrap_err(),
            Error::InvalidCertificate(CertificateError::UnknownIssuer)
        );
    }

    /// Asserts that expired leaf certificates are rejected by chain-only verifiers.
    #[test]
    fn chain_only_rejects_an_expired_leaf() {
        let v = ChainOnlyVerifier::new(roots("ca.pem"), provider());
        let err = check(&v, "server.pem", &ip([127, 0, 0, 1]), late_time()).unwrap_err();
        assert!(
            matches!(
                err,
                Error::InvalidCertificate(
                    CertificateError::Expired | CertificateError::ExpiredContext { .. }
                )
            ),
            "{err:?}"
        );
    }

    /// Asserts that certificates omitting the server authentication extended key usage are
    /// rejected.
    #[test]
    fn chain_only_rejects_a_client_only_leaf() {
        let v = ChainOnlyVerifier::new(roots("ca.pem"), provider());
        assert!(check(&v, "client.pem", &ip([127, 0, 0, 1]), valid_time()).is_err());
    }

    /// Asserts that malformed DER byte sequences fail certificate parsing.
    #[test]
    fn chain_only_rejects_a_leaf_that_does_not_parse() {
        let v = ChainOnlyVerifier::new(roots("ca.pem"), provider());
        let junk = CertificateDer::from(vec![0x30, 0x03, 0x02, 0x01, 0x00]);
        assert!(
            v.verify_server_cert(&junk, &[], &dns("localhost"), &[], valid_time())
                .is_err()
        );
    }

    /// Asserts that supported signature verification schemes match crypto provider capabilities.
    #[test]
    fn chain_only_has_the_provider_schemes() {
        let p = provider();
        let v = ChainOnlyVerifier::new(roots("ca.pem"), Arc::clone(&p));
        assert_eq!(
            v.supported_verify_schemes(),
            p.signature_verification_algorithms.supported_schemes()
        );
        assert!(!v.supported_verify_schemes().is_empty());
    }

    /// Asserts that WebPKI verifiers strictly enforce hostname and IP subject alternative name
    /// matching.
    #[test]
    fn webpki_checks_the_name() {
        let v = webpki_verifier(roots("ca.pem"), provider()).unwrap();
        check(v.as_ref(), "server.pem", &dns("localhost"), valid_time()).unwrap();
        check(v.as_ref(), "server.pem", &ip([127, 0, 0, 1]), valid_time()).unwrap();
        assert!(matches!(
            check(v.as_ref(), "server.pem", &dns("example.com"), valid_time()),
            Err(Error::InvalidCertificate(
                CertificateError::NotValidForName | CertificateError::NotValidForNameContext { .. }
            ))
        ));
        assert!(check(v.as_ref(), "server.pem", &ip([10, 0, 0, 1]), valid_time()).is_err());
    }

    /// Asserts that WebPKI verifiers reject untrusted authorities and expired validity intervals.
    #[test]
    fn webpki_rejects_another_ca_and_an_expired_leaf() {
        let v = webpki_verifier(roots("wrong-ca.pem"), provider()).unwrap();
        assert!(check(v.as_ref(), "server.pem", &dns("localhost"), valid_time()).is_err());
        let v = webpki_verifier(roots("ca.pem"), provider()).unwrap();
        assert!(check(v.as_ref(), "server.pem", &dns("localhost"), late_time()).is_err());
    }

    /// Asserts that initializing WebPKI verifiers without root anchors produces context
    /// configuration errors.
    #[test]
    fn webpki_without_roots_is_a_context_failure() {
        assert_eq!(
            webpki_verifier(Arc::new(RootCertStore::empty()), provider()).err(),
            Some(TlsSetupError::Resource {
                at: TlsResourceAt::ContextNew
            })
        );
    }
}

// The tests build paths from Unix bytes.
#[cfg(unix)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
mod connector {
    use std::net::SocketAddr;
    use std::os::unix::ffi::OsStrExt;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex};

    use rustls::ClientConfig;
    use zk_proto::compat::TlsVerify;
    use zk_proto::{Endpoint, HostToken};

    use zk_driver::net::tls::connector::*;
    use zk_driver::net::tls::verify::webpki_verifier;
    use zk_driver::net::tls::{
        TlsConfigStep, TlsConn, TlsConnector, TlsPurpose, TlsResourceAt, TlsSetupError,
    };
    use zk_driver::sync::Unlocked;

    use crate::tls_util as testing;
    use crate::tls_util::{TLS13, exchange, fixture, server};

    /// RAII temporary file helper unlinked upon drop.
    struct Temp(PathBuf);

    impl Temp {
        fn new(body: &[u8]) -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let n = NEXT.fetch_add(1, Ordering::Relaxed);
            let p =
                std::env::temp_dir().join(format!("zk-driver-tls-{}-{n}.pem", std::process::id()));
            std::fs::write(&p, body).unwrap();
            Self(p)
        }

        fn path(&self) -> LegacyPath {
            LegacyPath::new(self.0.as_os_str().as_bytes().to_vec())
        }
    }

    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn path(name: &str) -> LegacyPath {
        LegacyPath::new(fixture(name).as_os_str().as_bytes().to_vec())
    }

    fn args(ca: Option<LegacyPath>) -> LegacyTlsArgs {
        LegacyTlsArgs {
            ca,
            cert: None,
            key: None,
            passwd: None,
            env_allowed: false,
            verify: TlsVerify::LegacyChainOnly,
        }
    }

    fn endpoint(name: &[u8]) -> Endpoint {
        Endpoint {
            addr: SocketAddr::from(([127, 0, 0, 1], 2281)),
            host: HostToken {
                index: 0,
                name: name.to_vec(),
            },
        }
    }

    fn new_conn(c: &RustlsConnector, name: &[u8]) -> Result<Box<dyn TlsConn>, TlsSetupError> {
        c.new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &endpoint(name))
    }

    const CA_FILE: TlsSetupError = TlsSetupError::Config {
        step: TlsConfigStep::CaFile,
    };

    /// Mock native configuration provider tracking invocation purposes.
    struct Native {
        cfg: Arc<ClientConfig>,
        seen: Mutex<Vec<TlsPurpose>>,
    }

    impl NativeConfig for Native {
        fn client_config(
            &self,
            _proof: &Unlocked,
            purpose: TlsPurpose,
        ) -> Result<Arc<ClientConfig>, TlsSetupError> {
            self.seen.lock().unwrap().push(purpose);
            Ok(Arc::clone(&self.cfg))
        }
    }

    fn native() -> (RustlsConnector, Arc<Native>) {
        let p = default_provider();
        let verifier = webpki_verifier(testing::roots("ca.pem"), Arc::clone(&p)).unwrap();
        let cfg = ClientConfig::builder_with_provider(Arc::clone(&p))
            .with_safe_default_protocol_versions()
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        let src = Arc::new(Native {
            cfg: Arc::new(cfg),
            seen: Mutex::new(Vec::new()),
        });
        let dyn_src: Arc<dyn NativeConfig> = Arc::clone(&src) as Arc<dyn NativeConfig>;
        (RustlsConnector::native(dyn_src, p), src)
    }

    /// Asserts that legacy connectors re-read CA trust store files upon each connection
    /// initialization.
    #[test]
    fn ca_file_is_read_again_by_each_new_conn() {
        let t = Temp::new(&std::fs::read(fixture("ca.pem")).unwrap());
        let c = RustlsConnector::legacy(args(Some(t.path())), default_provider());
        new_conn(&c, b"127.0.0.1").unwrap();
        new_conn(&c, b"127.0.0.1").unwrap();
        std::fs::write(&t.0, b"junk").unwrap();
        assert_eq!(new_conn(&c, b"127.0.0.1").err(), Some(CA_FILE));
    }

    /// Asserts that legacy connectors omit SNI and bypass namevalidation.
    #[test]
    fn legacy_sends_no_sni_and_ignores_the_name() {
        let c = RustlsConnector::legacy(args(Some(path("ca.pem"))), default_provider());
        let mut tls = new_conn(&c, b"example.com").unwrap();
        let mut s = server(TLS13);
        exchange(tls.as_mut(), &mut s, false).unwrap();
        assert!(!tls.is_handshaking());
        assert_eq!(s.server_name(), None);
    }

    /// Asserts that native connectors transmit SNI for DNS names, omit SNI for IP addresses, and
    /// validate names.
    #[test]
    fn native_sends_sni_and_checks_the_name() {
        let (c, src) = native();
        let mut tls = new_conn(&c, b"localhost").unwrap();
        let mut s = server(TLS13);
        exchange(tls.as_mut(), &mut s, false).unwrap();
        assert_eq!(s.server_name(), Some("localhost"));

        let mut tls = new_conn(&c, b"example.com").unwrap();
        let mut s = server(TLS13);
        assert_eq!(
            exchange(tls.as_mut(), &mut s, false),
            Err(zk_driver::net::tls::TlsFailure::Handshake)
        );

        // IP address tokens suppress SNI extension emission.
        let mut tls = new_conn(&c, b"127.0.0.1").unwrap();
        let mut s = server(TLS13);
        exchange(tls.as_mut(), &mut s, false).unwrap();
        assert_eq!(s.server_name(), None);

        let probe = c
            .new_conn(
                &Unlocked::new_at_entry(),
                TlsPurpose::Probe,
                &endpoint(b"localhost"),
            )
            .unwrap();
        assert!(probe.is_handshaking());
        assert_eq!(
            *src.seen.lock().unwrap(),
            [TlsPurpose::Main; 3]
                .into_iter()
                .chain([TlsPurpose::Probe])
                .collect::<Vec<_>>()
        );
    }

    /// Asserts that native connector hostname token parsing rejects malformed or unresolvable
    /// identifiers.
    #[test]
    fn native_names_are_checked() {
        let (c, _) = native();
        new_conn(&c, b"[::1]").unwrap();
        new_conn(&c, b"::1").unwrap();
        new_conn(&c, b"zk-1.example.com").unwrap();
        let bad = Some(TlsSetupError::Resource {
            at: TlsResourceAt::ConnectionNew,
        });
        assert_eq!(new_conn(&c, b"\xff\xfe").err(), bad);
        assert_eq!(new_conn(&c, b"bad name").err(), bad);
        assert_eq!(new_conn(&c, b"").err(), bad);
    }

    /// Asserts that configuration inspection methods expose the configured raw filesystem path
    /// buffers.
    #[test]
    fn path_for_gives_the_raw_fields() {
        let args = LegacyTlsArgs {
            ca: Some(LegacyPath::new(b"ca.pem".to_vec())),
            cert: None,
            key: Some(LegacyPath::new(b"key.pem".to_vec())),
            passwd: None,
            env_allowed: false,
            verify: TlsVerify::LegacyChainOnly,
        };
        let c = RustlsConnector::legacy(args, default_provider());
        assert_eq!(c.path_for(TlsConfigStep::CaFile), Some(&b"ca.pem"[..]));
        assert_eq!(c.path_for(TlsConfigStep::ClientCertChain), None);
        assert_eq!(c.path_for(TlsConfigStep::ClientKey), Some(&b"key.pem"[..]));
        assert_eq!(c.path_for(TlsConfigStep::CheckPrivateKey), None);
    }
}

#[cfg(feature = "_legacy-keys")]
#[allow(clippy::unwrap_used, clippy::indexing_slicing)]
mod legacy_pem {
    use zk_driver::net::tls::legacy_pem::*;

    /// Asserts case-insensitive parsing of DEK encryption cipher identifiers in OpenSSL headers.
    #[test]
    fn cipher_names_ignore_case() {
        assert_eq!(DekCipher::from_name("des-cbc"), Some(DekCipher::DesCbc));
        assert_eq!(
            DekCipher::from_name("Aes-256-Cbc"),
            Some(DekCipher::Aes256Cbc)
        );
        assert_eq!(DekCipher::from_name("AES256"), None);
        assert_eq!(DekCipher::from_name(""), None);
    }
}
