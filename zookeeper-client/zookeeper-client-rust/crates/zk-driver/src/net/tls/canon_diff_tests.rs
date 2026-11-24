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

//! Differential testing harness comparing `canonical_name` implementations against a reference
//! oracle.
//!
//! Maintains an independent reference implementation of X.509 distinguished name canonicalization
//! to verify byte-for-byte equivalence against the production optimized parser across all inputs:
//! producing identical canonical bytes on valid inputs and consistent `None` rejections on
//! malformed inputs. Test inputs comprise real world certificate names extracted from TLS fixtures,
//! the trust-decision test corpus, synthetically constructed edge case names, and fuzz-mutated
//! inputs.
//!
//! Accesses internal parsing structures of `x509`, executing as a crate-level unit test.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::cast_possible_truncation,
    clippy::large_stack_frames
)]

use std::fs;
use std::path::Path;

use rustls::pki_types::CertificateDer;
use rustls::pki_types::pem::PemObject;

use super::{Mode, Reader, Tlv, canonical_name, is_space, oid_ok, push_tlv};

// ---------------------------------------------------------------------
// Reference implementation of distinguished name canonicalization.
// ---------------------------------------------------------------------

fn ref_canon_text(text: &[u8]) -> Vec<u8> {
    let start = text.iter().position(|b| !is_space(*b));
    let end = text.iter().rposition(|b| !is_space(*b));
    let (Some(start), Some(end)) = (start, end) else {
        return Vec::new();
    };
    let body = text.get(start..=end).unwrap_or(&[]);
    let mut out = Vec::with_capacity(body.len());
    let mut in_space = false;
    for &b in body {
        if is_space(b) {
            if !in_space {
                out.push(b' ');
            }
            in_space = true;
        } else {
            out.push(b.to_ascii_lowercase());
            in_space = false;
        }
    }
    out
}

fn ref_chars_to_utf8(chars: impl Iterator<Item = Option<char>>) -> Option<Vec<u8>> {
    let mut s = String::new();
    for c in chars {
        s.push(c?);
    }
    Some(s.into_bytes())
}

/// Reference string conversion arm yielding normalized UTF-8 bytes for string value variants.
fn ref_string_utf8(tag: u8, c: &[u8]) -> Option<Vec<u8>> {
    Some(match tag {
        0x0c => std::str::from_utf8(c).ok()?.as_bytes().to_vec(),
        0x13 | 0x14 | 0x16 => ref_chars_to_utf8(c.iter().map(|b| Some(char::from(*b))))?,
        0x1c => ref_universal_utf8(c)?,
        0x1e => ref_bmp_utf8(c)?,
        _ => return None,
    })
}

/// Reference decoding arm for ASN.1 UniversalString (UCS-4 big-endian) values.
fn ref_universal_utf8(c: &[u8]) -> Option<Vec<u8>> {
    if c.len() & 3 != 0 {
        return None;
    }
    ref_chars_to_utf8(c.chunks_exact(4).map(|q| {
        let w = <[u8; 4]>::try_from(q).ok()?;
        char::from_u32(u32::from_be_bytes(w))
    }))
}

/// Reference decoding arm for ASN.1 BMPString (UCS-2 big-endian) values.
fn ref_bmp_utf8(c: &[u8]) -> Option<Vec<u8>> {
    if c.len() & 1 != 0 {
        return None;
    }
    ref_chars_to_utf8(c.chunks_exact(2).map(|q| {
        let w = <[u8; 2]>::try_from(q).ok()?;
        char::from_u32(u32::from(u16::from_be_bytes(w)))
    }))
}

fn ref_canon_value(t: &Tlv<'_>) -> Option<Vec<u8>> {
    let c = t.content;
    let mut out = Vec::new();
    let utf8 = match t.tag {
        0x30 => return Some(t.raw.to_vec()),
        0x03 => {
            let (&unused, bits) = c.split_first()?;
            if unused > 7 {
                return None;
            }
            let mut v = Vec::with_capacity(c.len());
            if bits.is_empty() {
                v.push(0);
            } else {
                v.push(unused);
                v.extend_from_slice(bits);
                let keep = 0xffu8.checked_shl(u32::from(unused)).unwrap_or(0);
                if let Some(last) = v.last_mut() {
                    *last &= keep;
                }
            }
            push_tlv(&mut out, 0x03, &v);
            return Some(out);
        }
        0x07 | 0x08 | 0x09 | 0x0b | 0x0d | 0x0e | 0x0f | 0x12 | 0x1d => {
            push_tlv(&mut out, t.tag, c);
            return Some(out);
        }
        tag => ref_string_utf8(tag, c)?,
    };
    push_tlv(&mut out, 0x0c, &ref_canon_text(&utf8));
    Some(out)
}

