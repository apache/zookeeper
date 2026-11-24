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

//! Differential evaluation of the WebPKI trust anchor filtering pipeline.
//!
//! `TrustSet::root_store_at` preserves anchor validity determinations from initial
//! parse time to avoid redundant ASN.1 re-parsing across handshake cycles. This
//! harness compares `TrustSet::root_store_at` against an authoritative reference
//! implementation that parses every candidate anchor on each call. Both approaches
//! must yield identical root certificate stores across arbitrary validity epochs.
//!
//! In addition to root set equivalence, the test exercises the deterministic
//! WebPKI server certificate verifier against matching and non-matching SANs,
//! IP addresses, wildcards, and expired boundaries to verify end-to-end policy parity.

#![cfg(all(unix, feature = "tls"))]
#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use rustls::RootCertStore;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zk_driver::net::tls::trust::{TrustSet, TrustedCert};
use zk_driver::net::tls::verify::webpki_verifier;
use zk_driver::net::tls::x509::LegacyCert;
use zk_driver::net::tls::{LegacyPath, default_provider};

/// Reference implementation re-parsing DER anchors on each validity check.
fn ref_root_store_at(trust: &TrustSet, now: UnixTime) -> RootCertStore {
    let mut store = RootCertStore::empty();
    for anchor in trust.anchor_candidates() {
        if LegacyCert::parse(anchor.der().as_ref()).is_ok_and(|c| c.valid_at(now)) {
            let _ = store.add(anchor.der().clone());
        }
    }
    store
}

/// Target hostnames spanning loopback, wildcards, IP literals, and invalid domains.
const HOSTS: &[&str] = &[
    "localhost",
    "LocalHost",
    "x.localhost",
    "127.0.0.1",
    "127.0.0.2",
    "::1",
    "other.invalid",
    "wrong.invalid",
    "server.test",
    "other.test",
    "dn.server.test",
    "www.ok.test",
    "a.www.ok.test",
    "x.allowed.test",
    "192.0.2.1",
    "198.51.100.7",
];

fn certs(path: &Path) -> Vec<CertificateDer<'static>> {
    let text = fs::read(path).unwrap_or_default();
    CertificateDer::pem_slice_iter(&text)
        .map_while(Result::ok)
        .collect()
}

fn at(secs: u64) -> UnixTime {
    UnixTime::since_unix_epoch(Duration::from_secs(secs))
}

