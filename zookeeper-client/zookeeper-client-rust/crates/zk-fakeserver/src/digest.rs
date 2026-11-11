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

//! SHA-1 digest calculation and Base64 encoding for ZooKeeper digest authentication.
//!
//! ZooKeeper's `digest` authentication scheme identifies principals using the format
//! `user:base64(sha1("user:password"))`. This module implements FIPS PUB 180-4 SHA-1, standard
//! Merkle–Damgård padding, and RFC 4648 Base64 encoding without external crate dependencies.

/// Computes the 160-bit SHA-1 hash of `input` per FIPS PUB 180-4.
pub(crate) fn sha1(input: &[u8]) -> [u8; 20] {
    let mut h: [u32; 5] = [
        0x6745_2301,
        0xEFCD_AB89,
        0x98BA_DCFE,
        0x1032_5476,
        0xC3D2_E1F0,
    ];
    for block in pad(input, u64::to_be_bytes).chunks_exact(64) {
        let mut w = [0u32; 16];
        for (slot, word) in w.iter_mut().zip(block.chunks_exact(4)) {
            *slot = u32::from_be_bytes(word.try_into().unwrap_or([0; 4]));
        }
        let [mut a, mut b, mut c, mut d, mut e] = h;
        for t in 0..80usize {
            let word = if t < 16 {
                at(&w, t)
            } else {
                let v = (at(&w, t.wrapping_add(13))
                    ^ at(&w, t.wrapping_add(8))
                    ^ at(&w, t.wrapping_add(2))
                    ^ at(&w, t))
                .rotate_left(1);
                if let Some(slot) = w.get_mut(t & 15) {
                    *slot = v;
                }
                v
            };
            let (f, k) = match t {
                0..=19 => ((b & c) | (!b & d), 0x5A82_7999),
                20..=39 => (b ^ c ^ d, 0x6ED9_EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1B_BCDC),
                _ => (b ^ c ^ d, 0xCA62_C1D6),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(word);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        for (slot, v) in h.iter_mut().zip([a, b, c, d, e]) {
            *slot = slot.wrapping_add(v);
        }
    }
    let mut out = [0u8; 20];
    for (chunk, v) in out.chunks_exact_mut(4).zip(h) {
        chunk.copy_from_slice(&v.to_be_bytes());
    }
    out
}

/// Applies Merkle–Damgård padding and appends the 64-bit bit-length for MD5 or SHA-1.
///
/// Appends a `0x80` byte, zero-pads until the length modulo 64 is 56, and appends the original
/// message bit length encoded by `len_bytes`.
pub(crate) fn pad(input: &[u8], len_bytes: fn(u64) -> [u8; 8]) -> Vec<u8> {
    let bit_len = u64::try_from(input.len())
        .unwrap_or(u64::MAX)
        .wrapping_mul(8);
    let mut msg = input.to_vec();
    msg.push(0x80);
    while msg.len() & 63 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&len_bytes(bit_len));
    msg
}

/// Indexes into the 16-word circular message schedule buffer during SHA-1 compression.
fn at(w: &[u32; 16], i: usize) -> u32 {
    w.get(i & 15).copied().unwrap_or(0)
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Encodes `input` as an RFC 4648 Base64 string with `=` padding.
pub(crate) fn base64(input: &[u8]) -> String {
    let mut out = String::new();
    for chunk in input.chunks(3) {
        let b0 = chunk.first().copied().unwrap_or(0);
        let b1 = chunk.get(1).copied().unwrap_or(0);
        let b2 = chunk.get(2).copied().unwrap_or(0);
        let n = u32::from(b0).wrapping_shl(16) | u32::from(b1).wrapping_shl(8) | u32::from(b2);
        let sym = |shift: u32| -> char {
            let idx = usize::try_from(n.wrapping_shr(shift) & 63).unwrap_or(0);
            char::from(B64.get(idx).copied().unwrap_or(b'A'))
        };
        out.push(sym(18));
        out.push(sym(12));
        out.push(if chunk.len() > 1 { sym(6) } else { '=' });
        out.push(if chunk.len() > 2 { sym(0) } else { '=' });
    }
    out
}

/// Computes the ZooKeeper `digest` ACL identity for a `user:password` credential string.
///
/// Matches `org.apache.zookeeper.server.auth.DigestAuthenticationProvider.generateDigest`,
/// returning `user:base64(sha1(credentials))`.
pub fn digest_id(credentials: &str) -> String {
    let user = credentials.split(':').next().unwrap_or("");
    format!("{user}:{}", base64(&sha1(credentials.as_bytes())))
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::{base64, sha1};

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    #[test]
    fn sha1_vectors() {
        assert_eq!(hex(&sha1(b"")), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(
            hex(&sha1(b"abc")),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        let long = b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        assert_eq!(hex(&sha1(long)), "84983e441c3bd26ebaae4aa1f95129e5e54670f1");
    }

    #[test]
    fn base64_vectors() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }
}
