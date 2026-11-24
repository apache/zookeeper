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

//! Session authentication credentials, principal metadata, and digest generation utilities.
//!
//! Provides [`ClientInfo`] representation for active session authorization identities along with
//! embedded SHA-1 (FIPS 180-4) and Base64 (RFC 4648) implementations. These cryptographic
//! helpers compute canonical ZooKeeper digest authentication credentials without pulling external
//! cryptographic dependencies into non-TLS client configurations.

use zeroize::{Zeroize, Zeroizing};

/// Authenticated principal identity associated with an active session, as returned by `who_am_i`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClientInfo {
    /// Authentication scheme name (such as `"ip"`, `"digest"`, `"sasl"`, or `"x509"`).
    pub auth_scheme: String,
    /// Principal identifier string recognized within the scheme.
    pub user: String,
}

impl ClientInfo {
    /// Converts a wire Jute `ClientInfo` record into domain model representation.
    ///
    /// Applies lossy UTF-8 decoding to tolerate non-compliant strings received from Java ensemble
    /// servers.
    pub(crate) fn from_jute(c: &zk_jute::data::ClientInfo) -> Self {
        Self {
            auth_scheme: lossy(&c.auth_scheme),
            user: lossy(&c.user),
        }
    }
}

/// Converts an optional wire string into an owned UTF-8 string, treating null values as empty.
pub(crate) fn lossy(s: &zk_jute::JString) -> String {
    s.as_bytes()
        .map(|b| String::from_utf8_lossy(b).into_owned())
        .unwrap_or_default()
}

/// Formats a username and password into a canonical ZooKeeper digest identity.
///
/// Invariant: Conforms to Java `DigestAuthenticationProvider.generateDigest`. If `user` contains a
/// colon, the prefix prior to the first colon serves as the output username prefix while the
/// entire concatenated string is hashed. Intermediate scratch memory and formatted credentials are
/// securely zeroized upon exit.
pub(crate) fn digest_id(user: &str, password: &str) -> String {
    let mut credentials = Zeroizing::new(String::with_capacity(
        user.len().saturating_add(password.len()).saturating_add(1),
    ));
    credentials.push_str(user);
    credentials.push(':');
    credentials.push_str(password);
    let name = credentials.split(':').next().unwrap_or_default();
    format!("{name}:{}", base64(&sha1(credentials.as_bytes())))
}

/// Computes the 160-bit SHA-1 cryptographic hash of `data` conforming to FIPS 180-4.
fn sha1(data: &[u8]) -> [u8; 20] {
    let mut state: [u32; 5] = [
        0x6745_2301,
        0xefcd_ab89,
        0x98ba_dcfe,
        0x1032_5476,
        0xc3d2_e1f0,
    ];
    // Compute total bit length modulo 2^64 as required by FIPS 180-4 section 5.1.1.
    let bits = u64::try_from(data.len())
        .unwrap_or(u64::MAX)
        .wrapping_mul(8);
    let mut blocks = data.chunks_exact(64);
    for block in blocks.by_ref() {
        sha1_block(&mut state, block);
    }
    // Standard Merkle-Damgard padding: append 0x80, zero padding, and 64-bit big-endian length.
    let tail = blocks.remainder();
    let mut pad = [0u8; 128];
    let used = tail.len();
    if let Some(dst) = pad.get_mut(..used) {
        dst.copy_from_slice(tail);
    }
    if let Some(b) = pad.get_mut(used) {
        *b = 0x80;
    }
    let total: usize = if used < 56 { 64 } else { 128 };
    if let Some(dst) = pad.get_mut(total.saturating_sub(8)..total) {
        dst.copy_from_slice(&bits.to_be_bytes());
    }
    for block in pad.get(..total).unwrap_or_default().chunks_exact(64) {
        sha1_block(&mut state, block);
    }
    pad.zeroize();
    let mut out = [0u8; 20];
    for (dst, word) in out.chunks_exact_mut(4).zip(state) {
        dst.copy_from_slice(&word.to_be_bytes());
    }
    out
}

/// Applies the SHA-1 block transformation across a 512-bit message chunk (FIPS 180-4 ch 6.1.2).
fn sha1_block(state: &mut [u32; 5], block: &[u8]) {
    let mut w = [0u32; 80];
    for (dst, word) in w.iter_mut().zip(block.chunks_exact(4)) {
        let mut be = [0u8; 4];
        be.copy_from_slice(word);
        *dst = u32::from_be_bytes(be);
        be.zeroize();
    }
    for t in 16..80usize {
        let at = |back: usize| w.get(t.wrapping_sub(back)).copied().unwrap_or(0);
        let v = (at(3) ^ at(8) ^ at(14) ^ at(16)).rotate_left(1);
        if let Some(dst) = w.get_mut(t) {
            *dst = v;
        }
    }
    let [mut a, mut b, mut c, mut d, mut e] = *state;
    for (t, word) in w.iter().enumerate() {
        let (f, k) = match t {
            0..=19 => ((b & c) | (!b & d), 0x5a82_7999),
            20..=39 => (b ^ c ^ d, 0x6ed9_eba1),
            40..=59 => ((b & c) | (b & d) | (c & d), 0x8f1b_bcdc),
            _ => (b ^ c ^ d, 0xca62_c1d6),
        };
        let temp = a
            .rotate_left(5)
            .wrapping_add(f)
            .wrapping_add(e)
            .wrapping_add(k)
            .wrapping_add(*word);
        e = d;
        d = c;
        c = b.rotate_left(30);
        b = a;
        a = temp;
    }
    for (dst, v) in state.iter_mut().zip([a, b, c, d, e]) {
        *dst = dst.wrapping_add(v);
    }
    w.zeroize();
}