/// Collects critical evaluation timestamps spanning validity boundary transitions.
fn times(base: u64, certs: &[CertificateDer<'_>]) -> Vec<u64> {
    let mut out = vec![base];
    for c in certs {
        let Ok(c) = LegacyCert::parse(c.as_ref()) else {
            continue;
        };
        let v = c.validity();
        let (from, to) = (v.not_before.as_secs(), v.not_after.as_secs());
        out.extend([from.saturating_sub(1), from, to.saturating_sub(1), to]);
    }
    out.sort_unstable();
    out.dedup();
    out
}

#[derive(Default, Debug)]
struct Stats {
    stores: usize,
    roots: usize,
    verifies: usize,
    accepted: usize,
}

/// Executes differential comparison between cached and re-parsed root stores across target epochs
/// and hostnames.
fn compare(
    trust: &TrustSet,
    chain: &[CertificateDer<'static>],
    now: &[u64],
    verify_at: u64,
    stats: &mut Stats,
    what: &str,
) {
    for &t in now {
        let got = trust.root_store_at(at(t));
        let want = ref_root_store_at(trust, at(t));
        assert_eq!(got.roots, want.roots, "{what}: roots at {t}");
        stats.stores += 1;
        stats.roots += got.roots.len();
    }
    let Some((leaf, rest)) = chain.split_first() else {
        return;
    };
    let provider = default_provider();
    let new = webpki_verifier(
        Arc::new(trust.root_store_at(at(verify_at))),
        provider.clone(),
    );
    let old = webpki_verifier(Arc::new(ref_root_store_at(trust, at(verify_at))), provider);
    let (Ok(new), Ok(old)) = (new, old) else {
        // Empty root trust stores produce verifier initialization failures across both
        // implementations.
        assert!(trust.root_store_at(at(verify_at)).is_empty(), "{what}");
        return;
    };
    for host in HOSTS {
        let name = ServerName::try_from(*host).unwrap();
        let got = new.verify_server_cert(leaf, rest, &name, &[], at(verify_at));
        let want = old.verify_server_cert(leaf, rest, &name, &[], at(verify_at));
        // Rustls `Error::Other` variants lack structural equality; compare debug representations.
        assert_eq!(format!("{got:?}"), format!("{want:?}"), "{what}: {host}");
        stats.verifies += 1;
        stats.accepted += usize::from(got.is_ok());
    }
}

fn load(path: &Path) -> Option<TrustSet> {
    let mut trust = TrustSet::default();
    if path.is_dir() {
        trust.load_hash_dir(path);
    } else {
        let p = LegacyPath::new(path.as_os_str().as_bytes().to_vec());
        trust.load_ca_file(Some(&p)).ok()?;
    }
    Some(trust)
}

#[test]
fn root_store_matches_reference_on_fixtures() {
    let tls = zk_testkit::fixtures::tls_dir();
    let mut paths: Vec<PathBuf> = fs::read_dir(tls.join("trust"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.is_dir() || p.extension().is_some_and(|e| e == "pem"))
        .collect();
    paths.push(tls.join("ca.pem"));
    paths.push(tls.join("wrong-ca.pem"));
    paths.sort();
    let leaves = [
        "server.pem",
        "other-name.pem",
        "wrong-server.pem",
        "expired.pem",
        "ka-server.pem",
        "ka-ed-server.pem",
        "client.pem",
    ];
    let mut stats = Stats::default();
    for path in &paths {
        let Some(mut trust) = load(path) else {
            continue;
        };
        // Inject expired anchors and non-CA leaf certificates into the candidate trust set.
        for extra in ["expired.pem", "server.pem"] {
            for c in certs(&tls.join(extra)) {
                trust.push(TrustedCert::new(c, None).unwrap());
            }
        }
        let anchors: Vec<CertificateDer<'static>> = trust
            .anchor_candidates()
            .iter()
            .map(|a| a.der().clone())
            .collect();
        let now = times(UnixTime::now().as_secs(), &anchors);
        for leaf in leaves {
            let chain = certs(&tls.join(leaf));
            let v = LegacyCert::parse(chain[0].as_ref()).unwrap().validity();
            let mid = v.not_before.as_secs() / 2 + v.not_after.as_secs() / 2;
            let what = format!("{}: {leaf}", path.display());
            compare(&trust, &chain, &now, mid, &mut stats, &what);
        }
    }
    assert!(stats.roots > 100, "{stats:?}");
    assert!(stats.accepted > 10, "{stats:?}");
}

#[test]
fn root_store_matches_reference_on_corpus() {
    let mut dirs: Vec<PathBuf> = fs::read_dir(zk_testkit::fixtures::tls_corpus_dir())
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|d| d.join("verdict.txt").is_file())
        .collect();
    dirs.sort();
    assert!(dirs.len() > 100, "the corpus is incomplete: {}", dirs.len());
    let mut stats = Stats::default();
    for dir in &dirs {
        let mut trust = load(&dir.join("ca.pem")).unwrap();
        for d in ["defaults", "defaults2"] {
            if dir.join(d).is_dir() {
                trust.load_hash_dir(&dir.join(d));
            }
        }
        let text = fs::read_to_string(dir.join("verdict.txt")).unwrap();
        let attime: u64 = text
            .lines()
            .find_map(|l| l.strip_prefix("attime="))
            .and_then(|v| v.parse().ok())
            .unwrap();
        let chain = certs(&dir.join("chain.pem"));
        let mut all: Vec<CertificateDer<'static>> = trust
            .anchor_candidates()
            .iter()
            .map(|a| a.der().clone())
            .collect();
        all.extend(chain.iter().cloned());
        let now = times(attime, &all);
        compare(
            &trust,
            &chain,
            &now,
            attime,
            &mut stats,
            &dir.display().to_string(),
        );
    }
    assert!(stats.roots > 100, "{stats:?}");
    assert!(stats.accepted > 10, "{stats:?}");
}
