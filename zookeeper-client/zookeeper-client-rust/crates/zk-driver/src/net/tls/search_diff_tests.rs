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

//! Differential testing harness for certificate trust list pruning and path search.
//!
//! The production path builder optimizes verification latency by lazily parsing only the
//! trusted certificates whose subject and authority keys can potentially form a valid chain
//! rooted at the leaf certificate. To guarantee that this search space pruning never alters
//! path validation outcomes, this module maintains an exhaustive reference implementation
//! that evaluates every trusted certificate in the store without pruning.
//!
//! For each test vector, both the pruned and unpruned search engines execute in parallel
//! to assert invariant equivalence:
//! - The pruned trust candidate list forms a strict subset of the reference list, preserving
//!   relative evaluation order, role designations, origin tracking, and auxiliary trust bits.
//! - For every certificate eligible to serve as a chain top, both engines identify identical
//!   candidate issuer sets.
//! - Both search implementations arrive at identical cryptographic verification verdicts,
//!   including identical rejection error codes.
//!
//! Test cases are derived from the comprehensive trust-decision corpus and authentic TLS
//! test fixtures. The suite systematically introduces structural perturbations: partial chains,
//! reversed intermediate orderings, leaf or intermediate certificates duplicated in the trust
//! store, extraneous background certificates, and clock evaluations precisely at
//! notBefore/notAfter boundary thresholds.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::BTreeMap;
use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, UnixTime};

use super::{Search, Trusted, V1Reject, verify, verify_v3_path};
use crate::net::tls::connector::{LegacyPath, default_provider};
use crate::net::tls::trust::{Role, TrustSet, TrustedCert};
use crate::net::tls::x509::{AuxTrust, CertVersion, LegacyCert, check_issued};

/// Reference trust list builder: retains all configured trust certificates in load order,
/// preserving distinct DER encodings and tracking origin and rejection state.
fn ref_trust_list(trust: &TrustSet) -> Vec<Trusted<'_>> {
    let mut list: Vec<Trusted<'_>> = Vec::with_capacity(trust.certs().len());
    for input in trust.certs() {
        let cert = LegacyCert::parse(input.der().as_ref()).unwrap();
        let aux_trusts = input.aux().is_some_and(AuxTrust::trusts_server_auth);
        if let Some(first) = list.iter_mut().find(|t| t.cert.der() == cert.der()) {
            if input.role() == Role::Rejected {
                first.role = Role::Rejected;
            } else if input.role() != first.role || aux_trusts != first.aux_trusts {
                first.conflict = true;
            }
            first.origins.push(input.origin());
            continue;
        }
        list.push(Trusted {
            cert,
            role: input.role(),
            aux_trusts,
            origins: vec![input.origin()],
            conflict: false,
        });
    }
    list
}

/// Collects the DER-encoded byte slices of all trust entries eligible as issuers of certificate `x`
/// via `check_issued`.
fn candidates<'s>(list: &'s [Trusted<'_>], x: &LegacyCert<'_>) -> Vec<&'s [u8]> {
    list.iter()
        .filter(|t| check_issued(&t.cert, x))
        .map(|t| t.cert.der())
        .collect()
}

/// Asserts that the pruned candidate list `new` is a faithful sub-sequence of the reference list
/// `old`, verifying that relative ordering, assigned roles, auxiliary flags, and origins match
/// identically.
fn assert_sublist(new: &[Trusted<'_>], old: &[Trusted<'_>], what: &str) {
    let mut rest = old.iter();
    for n in new {
        let o = rest
            .find(|o| o.cert.der() == n.cert.der())
            .unwrap_or_else(|| panic!("{what}: a new entry is not in the old order"));
        assert_eq!(n.role, o.role, "{what}: role");
        assert_eq!(n.aux_trusts, o.aux_trusts, "{what}: aux");
        assert_eq!(n.origins, o.origins, "{what}: origins");
        assert_eq!(n.conflict, o.conflict, "{what}: conflict");
    }
}