fn ref_canonical_name(name: &[u8], mode: Mode) -> Option<Vec<u8>> {
    let mut r = Reader::new(name, mode);
    let mut out = Vec::new();
    while !r.is_empty() {
        let set = r.take(0x31)?;
        let mut sr = Reader::new(set, mode);
        let mut entries: Vec<Vec<u8>> = Vec::new();
        while !sr.is_empty() {
            let ava = sr.take(0x30)?;
            let mut ar = Reader::new(ava, mode);
            let oid = ar.take(0x06)?;
            if !oid_ok(oid) {
                return None;
            }
            let value = ar.any()?;
            ar.finish()?;
            let mut body = Vec::new();
            push_tlv(&mut body, 0x06, oid);
            body.extend_from_slice(&ref_canon_value(&value)?);
            let mut entry = Vec::new();
            push_tlv(&mut entry, 0x30, &body);
            entries.push(entry);
        }
        if entries.is_empty() {
            continue;
        }
        entries.sort();
        push_tlv(&mut out, 0x31, &entries.concat());
    }
    Some(out)
}

// ---------------------------------------------------------------------
// Comparison harness and test execution tracking.
// ---------------------------------------------------------------------

/// Tracks input evaluation and acceptance totals across differential test runs.
#[derive(Default)]
struct Counts {
    inputs: usize,
    some: usize,
}

impl Counts {
    /// Executes differential comparison between production and reference parsers across DER and
    /// BER modes.
    fn check(&mut self, name: &[u8], what: &str) {
        for mode in [Mode::Der, Mode::Ber] {
            let want = ref_canonical_name(name, mode);
            let got = canonical_name(name, mode);
            assert_eq!(got, want, "{what}: {mode:?}: {name:02x?}");
            self.inputs += 1;
            self.some += usize::from(got.is_some());
        }
    }
}

fn der(tag: u8, content: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    push_tlv(&mut out, tag, content);
    out
}

/// Standard OID constant 2.5.4.3 (commonName).
const CN: &[u8] = &[0x55, 0x04, 0x03];
/// Standard OID constant 2.5.4.10 (organizationName).
const ORG: &[u8] = &[0x55, 0x04, 0x0a];
/// Standard OID constant 1.2.840.113549.1.9.1 (emailAddress).
const EMAIL: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x01];

fn atv(oid: &[u8], value: &[u8]) -> Vec<u8> {
    der(0x30, &[der(0x06, oid), value.to_vec()].concat())
}

fn rdn(atvs: &[Vec<u8>]) -> Vec<u8> {
    der(0x31, &atvs.concat())
}

/// Constructs an encoded Name RDN sequence from component RDNs.
fn name(rdns: &[Vec<u8>]) -> Vec<u8> {
    rdns.concat()
}

/// Supported and unsupported ASN.1 value tags exercised across synthetic test cases.
const VALUE_TAGS: &[u8] = &[
    0x0c, 0x13, 0x14, 0x16, 0x1c, 0x1e, 0x03, 0x07, 0x08, 0x09, 0x0b, 0x0d, 0x0e, 0x0f, 0x12, 0x1d,
    0x30, 0x31, 0x02, 0x04, 0x05, 0x06, 0x15, 0x19, 0x1a, 0x1b, 0x2c, 0x33, 0x80, 0xa0,
];

/// Generates diverse value payloads representing edge cases, whitespace variants, and Unicode
/// sequences.
fn value_bodies() -> Vec<Vec<u8>> {
    let mut out: Vec<Vec<u8>> = [
        &b""[..],
        b" ",
        b"\t\n\x0b\x0c\r ",
        b"Corpus Canon",
        b"  Corpus   Canon  ",
        b"\tCORPUS\x0b\x0cCanon\r\n",
        b"MiXeD-Case_09 @.",
        b"a",
        b"A ",
        b" A",
        &[0xc3, 0x84, 0x20, 0x20, 0xc3, 0x96],
        &[0xe2, 0x82, 0xac, 0x09, 0x41],
        &[0xf0, 0x9f, 0x98, 0x80],
        &[0xc4, 0x20, 0xe9, 0x41],
        &[0xff, 0xfe],
        &[0xc3],
        &[0xed, 0xa0, 0x80],
        &[0x00, 0x41],
        &[0x00, 0x20, 0x00, 0x41, 0x00, 0x20, 0x00, 0x20, 0x00, 0x62],
        &[0xd8, 0x00],
        &[0x00, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00, 0x20],
        &[0x00, 0x11, 0x00, 0x00],
        &[0x00, 0x00, 0xd8, 0x00],
        &[0x00, 0x00, 0x00],
        &[0x00, 0x00, 0x00, 0x41, 0x00],
        &[0x00],
        &[0x03],
        &[0x07],
        &[0x08, 0xff],
        &[0x03, 0xff],
        &[0x07, 0xff, 0xff],
        &[0x00, 0xff, 0x80],
        &[0x01, 0x81],
        &[0x05, 0x00],
        &[0x30, 0x00],
        &[0x31, 0x03, 0x0c, 0x01, 0x41],
    ]
    .iter()
    .map(|v| v.to_vec())
    .collect();
    out.push(b"Long Value ".repeat(20));
    out.push(b" X".repeat(200));
    // Multi-kilobyte payload exercising long-form DER length headers (skipped under Miri).
    if !cfg!(miri) {
        out.push(vec![b'Z'; 70_000]);
    }
    out
}

