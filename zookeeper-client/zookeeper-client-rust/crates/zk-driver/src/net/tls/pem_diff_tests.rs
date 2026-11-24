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

//! Differential testing harness for the strict PEM parser.
//!
//! To prevent regressions and verify that streaming optimizations preserve RFC-compliant
//! semantics, this module maintains an independent reference implementation representing
//! the baseline PEM parser. The baseline reader accumulates unparsed Base64 lines into an
//! intermediate buffer before decoding, whereas the production parser streams and decodes
//! chunks directly to avoid extraneous memory allocations.
//!
//! The differential test suite verifies byte-for-byte equivalence across both engines:
//! identical successfully parsed blocks, exact matching of encapsulation labels, preservation
//! of RFC 1421 headers, payload equivalence, and identical error classification on malformed
//! inputs. Test inputs include authentic TLS certificate and private key fixtures, the
//! trust-decision test corpus, synthetically constructed edge case blocks, and aggressively
//! fuzzed mutations.
//!
//! In addition, the suite enforces cryptographic zeroization invariants by confirming that
//! body destination buffers are preallocated to their exact upper bound capacity, preventing
//! reallocations that could leak sensitive key material into unzeroized heap slack.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::cast_possible_truncation
)]

use std::fs;
use std::path::Path;

use der::zeroize::Zeroizing;
use zk_proto::SecretBytes;

use super::{
    B64, BEGIN, BOM, CHUNK, END, HeaderState, LINE_MAX, Lines, PemBlock, PemError, TAIL,
    b64_capacity, b64_value, header_and_body, parse_headers, read_blocks_partial, sanitize,
    scan_block,
};

// ---------------------------------------------------------------------
// Reference PEM parser implementation for differential comparison.
// ---------------------------------------------------------------------

#[derive(Clone, Copy)]
struct RefLines<'a> {
    data: &'a [u8],
}

impl<'a> RefLines<'a> {
    fn next_chunk(&mut self) -> Option<&'a [u8]> {
        let max = self.data.len().min(CHUNK);
        let window = self.data.get(..max)?;
        let n = window
            .iter()
            .position(|b| *b == b'\n')
            .and_then(|i| i.checked_add(1))
            .unwrap_or(max);
        let chunk = window.get(..n)?;
        self.data = self.data.get(n..)?;
        let eff = match chunk.iter().position(|b| *b == 0) {
            Some(z) => chunk.get(..z)?,
            None => chunk,
        };
        (!eff.is_empty()).then_some(eff)
    }
}

fn ref_find_begin<'a>(lines: &mut RefLines<'a>) -> Option<&'a [u8]> {
    let mut first = true;
    loop {
        let mut raw = lines.next_chunk()?;
        if first && raw.len() > 3 && raw.starts_with(BOM) {
            raw = raw.get(3..)?;
        }
        first = false;
        let s = sanitize(raw);
        if s.starts_with(BEGIN) && s.ends_with(TAIL) {
            let end = s.len().checked_sub(TAIL.len())?;
            return s.get(BEGIN.len()..end);
        }
    }
}

type Text = Zeroizing<Vec<u8>>;

fn ref_header_and_data(lines: &mut RefLines<'_>, label: &[u8]) -> Result<(Text, Text), PemError> {
    let (mut header_len, mut data_len) = (0usize, 0usize);
    let mut probe = *lines;
    ref_scan_block(&mut probe, label, |to_data, s| {
        let slot = if to_data {
            &mut data_len
        } else {
            &mut header_len
        };
        *slot = slot.saturating_add(s.len()).saturating_add(1);
    })?;
    let mut header = Zeroizing::new(Vec::with_capacity(header_len));
    let mut data = Zeroizing::new(Vec::with_capacity(data_len));
    let swap = ref_scan_block(lines, label, |to_data, s| {
        let target = if to_data { &mut data } else { &mut header };
        target.extend_from_slice(s);
        target.push(b'\n');
    })?;
    if swap {
        std::mem::swap(&mut header, &mut data);
    }
    Ok((header, data))
}