/// Computes the expected verification verdict for a leaf certificate under the reference search
/// engine, returning `None` if the leaf is self-signed and ineligible for standard path discovery
/// entry points.
fn ref_entry(
    leaf: &LegacyCert<'_>,
    now: UnixTime,
    search: Result<(), V1Reject>,
) -> Option<Result<(), V1Reject>> {
    if leaf.openssl_self_signed() {
        return None;
    }
    if leaf.version() == CertVersion::V1 && super::has_any_extension(leaf) {
        return Some(Err(V1Reject::CriticalExtension));
    }
    if let Err(e) = super::check_leaf(leaf, now) {
        return Some(Err(e));
    }
    Some(search)
}

/// Execution statistics tracking processed cases, entry counts, accepted chains, and rejection
/// error frequencies.
#[derive(Default, Debug)]
struct Stats {
    cases: usize,
    old_entries: usize,
    new_entries: usize,
    accepted: usize,
    reasons: BTreeMap<String, usize>,
}

/// Executes differential verification between the optimized search builder and reference engine on
/// a single test vector.
fn compare(
    trust: &TrustSet,
    chain: &[CertificateDer<'_>],
    now: UnixTime,
    stats: &mut Stats,
    what: &str,
) {
    let provider = default_provider();
    let algs = provider.signature_verification_algorithms.all;
    let Some((first, rest)) = chain.split_first() else {
        return;
    };
    let Ok(leaf) = LegacyCert::parse(first.as_ref()) else {
        return;
    };
    let sent: Vec<LegacyCert<'_>> = rest
        .iter()
        .filter_map(|c| LegacyCert::parse(c.as_ref()).ok())
        .collect();
    let new = Search::new(&leaf, &sent, trust, now, algs).expect("new list");
    let old = Search {
        leaf: &leaf,
        sent: &sent,
        trust: ref_trust_list(trust),
        store: trust,
        now,
        algs,
    };
    assert_sublist(&new.trust, &old.trust, what);
    // Evaluate issuer candidacy against all potential chain tops: the leaf, peer-supplied
    // intermediates, and trust store roots selected during the discovery phase.
    let tops = std::iter::once(&leaf)
        .chain(sent.iter())
        .chain(new.trust.iter().map(|t| &t.cert));
    for x in tops {
        assert_eq!(
            candidates(&new.trust, x),
            candidates(&old.trust, x),
            "{what}: issuer candidates"
        );
    }
    let got = new.run();
    let want = old.run();
    assert_eq!(got, want, "{what}: run");
    let entry = match leaf.version() {
        CertVersion::V1 => verify(&leaf, &sent, trust, now, algs),
        CertVersion::V3 => verify_v3_path(&leaf, &sent, trust, now, algs),
    };
    if let Some(want) = ref_entry(&leaf, now, want) {
        assert_eq!(entry, want, "{what}: entry point");
    }
    stats.cases += 1;
    stats.old_entries += old.trust.len();
    stats.new_entries += new.trust.len();
    match got {
        Ok(()) => stats.accepted += 1,
        Err(e) => *stats.reasons.entry(format!("{e:?}")).or_default() += 1,
    }
}

/// Extracts all DER-encoded certificates from a PEM file, ignoring non-certificate blocks.
fn certs(path: &Path) -> Vec<CertificateDer<'static>> {
    let text = fs::read(path).unwrap_or_default();
    CertificateDer::pem_slice_iter(&text)
        .map_while(Result::ok)
        .collect()
}

fn at(secs: u64) -> UnixTime {
    UnixTime::since_unix_epoch(Duration::from_secs(secs))
}