/// Assembles synthetic distinguished names covering canonicalization variants and malformed
/// encodings.
fn constructed_names() -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    let bodies = value_bodies();
    for &tag in VALUE_TAGS {
        for body in &bodies {
            let v = der(tag, body);
            out.push(name(&[rdn(&[atv(CN, &v)])]));
            out.push(name(&[
                rdn(&[atv(ORG, &der(0x13, b"Corpus"))]),
                rdn(&[atv(CN, &v), atv(EMAIL, &der(0x16, b"A@B.Test"))]),
            ]));
        }
    }
    let o = atv(ORG, &der(0x0c, b"Corpus"));
    let c = atv(CN, &der(0x13, b"  SERVER  test "));
    let e = atv(EMAIL, &der(0x16, b"Ops@Example.Test"));
    let bits = atv(CN, &der(0x03, &[0x03, 0xff]));
    let seq = atv(CN, &der(0x30, &der(0x0c, b"Inner")));
    let raw = atv(CN, &der(0x04, b"\x01\x02"));
    for set in [
        vec![o.clone(), c.clone()],
        vec![c.clone(), o.clone()],
        vec![c.clone(), c.clone()],
        vec![e.clone(), c.clone(), o.clone()],
        vec![bits.clone(), seq.clone(), raw.clone(), o.clone()],
        vec![raw, seq, bits],
        vec![atv(CN, &der(0x0c, b"B")), atv(CN, &der(0x13, b" b "))],
        vec![atv(CN, &der(0x0c, b"x")), atv(CN, &der(0x1a, b"x"))],
    ] {
        out.push(name(&[rdn(&set)]));
        out.push(name(&[rdn(std::slice::from_ref(&o)), rdn(&set), rdn(&[])]));
    }
    // Edge cases: empty distinguished names, empty attribute sets, and internal empty SET
    // sequences.
    out.push(Vec::new());
    out.push(rdn(&[]));
    out.push(name(&[
        rdn(std::slice::from_ref(&o)),
        rdn(&[]),
        rdn(std::slice::from_ref(&c)),
    ]));
    // Malformed encodings: invalid OIDs, omitted values, trailing bytes, and unexpected tag types.
    for bad_oid in [&[][..], &[0x80, 0x01], &[0x55, 0x84], &[0x2a, 0x80, 0x01]] {
        out.push(name(&[rdn(&[atv(bad_oid, &der(0x0c, b"x"))])]));
    }
    out.push(name(&[rdn(&[der(0x30, &der(0x06, CN))])]));
    out.push(name(&[rdn(&[der(
        0x30,
        &[der(0x06, CN), der(0x0c, b"x"), der(0x0c, b"y")].concat(),
    )])]));
    out.push(name(&[rdn(&[der(
        0x31,
        &[der(0x06, CN), der(0x0c, b"x")].concat(),
    )])]));
    out.push(name(&[der(0x30, &o)]));
    out.push(name(&[rdn(std::slice::from_ref(&o)), vec![0x05, 0x00]]));
    // Large RDNs and deep sequences exercising multi-byte length envelopes.
    let many: Vec<Vec<u8>> = (0..40u8)
        .map(|i| atv(CN, &der(0x13, &[b'A' + i % 26, b' ', b' ', b'z'])))
        .collect();
    out.push(name(&[rdn(&many)]));
    out.push(
        many.iter()
            .flat_map(|a| rdn(std::slice::from_ref(a)))
            .collect(),
    );
    // BER-specific constructs: multi-byte lengths, indefinite length encoding, and high-tag
    // numbers.
    out.push([&[0x31, 0x81, 0x09][..], &atv(CN, &der(0x0c, b"Ab"))].concat());
    out.push(
        [
            &[0x31, 0x80][..],
            &[
                0x30, 0x80, 0x06, 0x03, 0x55, 0x04, 0x03, 0x0c, 0x01, 0x41, 0, 0,
            ],
            &[0, 0],
        ]
        .concat(),
    );
    out.push(
        [
            &[0x31, 0x0c, 0x30, 0x0a][..],
            &[0x06, 0x03, 0x55, 0x04, 0x03],
            &[0x30, 0x80, 0x05, 0x00, 0, 0],
        ]
        .concat(),
    );
    out.push(
        [
            &[0x31, 0x09, 0x30, 0x07][..],
            &[0x06, 0x03, 0x55, 0x04, 0x03, 0x1f, 0x21, 0x00],
        ]
        .concat(),
    );
    out
}

