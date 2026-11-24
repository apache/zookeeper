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

//! In-memory DIGEST-MD5 SASL authentication provider (RFC 2831).
//!
//! Emulates the Java ZooKeeper server SASL exchange for configured single user credentials. The
//! server issues a challenge for realm `zk-sasl-md5` with `qop=auth` and `algorithm=md5-sess`,
//! verifies the client digest response against the configured credentials, and returns a mutual
//! authentication token (`rspauth`). Server nonces are derived deterministically from the
//! connection ID. Includes an RFC 1321 MD5 implementation sharing Merkle–Damgård padding with
//! `digest.rs`.

/// Authentication realm advertised in server challenges and expected in client responses.
pub(crate) const REALM: &str = "zk-sasl-md5";

/// Service principal URI (`digest-uri`) expected in client responses.
pub(crate) const DIGEST_URI: &str = "zookeeper/zk-sasl-md5";

/// Maximum accepted byte length for a client SASL response token.
const MAX_RESPONSE: usize = 4096;

/// State of an in-flight DIGEST-MD5 challenge-response handshake.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Initial state awaiting the client's empty mechanism initiation token.
    Start,
    /// Challenge sent and awaiting the client's digest response token.
    Challenged,
    /// Handshake completed (either authenticated with `rspauth` or rejected).
    Done,
}

/// server side state machine for a single DIGEST-MD5 authentication exchange.
#[derive(Debug)]
pub(crate) struct DigestServer {
    user: String,
    password: String,
    nonce: String,
    phase: Phase,
}

impl DigestServer {
    /// Creates a DIGEST-MD5 state machine for `user`, `password`, and connection `conn`.
    pub(crate) fn new(user: String, password: String, conn: u64) -> Self {
        Self {
            user,
            password,
            nonce: format!("fakenonce{conn:016x}"),
            phase: Phase::Start,
        }
    }

    /// Processes an inbound client SASL token and returns the next server response token, or
    /// `None` if authentication fails.
    pub(crate) fn step(&mut self, token: &[u8]) -> Option<Vec<u8>> {
        let out = match self.phase {
            Phase::Start if token.is_empty() => {
                self.phase = Phase::Challenged;
                Some(self.challenge())
            }
            Phase::Challenged => self.check(token).map(|rspauth| {
                self.phase = Phase::Done;
                format!("rspauth={rspauth}").into_bytes()
            }),
            Phase::Start | Phase::Done => None,
        };
        if out.is_none() {
            self.phase = Phase::Done;
        }
        out
    }

    fn challenge(&self) -> Vec<u8> {
        format!(
            "realm=\"{REALM}\",nonce=\"{}\",qop=\"auth\",charset=utf-8,algorithm=md5-sess",
            self.nonce
        )
        .into_bytes()
    }

    /// Verifies the client digest response token and returns the mutual `rspauth` hex digest.
    fn check(&self, token: &[u8]) -> Option<String> {
        let fields = directives(token)?;
        let get = |name: &str| {
            fields
                .iter()
                .find(|(n, _)| n == name)
                .map(|(_, v)| v.as_str())
        };
        let utf8 = get("charset").is_some_and(|c| c.eq_ignore_ascii_case("utf-8"));
        let realm = get("realm").unwrap_or("");
        let cnonce = get("cnonce")?;
        let nc = get("nc")?;
        let uri = get("digest-uri")?;
        let ok = get("username") == Some(self.user.as_str())
            && (realm.is_empty() || realm == REALM)
            && get("nonce") == Some(self.nonce.as_str())
            && nc == "00000001"
            && get("qop").is_none_or(|q| q == "auth")
            && uri == DIGEST_URI
            && !cnonce.is_empty();
        if !ok {
            return None;
        }
        let mut a1 = md5(&join(&[
            &text(&self.user, utf8),
            &text(realm, utf8),
            &text(&self.password, utf8),
        ]))
        .to_vec();
        a1.extend_from_slice(format!(":{}:{cnonce}", self.nonce).as_bytes());
        let ha1 = hex(&md5(&a1));
        let kd = |a2: String| {
            let ha2 = hex(&md5(a2.as_bytes()));
            hex(&md5(format!(
                "{ha1}:{}:{nc}:{cnonce}:auth:{ha2}",
                self.nonce
            )
            .as_bytes()))
        };
        let response = kd(format!("AUTHENTICATE:{uri}"));
        if get("response") != Some(response.as_str()) {
            return None;
        }
        Some(kd(format!(":{uri}")))
    }
}