fn ref_scan_block(
    lines: &mut RefLines<'_>,
    label: &[u8],
    mut put: impl FnMut(bool, &[u8]),
) -> Result<bool, PemError> {
    let mut to_data = false;
    let mut state = HeaderState::Maybe;
    let mut end = false;
    let mut partial = false;
    loop {
        let raw = lines.next_chunk().ok_or(PemError::Unterminated)?;
        let prev_partial = partial;
        partial = raw.len() == CHUNK && raw.last() != Some(&b'\n');
        if state == HeaderState::Maybe && raw.contains(&b':') {
            state = HeaderState::In;
        }
        let s = sanitize(raw);
        if s.is_empty() {
            if !prev_partial {
                if state == HeaderState::Post {
                    return Err(PemError::Base64);
                }
                state = HeaderState::Post;
                to_data = true;
            }
            continue;
        }
        if let Some(rest) = s.strip_prefix(END) {
            let name_ok = rest.strip_prefix(label).is_some_and(|tail| tail == TAIL);
            if !name_ok {
                return Err(PemError::Unterminated);
            }
            return Ok(state == HeaderState::Maybe);
        }
        if end {
            return Err(PemError::Base64);
        }
        put(to_data, s);
        if state == HeaderState::Post {
            let len = s.len().saturating_add(1);
            if len > LINE_MAX {
                return Err(PemError::Base64);
            }
            if len < LINE_MAX {
                end = true;
            }
        }
    }
}

fn ref_b64_value(b: u8) -> Option<u8> {
    match b {
        b'A'..=b'Z' => b.checked_sub(b'A'),
        b'a'..=b'z' => b.checked_sub(b'a' - 26),
        b'0'..=b'9' => b.checked_add(4),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

fn ref_b64_decode(text: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    let mut out = Zeroizing::new(Vec::with_capacity((text.len() / 4).saturating_mul(3)));
    let mut acc: u32 = 0;
    let mut n: u8 = 0;
    let mut pad: u8 = 0;
    for &b in text {
        let v = match b {
            b' ' | b'\t' | b'\n' | b'\r' => continue,
            b'-' => break,
            b'=' => {
                pad = pad.checked_add(1)?;
                if pad > 2 {
                    return None;
                }
                0
            }
            _ => {
                if pad > 0 {
                    return None;
                }
                ref_b64_value(b)?
            }
        };
        acc = acc.wrapping_shl(6) | u32::from(v);
        n = n.checked_add(1)?;
        if n == 4 {
            let [_, b1, b2, b3] = acc.to_be_bytes();
            out.push(b1);
            if pad < 2 {
                out.push(b2);
            }
            if pad < 1 {
                out.push(b3);
            }
            acc = 0;
            n = 0;
        }
    }
    (n == 0).then_some(out)
}

fn ref_read_body(lines: &mut RefLines<'_>, label: &[u8]) -> Result<PemBlock, PemError> {
    let (header, data) = ref_header_and_data(lines, label)?;
    let mut body = ref_b64_decode(&data).ok_or(PemError::Base64)?;
    if body.is_empty() {
        return Err(PemError::Base64);
    }
    Ok(PemBlock {
        label: String::from_utf8_lossy(label).into_owned(),
        headers: parse_headers(&header),
        body: SecretBytes::new(std::mem::take(&mut *body)),
    })
}

fn ref_read_blocks_partial(text: &[u8]) -> (Vec<PemBlock>, Option<PemError>) {
    let mut lines = RefLines { data: text };
    let mut out = Vec::new();
    while let Some(label) = ref_find_begin(&mut lines) {
        match ref_read_body(&mut lines, label) {
            Ok(block) => out.push(block),
            Err(e) => return (out, Some(e)),
        }
    }
    (out, None)
}

// ---------------------------------------------------------------------
// Test input generators, corpus loaders, and differential verification engine.
// ---------------------------------------------------------------------

/// Deterministic 64-bit XorShift pseudorandom number generator for reproducible fuzzing.
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

/// Structural boundary and delimiter bytes used to guide mutation fuzzing across line breaks,
/// NUL terminators, header colons, padding characters, encapsulation boundaries, and UTF-8 BOMs.
const SPECIAL: &[u8] = b"\n\n\n\r\0:=-- \t\x0bAQz09+/\xc3\x80\xef\xbb\xbf";

/// Recursively collects all PEM-formatted files within a directory hierarchy in sorted
/// lexicographical order.
fn files(dir: &Path, out: &mut Vec<Vec<u8>>) {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            files(&path, out);
        } else if let Ok(text) = fs::read(&path) {
            if text.windows(BEGIN.len()).any(|w| w == BEGIN) {
                out.push(text);
            }
        }
    }
}