/// Loads all X.509 certificates from PEM files within the specified directory tree.
fn pem_certs(dir: &Path, out: &mut Vec<CertificateDer<'static>>) {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            pem_certs(&path, out);
            continue;
        }
        let Ok(text) = fs::read(&path) else {
            continue;
        };
        out.extend(CertificateDer::pem_slice_iter(&text).map_while(Result::ok));
    }
}

/// Recursively extracts raw sequence payloads from nested ASN.1 structures.
fn sequences<'a>(data: &'a [u8], out: &mut Vec<&'a [u8]>) {
    let mut r = Reader::new(data, Mode::Ber);
    while !r.is_empty() {
        let Some(t) = r.any() else {
            return;
        };
        if t.tag == 0x30 {
            out.push(t.content);
        }
        if t.tag & 0x20 != 0 {
            sequences(t.content, out);
        }
    }
}

/// Deterministic 64-bit XorShift pseudo-random number generator for reproducible fuzz mutation.
struct XorShift(u64);

impl XorShift {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }

    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
}

/// Mutates an input byte slice by altering, inserting, deleting, or truncating byte sequences.
fn mutate(rng: &mut XorShift, seed: &[u8]) -> Vec<u8> {
    const SPECIAL: &[u8] = &[
        0x00, 0x03, 0x0c, 0x13, 0x1c, 0x1e, 0x1f, 0x20, 0x30, 0x31, 0x7f, 0x80, 0x81, 0x82, 0xff,
    ];
    let mut v = seed.to_vec();
    for _ in 0..=rng.below(3) {
        let at = rng.below(v.len().max(1));
        match rng.below(5) {
            0 if !v.is_empty() => v[at] = rng.next() as u8,
            1 if !v.is_empty() => v[at] = SPECIAL[rng.below(SPECIAL.len())],
            2 => v.insert(at.min(v.len()), SPECIAL[rng.below(SPECIAL.len())]),
            3 if !v.is_empty() => {
                v.remove(at);
            }
            _ => v.truncate(at),
        }
    }
    v
}

#[test]
fn canonical_name_matches_reference_on_fixture_names() {
    let mut certs = Vec::new();
    pem_certs(zk_testkit::fixtures::tls_dir(), &mut certs);
    pem_certs(zk_testkit::fixtures::tls_corpus_dir(), &mut certs);
    assert!(certs.len() > 200, "too few certificates: {}", certs.len());
    let mut counts = Counts::default();
    for cert in &certs {
        let mut seqs = Vec::new();
        sequences(cert.as_ref(), &mut seqs);
        for s in seqs {
            counts.check(s, "fixture");
        }
    }
    // Confirm that both subject and issuer names from each certificate decode successfully.
    assert!(
        counts.some >= 2 * certs.len(),
        "{} of {}",
        counts.some,
        counts.inputs
    );
}

#[test]
fn canonical_name_matches_reference_on_constructed_names() {
    let names = constructed_names();
    let mut counts = Counts::default();
    for n in &names {
        counts.check(n, "constructed");
    }
    assert!(
        counts.some > names.len() / 2,
        "{} of {}",
        counts.some,
        counts.inputs
    );
    assert!(counts.some < counts.inputs, "no input fails");
}

#[test]
fn canonical_name_matches_reference_on_mutated_names() {
    let mut seeds: Vec<Vec<u8>> = constructed_names()
        .into_iter()
        .filter(|n| n.len() < 2048)
        .collect();
    let mut certs = Vec::new();
    pem_certs(&zk_testkit::fixtures::tls_dir().join("trust"), &mut certs);
    for cert in &certs {
        let mut seqs = Vec::new();
        sequences(cert.as_ref(), &mut seqs);
        seeds.extend(
            seqs.into_iter()
                .filter(|s| canonical_name(s, Mode::Der).is_some())
                .map(<[u8]>::to_vec),
        );
    }
    let mut rng = XorShift(0x9e37_79b9_7f4a_7c15);
    let mut counts = Counts::default();
    // Reduce iteration count under Miri to maintain reasonable test execution times.
    let rounds = if cfg!(miri) { 3_000 } else { 60_000 };
    for _ in 0..rounds {
        let seed = &seeds[rng.below(seeds.len())];
        counts.check(&mutate(&mut rng, seed), "mutated");
    }
    assert!(
        counts.some > rounds / 60,
        "{} of {}",
        counts.some,
        counts.inputs
    );
}