/// Joins byte slices with a `:` separator.
fn join(parts: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::new();
    for (i, p) in parts.iter().enumerate() {
        if i > 0 {
            out.push(b':');
        }
        out.extend_from_slice(p);
    }
    out
}

/// Encodes `value` according to RFC 2831 charset rules (ISO-8859-1 if representable, or UTF-8).
fn text(value: &str, utf8: bool) -> Vec<u8> {
    let latin1: Option<Vec<u8>> = value.chars().map(|c| u8::try_from(c).ok()).collect();
    match latin1 {
        Some(b) => b,
        None if utf8 => value.as_bytes().to_vec(),
        None => Vec::new(),
    }
}

/// Parses an RFC 2831 comma-separated directive list into `(name, value)` pairs.
fn directives(input: &[u8]) -> Option<Vec<(String, String)>> {
    if input.len() > MAX_RESPONSE {
        return None;
    }
    let text = std::str::from_utf8(input).ok()?;
    let mut out = Vec::new();
    let mut it = text.chars().peekable();
    loop {
        while it.next_if(|c| c.is_whitespace() || *c == ',').is_some() {}
        if it.peek().is_none() {
            return Some(out);
        }
        let mut name = String::new();
        while let Some(c) = it.next_if(|c| *c != '=' && *c != ',' && !c.is_whitespace()) {
            name.push(c.to_ascii_lowercase());
        }
        while it.next_if(|c| c.is_whitespace()).is_some() {}
        if it.next() != Some('=') || name.is_empty() {
            return None;
        }
        while it.next_if(|c| c.is_whitespace()).is_some() {}
        let mut value = String::new();
        if it.next_if_eq(&'"').is_some() {
            loop {
                match it.next()? {
                    '"' => break,
                    '\\' => value.push(it.next()?),
                    c => value.push(c),
                }
            }
        } else {
            while let Some(c) = it.next_if(|c| *c != ',' && !c.is_whitespace()) {
                value.push(c);
            }
        }
        out.push((name, value));
    }
}

/// Formats `bytes` as a lowercase hexadecimal string.
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Per-round left-rotation shift amounts from RFC 1321 section 3.4.
const SHIFTS: [u32; 16] = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];

/// Sine-derived constant table `T[i] = floor(2^32 * abs(sin(i)))` from RFC 1321.
const SINES: [u32; 64] = [
    0xd76a_a478,
    0xe8c7_b756,
    0x2420_70db,
    0xc1bd_ceee,
    0xf57c_0faf,
    0x4787_c62a,
    0xa830_4613,
    0xfd46_9501,
    0x6980_98d8,
    0x8b44_f7af,
    0xffff_5bb1,
    0x895c_d7be,
    0x6b90_1122,
    0xfd98_7193,
    0xa679_438e,
    0x49b4_0821,
    0xf61e_2562,
    0xc040_b340,
    0x265e_5a51,
    0xe9b6_c7aa,
    0xd62f_105d,
    0x0244_1453,
    0xd8a1_e681,
    0xe7d3_fbc8,
    0x21e1_cde6,
    0xc337_07d6,
    0xf4d5_0d87,
    0x455a_14ed,
    0xa9e3_e905,
    0xfcef_a3f8,
    0x676f_02d9,
    0x8d2a_4c8a,
    0xfffa_3942,
    0x8771_f681,
    0x6d9d_6122,
    0xfde5_380c,
    0xa4be_ea44,
    0x4bde_cfa9,
    0xf6bb_4b60,
    0xbebf_bc70,
    0x289b_7ec6,
    0xeaa1_27fa,
    0xd4ef_3085,
    0x0488_1d05,
    0xd9d4_d039,
    0xe6db_99e5,
    0x1fa2_7cf8,
    0xc4ac_5665,
    0xf429_2244,
    0x432a_ff97,
    0xab94_23a7,
    0xfc93_a039,
    0x655b_59c3,
    0x8f0c_cc92,
    0xffef_f47d,
    0x8584_5dd1,
    0x6fa8_7e4f,
    0xfe2c_e6e0,
    0xa301_4314,
    0x4e08_11a1,
    0xf753_7e82,
    0xbd3a_f235,
    0x2ad7_d2bb,
    0xeb86_d391,
];