/// Loads all PEM test fixtures and trust-decision corpus files for differential testing.
fn fixture_files() -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    files(zk_testkit::fixtures::tls_dir(), &mut out);
    files(zk_testkit::fixtures::tls_corpus_dir(), &mut out);
    assert!(out.len() > 20, "too few PEM fixtures: {}", out.len());
    out
}

fn block(label: &str, inner: &[u8]) -> Vec<u8> {
    [
        format!("-----BEGIN {label}-----\n").as_bytes(),
        inner,
        format!("-----END {label}-----\n").as_bytes(),
    ]
    .concat()
}

/// Constructs synthetic PEM blocks exercising parser edge cases, including encapsulation
/// headers, continuation lines, embedded NULs, boundary line lengths, padding variations,
/// mismatched boundary tags, UTF-8 byte-order marks, and CRLF line terminations.
fn constructed() -> Vec<Vec<u8>> {
    let bodies: &[&[u8]] = &[
        b"QUJD\n",
        b"Proc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n\nQUJD\n",
        b"QUI=\nQUJD\n",
        b"QU\nJD\n",
        b"a:b\nQUJD\n",
        b"\nQUJD\n",
        b"QUJD\n\n",
        b"QQ==\n",
        b"Q\0UJD\n",
        b"QUJD-x\n",
        b"QU JD\n",
        b" \nQUJD\n",
        b"Comment: a\n b\n\nQUJD\n",
        b"Comment: a\n\nQUJD\nQUJD\n",
        b"Comment: a\n\n\nQUJD\n",
        b"QUJD\r\nQUJD\r\n",
        b"QUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJD\nQUJD\n",
        b"\nQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJD\nQUJD\n",
        b"\nQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQUJDQ\nQUJD\n",
        b"\nQUJD\nQUJD\n",
        b"QUJD:\n",
        b"QU:JD\n",
        b"QU\0:JD\n",
        b"=\n",
        b"QQ==QUJD\n",
        b"Q===\n",
        b"QUJ\n",
        b"",
        b"\n",
        b"QUJD\x80\n",
        b"QUJD\xc3\xa9\n",
        b"QUJD \t\x0b\n",
        b"-----\n",
    ];
    let mut out: Vec<Vec<u8>> = bodies.iter().map(|b| block("X", b)).collect();
    out.push(block("CERTIFICATE", b"QUJD\n"));
    out.push([b"\xef\xbb\xbf".as_slice(), &block("X", b"QUJD\n")].concat());
    out.push([b"\xef\xbb\xbf\n".as_slice(), &block("X", b"QUJD\n")].concat());
    out.push(b"-----BEGIN X-----\nQUJD\n-----END Y-----\n".to_vec());
    out.push(b"-----BEGIN X-----\nQUJD\n-----END X----- \n".to_vec());
    out.push(b"-----BEGIN X-----\nQUJD\n".to_vec());
    out.push(b"-----BEGIN X-----\nQUJD\n-----END X-----".to_vec());
    out.push(b"text\n-----BEGIN X-----\r\nQUJD\r\n-----END X-----\r\ntext\n".to_vec());
    out.push([block("X", b"QUJD\n"), b"\0".to_vec(), block("X", b"QUJD\n")].concat());
    out.push([block("X", b"QUJD\n"), block("X", b"QU*D\n")].concat());
    out.push([block("X", b"QUJD\n"), block("X", b"QUJD\n")].concat());
    // Generate test lines around the 254-byte streaming chunk boundary to test buffer split
    // handling with embedded colons, spaces, padding characters, and NUL terminators.
    for len in [7, 8, 9, 63, 64, 65, 252, 253, 254, 255, 256, 300, 508, 509] {
        let line = vec![b'Q'; len];
        out.push(block("X", &[line.as_slice(), b"\n"].concat()));
        out.push(block("X", &[b"\n".as_slice(), &line, b"\n"].concat()));
        for at in [0, 1, 7, 8, len / 2, len - 1]
            .into_iter()
            .filter(|at| *at < len)
        {
            for b in [b':', 0, b' ', b'='] {
                let mut l = line.clone();
                l[at] = b;
                out.push(block("X", &[l.as_slice(), b"\nQUJD\n"].concat()));
            }
        }
    }
    out
}

