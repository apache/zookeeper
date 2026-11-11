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

//! Common utilities and harness primitives shared across fuzz targets.
//!
//! [`Cursor`] consumes structured values from a raw fuzz input slice, while [`Enc`] serializes
//! values in the inverse format so the seed generator can construct deterministic test cases.
//! [`sim`] implements a simulated I/O driver and peer server for `zk_proto::Connection`,
//! drawing wire frames and driver decisions from the fuzz input stream.
//!
//! Because libFuzzer treats panics and aborts as defect signals, target invariant checks use
//! standard `assert!` macros.

#![forbid(unsafe_code)]

pub mod sim;

/// Invokes `$m!(module, Record)` for every Jute protocol record used by the client.
///
/// Shared by the Jute record fuzz target, the seed corpus generator, and `zk-jute` fixture tests.
#[macro_export]
macro_rules! for_each_record {
    ($m:ident) => {
        $m!(data, ACL);
        $m!(data, ClientInfo);
        $m!(data, Id);
        $m!(data, Stat);
        $m!(data, StatPersisted);
        $m!(proto, AddWatchRequest);
        $m!(proto, AuthPacket);
        $m!(proto, CheckVersionRequest);
        $m!(proto, CheckWatchesRequest);
        $m!(proto, ConnectRequest);
        $m!(proto, ConnectResponse);
        $m!(proto, Create2Response);
        $m!(proto, CreateRequest);
        $m!(proto, CreateResponse);
        $m!(proto, CreateTTLRequest);
        $m!(proto, DeleteRequest);
        $m!(proto, ErrorResponse);
        $m!(proto, ExistsRequest);
        $m!(proto, ExistsResponse);
        $m!(proto, GetACLRequest);
        $m!(proto, GetACLResponse);
        $m!(proto, GetAllChildrenNumberRequest);
        $m!(proto, GetAllChildrenNumberResponse);
        $m!(proto, GetChildren2Request);
        $m!(proto, GetChildren2Response);
        $m!(proto, GetChildrenRequest);
        $m!(proto, GetChildrenResponse);
        $m!(proto, GetDataRequest);
        $m!(proto, GetDataResponse);
        $m!(proto, GetEphemeralsRequest);
        $m!(proto, GetEphemeralsResponse);
        $m!(proto, GetMaxChildrenRequest);
        $m!(proto, GetMaxChildrenResponse);
        $m!(proto, GetSASLRequest);
        $m!(proto, MultiHeader);
        $m!(proto, ReconfigRequest);
        $m!(proto, RemoveWatchesRequest);
        $m!(proto, ReplyHeader);
        $m!(proto, RequestHeader);
        $m!(proto, SetACLRequest);
        $m!(proto, SetACLResponse);
        $m!(proto, SetDataRequest);
        $m!(proto, SetDataResponse);
        $m!(proto, SetMaxChildrenRequest);
        $m!(proto, SetSASLRequest);
        $m!(proto, SetSASLResponse);
        $m!(proto, SetWatches);
        $m!(proto, SetWatches2);
        $m!(proto, SyncRequest);
        $m!(proto, SyncResponse);
        $m!(proto, WatcherEvent);
        $m!(proto, WhoAmIResponse);
    };
}

/// Sequential reader over a raw fuzz input slice.
///
/// Reads beyond the end of the slice zero-fill missing bytes so any input prefix forms a complete
/// instruction stream. Callers check [`Cursor::is_empty`] to detect exhaustion.
#[derive(Clone, Debug)]
pub struct Cursor<'a> {
    data: &'a [u8],
}

impl<'a> Cursor<'a> {
    /// Creates a cursor over `data`.
    pub fn new(data: &'a [u8]) -> Self {
        Self { data }
    }

    /// Returns `true` if no unread bytes remain.
    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    /// Consumes and returns all remaining unread bytes.
    pub fn rest(&mut self) -> &'a [u8] {
        std::mem::take(&mut self.data)
    }

    /// Consumes up to `n` bytes, returning a shorter slice if fewer than `n` bytes remain.
    pub fn bytes(&mut self, n: usize) -> &'a [u8] {
        let n = n.min(self.data.len());
        let (head, tail) = self.data.split_at(n);
        self.data = tail;
        head
    }

    /// Reads a fixed-size byte array, zero-padding any trailing bytes if the input is exhausted.
    pub fn array<const N: usize>(&mut self) -> [u8; N] {
        let mut out = [0u8; N];
        let got = self.bytes(N);
        out[..got.len()].copy_from_slice(got);
        out
    }

    /// Reads a single byte.
    pub fn u8(&mut self) -> u8 {
        self.array::<1>()[0]
    }

    /// Reads a big-endian `u16`.
    pub fn u16(&mut self) -> u16 {
        u16::from_be_bytes(self.array())
    }

    /// Reads a big-endian `u32`.
    pub fn u32(&mut self) -> u32 {
        u32::from_be_bytes(self.array())
    }

    /// Reads a big-endian `i32`.
    pub fn i32(&mut self) -> i32 {
        i32::from_be_bytes(self.array())
    }

    /// Reads a big-endian `i64`.
    pub fn i64(&mut self) -> i64 {
        i64::from_be_bytes(self.array())
    }

    /// Reads a boolean from the least-significant bit of the next byte.
    pub fn bool(&mut self) -> bool {
        self.u8() & 1 == 1
    }

    /// Reads a `u16`-length-prefixed byte slice.
    pub fn lp(&mut self) -> &'a [u8] {
        let n = usize::from(self.u16());
        self.bytes(n)
    }
}

/// Serializer producing byte streams compatible with [`Cursor`].
#[derive(Clone, Debug, Default)]
pub struct Enc {
    /// Accumulated encoded bytes.
    pub out: Vec<u8>,
}

impl Enc {
    /// Creates an empty encoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends a single byte.
    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.out.push(v);
        self
    }

    /// Appends a big-endian `u16`.
    pub fn u16(&mut self, v: u16) -> &mut Self {
        self.out.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Appends a big-endian `u32`.
    pub fn u32(&mut self, v: u32) -> &mut Self {
        self.out.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Appends a big-endian `i32`.
    pub fn i32(&mut self, v: i32) -> &mut Self {
        self.out.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Appends a big-endian `i64`.
    pub fn i64(&mut self, v: i64) -> &mut Self {
        self.out.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Appends a raw byte slice.
    pub fn raw(&mut self, b: &[u8]) -> &mut Self {
        self.out.extend_from_slice(b);
        self
    }

    /// Appends a `u16`-length-prefixed byte slice, truncating inputs exceeding `u16::MAX` bytes.
    pub fn lp(&mut self, b: &[u8]) -> &mut Self {
        let b = &b[..b.len().min(usize::from(u16::MAX))];
        self.u16(u16::try_from(b.len()).unwrap_or(u16::MAX));
        self.raw(b)
    }

    /// Consumes and returns the encoded buffer.
    pub fn finish(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.out)
    }
}

/// Prefixes `body` with its 4-byte big-endian Jute frame length.
pub fn framed(body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 4);
    zk_jute::encode_frame(body, &mut out);
    out
}