/// Encodes raw binary data into standard Base64 with padding conforming to RFC 4648 section 4.
fn base64(data: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let symbol = |six: u8| -> char {
        char::from(
            ALPHABET
                .get(usize::from(six & 0x3f))
                .copied()
                .unwrap_or(b'A'),
        )
    };
    let mut out = String::with_capacity(data.len().div_ceil(3).saturating_mul(4));
    for group in data.chunks(3) {
        let (b0, b1, b2) = match *group {
            [b0] => (b0, 0, 0),
            [b0, b1] => (b0, b1, 0),
            [b0, b1, b2, ..] => (b0, b1, b2),
            [] => continue,
        };
        out.push(symbol(b0.wrapping_shr(2)));
        out.push(symbol(b0.wrapping_shl(4) | b1.wrapping_shr(4)));
        if group.len() > 1 {
            out.push(symbol(b1.wrapping_shl(2) | b2.wrapping_shr(6)));
        } else {
            out.push('=');
        }
        if group.len() > 2 {
            out.push(symbol(b2));
        } else {
            out.push('=');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    #[test]
    fn sha1_fips_vectors() {
        // FIPS 180-4 examples and the NIST short-message vectors.
        assert_eq!(
            hex(&sha1(b"abc")),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(hex(&sha1(b"")), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(
            hex(&sha1(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            )),
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
        );
        assert_eq!(
            hex(&sha1(
                b"abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
            )),
            "a49b2446a02c645bf419f995b67091253a04a259"
        );
        // Excluded under Miri interpretation to avoid excessive emulation overhead for large
        // payloads.
        if !cfg!(miri) {
            assert_eq!(
                hex(&sha1(&vec![b'a'; 1_000_000])),
                "34aa973cd4c4daa4f61eeb2bdbad27316534016f"
            );
        }
    }

    #[test]
    fn sha1_padding_edges() {
        // Exercises boundary conditions surrounding one block and two block SHA-1 padding
        // transitions.
        let mut seen = std::collections::HashSet::new();
        for n in [0usize, 1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128] {
            assert!(seen.insert(sha1(&vec![0x5a; n])), "{n}");
        }
        assert_eq!(
            hex(&sha1(&[b'a'; 64])),
            "0098ba824b5c16427bd7a1122a5a442a25ec644d"
        );
    }

    #[test]
    fn base64_rfc4648_vectors() {
        let cases: [(&[u8], &str); 7] = [
            (b"", ""),
            (b"f", "Zg=="),
            (b"fo", "Zm8="),
            (b"foo", "Zm9v"),
            (b"foob", "Zm9vYg=="),
            (b"fooba", "Zm9vYmE="),
            (b"foobar", "Zm9vYmFy"),
        ];
        for (input, want) in cases {
            assert_eq!(base64(input), want);
        }
        assert_eq!(base64(&[0xfb, 0xff, 0xfe]), "+//+");
    }

    #[test]
    fn digest_matches_java() {
        // Values from Java `DigestAuthenticationProvider.generateDigest`
        assert_eq!(
            digest_id("super", "test"),
            "super:D/InIHSb7yEEbrWz8b9l71RjZJU="
        );
        assert_eq!(digest_id("u", "p"), "u:Jq7wMyA/w2Vd5WIDAKdu4OIIFEQ=");
        assert_eq!(
            digest_id("alice", "secret"),
            "alice:aYXlLOpEooaV1cRAvUL1fp9Qt7E="
        );
        assert_eq!(digest_id("a", "b:c"), "a:cLzgnoJ6mP5qz3w+mwvPE2vDgu0=");
        assert_eq!(digest_id("a:b", "c"), "a:cLzgnoJ6mP5qz3w+mwvPE2vDgu0=");
        assert_eq!(digest_id("", ""), ":BaefBs8/Z/cm2uaNGKIpD2yaUMk=");
        assert_eq!(
            digest_id("user", "p\u{e4}ssw\u{f6}rd"),
            "user:Vx8H+a8Jh/huGaoHXWUnrKRcDDI="
        );
    }

    #[test]
    fn digest_matches_the_fake_server() {
        for (u, p) in [
            ("u", "p"),
            ("bob", ""),
            ("x:y", "z"),
            ("\u{e9}", "\u{1f600}"),
        ] {
            assert_eq!(
                digest_id(u, p),
                zk_fakeserver::digest_id(&format!("{u}:{p}"))
            );
        }
    }

    proptest::proptest! {
        #![proptest_config(crate::test_config::proptest())]
        #[test]
        fn digest_agrees_with_the_fake_server(u in "\\PC{0,8}", p in "\\PC{0,80}") {
            assert_eq!(digest_id(&u, &p), zk_fakeserver::digest_id(&format!("{u}:{p}")));
        }
    }
}