/// Applies randomized byte mutations (in-place replacement, structural byte insertion, byte
/// deletion, or truncation) to an input seed to test parser resilience against malformed
/// structures.
fn mutate(rng: &mut XorShift, seed: &[u8]) -> Vec<u8> {
    let mut v = seed.to_vec();
    for _ in 0..=rng.below(3) {
        let at = rng.below(v.len().max(1));
        match rng.below(6) {
            0 if !v.is_empty() => v[at] = rng.next() as u8,
            1 if !v.is_empty() => v[at] = SPECIAL[rng.below(SPECIAL.len())],
            2 => v.insert(at.min(v.len()), SPECIAL[rng.below(SPECIAL.len())]),
            3 if !v.is_empty() => {
                v.remove(at);
            }
            4 => v.truncate(at),
            _ => {
                let n = rng.below(5) + 1;
                for _ in 0..n {
                    v.insert(at.min(v.len()), SPECIAL[rng.below(SPECIAL.len())]);
                }
            }
        }
    }
    v
}

/// Differential testing statistics tracker recording processed inputs and matching error outcomes.
#[derive(Default)]
struct Diff {
    inputs: usize,
    errors: usize,
}

impl Diff {
    fn check(&mut self, text: &[u8]) {
        let (want, want_err) = ref_read_blocks_partial(text);
        let (got, got_err) = read_blocks_partial(text);
        assert_eq!(got_err, want_err, "error of input {}", self.inputs);
        assert_eq!(
            got.len(),
            want.len(),
            "block count of input {}",
            self.inputs
        );
        for (g, w) in got.iter().zip(&want) {
            assert_eq!(g.label, w.label, "label of input {}", self.inputs);
            assert_eq!(g.headers, w.headers, "headers of input {}", self.inputs);
            assert_eq!(
                g.body.expose(),
                w.body.expose(),
                "body of input {}",
                self.inputs
            );
        }
        self.inputs += 1;
        if want_err.is_some() {
            self.errors += 1;
        }
    }
}

// ---------------------------------------------------------------------
// Differential test execution cases.
// ---------------------------------------------------------------------

#[test]
fn digit_table_matches_reference() {
    for b in 0..=u8::MAX {
        assert_eq!(b64_value(b), ref_b64_value(b), "byte {b:#04x}");
    }
}

#[test]
fn chunks_match_reference() {
    let mut rng = XorShift(0x9e37_79b9_7f4a_7c15);
    // Reduce mutation iterations under Miri emulation to keep execution within reasonable time
    // bounds.
    let rounds = if cfg!(miri) { 200 } else { 4000 };
    for round in 0..rounds {
        let len = rng.below(700);
        let text: Vec<u8> = (0..len)
            .map(|_| {
                if rng.below(4) == 0 {
                    SPECIAL[rng.below(SPECIAL.len())]
                } else {
                    b'A' + rng.below(26) as u8
                }
            })
            .collect();
        let mut got = Lines { data: &text };
        let mut want = RefLines { data: &text };
        loop {
            let w = want.next_chunk();
            let g = got.next_chunk_colon();
            assert_eq!(g.map(|(c, _)| c), w, "chunk of round {round}");
            assert_eq!(got.data, want.data, "rest of round {round}");
            let Some(w) = w else { break };
            assert_eq!(
                g.map(|(_, c)| c),
                Some(w.contains(&b':')),
                "colon of round {round}"
            );
        }
    }
}