/// Generates representative evaluation timestamps for a certificate chain, testing validity
/// boundaries immediately before and after the `notBefore` and `notAfter` thresholds of each
/// constituent certificate.
fn times(base: u64, chain: &[CertificateDer<'_>]) -> Vec<u64> {
    let mut out = vec![base];
    for c in chain {
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

/// Generates permutations of a peer certificate chain, including natural order, reversed
/// intermediates, and truncated chain prefixes to test robust path discovery.
fn chain_variants(chain: &[CertificateDer<'static>]) -> Vec<Vec<CertificateDer<'static>>> {
    let mut out = vec![chain.to_vec()];
    if chain.len() > 2 {
        let mut rev = chain.to_vec();
        rev[1..].reverse();
        out.push(rev);
    }
    for n in 1..chain.len() {
        out.push(chain[..n].to_vec());
    }
    out
}

/// Ingests a collection of certificates into the trust store candidate set.
fn push_all(trust: &mut TrustSet, certs: &[CertificateDer<'static>]) {
    for c in certs {
        if let Ok(t) = TrustedCert::new(c.clone(), None) {
            trust.push(t);
        }
    }
}

/// Aggregates unrelated trust certificates across the test suite to inject synthetic background
/// noise, ensuring candidate pruning operates correctly amidst large trust stores.
fn noise() -> Vec<CertificateDer<'static>> {
    let mut out = Vec::new();
    let dir = zk_testkit::fixtures::tls_dir().join("trust");
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "pem"))
        .collect();
    files.sort();
    for f in files {
        out.extend(certs(&f));
    }
    let mut corpus: Vec<CertificateDer<'static>> = Vec::new();
    for case in corpus_cases() {
        corpus.extend(certs(&case.dir.join("chain.pem")));
        corpus.extend(certs(&case.dir.join("ca.pem")));
    }
    corpus.sort_by(|a, b| a.as_ref().cmp(b.as_ref()));
    corpus.dedup();
    out.extend(corpus);
    out
}

struct Case {
    name: String,
    dir: PathBuf,
    attime: u64,
}

impl Case {
    /// Instantiates and populates the trust store for this test case, loading CA files and hashed
    /// directory symlinks.
    fn trust(&self) -> TrustSet {
        let mut trust = TrustSet::default();
        let ca = self.dir.join("ca.pem");
        trust
            .load_ca_file(Some(&LegacyPath::new(ca.as_os_str().as_bytes().to_vec())))
            .unwrap_or_else(|e| panic!("{}: CA file: {e:?}", self.name));
        for dir in ["defaults", "defaults2"] {
            let defaults = self.dir.join(dir);
            if defaults.is_dir() {
                trust.load_hash_dir(&defaults);
            }
        }
        trust
    }
}

fn corpus_cases() -> Vec<Case> {
    let mut out = Vec::new();
    for entry in fs::read_dir(zk_testkit::fixtures::tls_corpus_dir()).unwrap() {
        let dir = entry.unwrap().path();
        let Ok(text) = fs::read_to_string(dir.join("verdict.txt")) else {
            continue;
        };
        let attime = text
            .lines()
            .find_map(|l| l.strip_prefix("attime="))
            .and_then(|v| v.parse().ok())
            .unwrap();
        let name = dir.file_name().unwrap().to_string_lossy().into_owned();
        out.push(Case { name, dir, attime });
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    assert!(out.len() > 100, "the corpus is incomplete: {}", out.len());
    out
}

#[test]
#[cfg_attr(
    miri,
    ignore = "the signature checks of the search use ring, which Miri cannot run"
)]
fn search_list_matches_reference_on_corpus() {
    let mut stats = Stats::default();
    for case in corpus_cases() {
        let chain = certs(&case.dir.join("chain.pem"));
        let trust = case.trust();
        for (v, sent) in chain_variants(&chain).iter().enumerate() {
            for now in times(case.attime, &chain) {
                let what = format!("{} variant {v} at {now}", case.name);
                compare(&trust, sent, at(now), &mut stats, &what);
            }
        }
    }
    assert!(stats.cases > 2000, "{stats:?}");
    assert!(stats.accepted > 100, "{stats:?}");
    assert!(stats.reasons.len() > 5, "{stats:?}");
}

#[test]
#[cfg_attr(
    miri,
    ignore = "the signature checks of the search use ring, which Miri cannot run"
)]
fn search_list_matches_reference_with_changed_trust() {
    let noise = noise();
    let mut stats = Stats::default();
    for case in corpus_cases() {
        let chain = certs(&case.dir.join("chain.pem"));
        let base = case.trust();
        // Introduce trust variations: inject the leaf or intermediates directly into the trust
        // store, and surround the legitimate CA certificates with extraneous background noise.
        let mut extra: Vec<(String, TrustSet)> = Vec::new();
        for (i, c) in chain.iter().enumerate() {
            let mut t = base.clone();
            push_all(&mut t, std::slice::from_ref(c));
            extra.push((format!("cert {i} in trust"), t));
        }
        let mut t = TrustSet::default();
        push_all(&mut t, &noise);
        for c in base.certs() {
            t.push(c.clone());
        }
        extra.push(("noise first".to_owned(), t));
        let mut t = base.clone();
        push_all(&mut t, &noise);
        extra.push(("noise last".to_owned(), t));
        for (label, trust) in &extra {
            // Restrict test permutations to the complete chain and standalone leaf to bound
            // execution time.
            for sent in [chain.clone(), chain[..1].to_vec()] {
                let what = format!("{} {label}, {} sent", case.name, sent.len());
                compare(trust, &sent, at(case.attime), &mut stats, &what);
            }
        }
    }
    assert!(stats.cases > 1000, "{stats:?}");
    assert!(stats.old_entries > 10 * stats.new_entries, "{stats:?}");
}