/// Computes the 128-bit MD5 digest of `input` per RFC 1321.
pub(crate) fn md5(input: &[u8]) -> [u8; 16] {
    let mut h: [u32; 4] = [0x6745_2301, 0xefcd_ab89, 0x98ba_dcfe, 0x1032_5476];
    for block in crate::digest::pad(input, u64::to_le_bytes).chunks_exact(64) {
        let mut m = [0u32; 16];
        for (slot, word) in m.iter_mut().zip(block.chunks_exact(4)) {
            *slot = u32::from_le_bytes(word.try_into().unwrap_or([0; 4]));
        }
        let [mut a, mut b, mut c, mut d] = h;
        for (i, k) in SINES.iter().enumerate() {
            let (f, g) = match i >> 4 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), i.wrapping_mul(5).wrapping_add(1)),
                2 => (b ^ c ^ d, i.wrapping_mul(3).wrapping_add(5)),
                _ => (c ^ (b | !d), i.wrapping_mul(7)),
            };
            let word = m.get(g & 15).copied().unwrap_or(0);
            let shift = SHIFTS.get(((i >> 4) << 2) | (i & 3)).copied().unwrap_or(0);
            let sum = f.wrapping_add(a).wrapping_add(*k).wrapping_add(word);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(sum.rotate_left(shift));
        }
        for (slot, v) in h.iter_mut().zip([a, b, c, d]) {
            *slot = slot.wrapping_add(v);
        }
    }
    let mut out = [0u8; 16];
    for (chunk, word) in out.chunks_exact_mut(4).zip(h) {
        chunk.copy_from_slice(&word.to_le_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn md5_matches_rfc_1321() {
        assert_eq!(hex(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(&md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(
            hex(&md5(
                b"12345678901234567890123456789012345678901234567890123456789012345678901234567890"
            )),
            "57edf4a22be3c955ac49da2e2107b67a"
        );
    }

    fn response(server: &DigestServer, password: &str) -> Vec<u8> {
        let cnonce = "OA6MHXh6VqTrRk";
        let mut a1 = md5(format!("bob:{REALM}:{password}").as_bytes()).to_vec();
        a1.extend_from_slice(format!(":{}:{cnonce}", server.nonce).as_bytes());
        let ha1 = hex(&md5(&a1));
        let ha2 = hex(&md5(format!("AUTHENTICATE:{DIGEST_URI}").as_bytes()));
        let r = hex(&md5(format!(
            "{ha1}:{}:00000001:{cnonce}:auth:{ha2}",
            server.nonce
        )
        .as_bytes()));
        format!(
            "charset=utf-8,username=\"bob\",realm=\"{REALM}\",nonce=\"{}\",nc=00000001,\
             cnonce=\"{cnonce}\",digest-uri=\"{DIGEST_URI}\",maxbuf=65536,response={r},qop=auth",
            server.nonce
        )
        .into_bytes()
    }

    #[test]
    fn a_good_response_gets_rspauth() {
        let mut s = DigestServer::new("bob".into(), "pw".into(), 7);
        let challenge = s.step(b"");
        assert!(challenge.is_some_and(|c| c.starts_with(b"realm=\"zk-sasl-md5\"")));
        let token = response(&s, "pw");
        let out = s.step(&token).unwrap_or_default();
        assert!(out.starts_with(b"rspauth="));
        assert_eq!(out.len(), 8 + 32);
        assert!(s.step(b"").is_none());
    }

    #[test]
    fn a_bad_response_fails() {
        let mut s = DigestServer::new("bob".into(), "pw".into(), 7);
        assert!(s.step(b"").is_some());
        let token = response(&s, "wrong");
        assert!(s.step(&token).is_none());
        // RFC 2831 requires rejecting non-empty initial client tokens prior to the challenge.
        let mut s = DigestServer::new("bob".into(), "pw".into(), 8);
        assert!(s.step(b"x").is_none());
        for bad in [&b"username"[..], b"a=\"b", b"=x"] {
            assert!(directives(bad).is_none());
        }
    }
}