#[test]
fn b64_parts_match_reference() {
    const TEXT: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut rng = XorShift(0x2545_f491_4f6c_dd1d);
    // Reduce mutation iterations under Miri emulation to keep execution within reasonable time
    // bounds.
    let rounds = if cfg!(miri) { 1000 } else { 20000 };
    for round in 0..rounds {
        let len = rng.below(80);
        let text: Vec<u8> = (0..len)
            .map(|_| match rng.below(8) {
                0 => SPECIAL[rng.below(SPECIAL.len())],
                _ => TEXT[rng.below(TEXT.len())],
            })
            .collect();
        let want = ref_b64_decode(&text);
        let cap = b64_capacity(text.len());
        let mut out = Zeroizing::new(Vec::with_capacity(cap));
        let cap = out.capacity();
        let mut b64 = B64::default();
        let mut rest = text.as_slice();
        while !rest.is_empty() {
            let (part, tail) = rest.split_at(rng.below(rest.len()) + 1);
            b64.feed(&mut out, part);
            rest = tail;
        }
        let got = b64.finish().then_some(out);
        assert_eq!(
            got.as_deref().map(Vec::as_slice),
            want.as_deref().map(Vec::as_slice),
            "round {round}: {:?}",
            String::from_utf8_lossy(&text)
        );
        if let Some(out) = got {
            assert_eq!(out.capacity(), cap, "round {round}: the body grew");
        }
    }
}

#[test]
#[cfg_attr(
    miri,
    ignore = "Miri is too slow to scan each PEM file of the fixtures and the corpus"
)]
fn read_blocks_matches_reference() {
    let fixtures = fixture_files();
    let constructed = constructed();
    let mut diff = Diff::default();
    for text in fixtures.iter().chain(&constructed) {
        diff.check(text);
    }
    // Concatenate multiple PEM blocks into a single stream to verify multi-certificate bundle
    // handling.
    diff.check(&fixtures.concat());
    let mut rng = XorShift(0x5851_f42d_4c95_7f2d);
    let seeds: Vec<&Vec<u8>> = fixtures.iter().chain(&constructed).collect();
    for round in 0..20000 {
        let seed = seeds[rng.below(seeds.len())];
        // Target a localized window within large PEM files during mutation rounds to ensure fast
        // iteration.
        let seed = if seed.len() > 6000 && round % 2 == 0 {
            let start = rng.below(seed.len() - 4000);
            &seed[start..start + 4000]
        } else {
            seed.as_slice()
        };
        diff.check(&mutate(&mut rng, seed));
    }
    assert!(diff.inputs >= 20020, "inputs {}", diff.inputs);
    // Ensure statistical significance across both valid acceptance and rejected error validation
    // paths.
    assert!(
        diff.errors > 2000,
        "errors {} of {}",
        diff.errors,
        diff.inputs
    );
    assert!(
        diff.inputs - diff.errors > 2000,
        "accepts of {}",
        diff.inputs
    );
}

/// Enforces heap allocation safety: verifies that decoded payload buffers are preallocated with
/// their exact capacity before Base64 decoding, preventing reallocations that could abandon
/// sensitive key material in unzeroized heap memory.
#[test]
#[cfg_attr(
    miri,
    ignore = "Miri is too slow to scan each PEM file of the fixtures and the corpus"
)]
fn body_has_final_capacity() {
    let mut texts = fixture_files();
    texts.extend(constructed());
    let mut blocks = 0usize;
    for text in &texts {
        let mut lines = Lines { data: text };
        let mut want = RefLines { data: text };
        while let Some(label) = super::find_begin(&mut lines) {
            let want_label = ref_find_begin(&mut want);
            assert_eq!(Some(label), want_label);
            let (mut header_len, mut data_len) = (0usize, 0usize);
            let mut probe = lines;
            let swap = scan_block(&mut probe, label, |to_data, s| {
                let slot = if to_data {
                    &mut data_len
                } else {
                    &mut header_len
                };
                *slot += s.len() + 1;
            });
            let got = header_and_body(&mut lines, label);
            let ref_got = ref_header_and_data(&mut want, label);
            let (Ok(swap), Ok((header, body)), Ok((ref_header, ref_data))) = (swap, got, ref_got)
            else {
                break;
            };
            if swap {
                std::mem::swap(&mut header_len, &mut data_len);
            }
            assert_eq!(header.as_slice(), ref_header.as_slice());
            assert_eq!(header.capacity(), header_len);
            let ref_body = ref_b64_decode(&ref_data);
            assert_eq!(
                body.as_deref().map(Vec::as_slice),
                ref_body.as_deref().map(Vec::as_slice)
            );
            if let Some(body) = body {
                let cap = Vec::<u8>::with_capacity(b64_capacity(data_len)).capacity();
                assert_eq!(body.capacity(), cap, "the body grew");
                assert!(body.len() <= cap);
                blocks += 1;
            }
        }
    }
    assert!(blocks > 50, "blocks {blocks}");
}