#[test]
#[cfg_attr(
    miri,
    ignore = "the trust load hashes names with ring, which Miri cannot run"
)]
fn search_list_matches_reference_on_fixtures() {
    let tls = zk_testkit::fixtures::tls_dir();
    let trust_dir = tls.join("trust");
    // Assemble diverse trust configurations: individual CA bundles, OpenSSL hash directories, and
    // aggregate stores.
    let mut sets: Vec<(String, TrustSet)> = Vec::new();
    let mut entries: Vec<PathBuf> = fs::read_dir(&trust_dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .chain(std::iter::once(tls.join("ca.pem")))
        .collect();
    entries.sort();
    let mut all = TrustSet::default();
    for path in &entries {
        let mut t = TrustSet::default();
        let label = path.file_name().unwrap().to_string_lossy().into_owned();
        if path.is_dir() {
            t.load_hash_dir(path);
            all.load_hash_dir(path);
        } else if path.extension().is_some_and(|e| e == "pem") {
            let p = LegacyPath::new(path.as_os_str().as_bytes().to_vec());
            if t.load_ca_file(Some(&p)).is_err() {
                continue;
            }
            let _ = all.load_ca_file(Some(&p));
        } else {
            continue;
        }
        sets.push((label, t));
    }
    sets.push(("all".to_owned(), all));
    // Evaluate candidate leaf certificates in isolation and paired with intermediate authorities.
    let inter = certs(&trust_dir.join("inter.pem"));
    let mut leaves = Vec::new();
    for path in &entries {
        leaves.extend(certs(path));
    }
    for name in [
        "server.pem",
        "other-name.pem",
        "wrong-server.pem",
        "expired.pem",
        "client.pem",
        "ka-server.pem",
        "ka-ed-server.pem",
    ] {
        leaves.extend(certs(&tls.join(name)));
    }
    let mut stats = Stats::default();
    for leaf in &leaves {
        let chains = [
            vec![leaf.clone()],
            [vec![leaf.clone()], inter.clone()].concat(),
        ];
        let Ok(parsed) = LegacyCert::parse(leaf.as_ref()) else {
            continue;
        };
        let v = parsed.validity();
        let mid = v.not_before.as_secs() / 2 + v.not_after.as_secs() / 2;
        for (label, trust) in &sets {
            for chain in &chains {
                for now in [mid, v.not_after.as_secs()] {
                    let what = format!("{label}, {} certs at {now}", chain.len());
                    compare(trust, chain, at(now), &mut stats, &what);
                }
            }
        }
    }
    assert!(stats.cases > 1000, "{stats:?}");
    assert!(stats.accepted > 10, "{stats:?}");
}
