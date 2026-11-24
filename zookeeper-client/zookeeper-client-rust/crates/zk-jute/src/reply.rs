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

//! Utilities for inspecting and parsing ZooKeeper reply packet headers.
//!
//! Every standard server response packet (excluding the initial connect response)
//! begins with a 16-byte `ReplyHeader`. This header correlates the reply with
//! the originating request via its transaction ID (`xid`), provides the server's
//! state counter (`zxid`), and indicates whether the operation succeeded or failed
//! with an error code.

use crate::proto::ReplyHeader;
use crate::{DecodeError, Reader, Record};

/// Wire-encoded length of a [`ReplyHeader`] in bytes, consisting of a 4-byte `xid` (i32),
/// an 8-byte `zxid` (i64), and a 4-byte error code `err` (i32).
pub const REPLY_HEADER_LEN: usize = 16;

impl ReplyHeader {
    /// Decodes the leading [`ReplyHeader`] from an incoming frame payload and returns
    /// the parsed header alongside the remaining unparsed payload slice.
    ///
    /// This zero-copy split allows the connection dispatcher to immediately inspect the
    /// correlation `xid` and error status before routing the remaining payload bytes to
    /// the appropriate response handler for typed decoding.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if the provided frame slice contains fewer than
    /// 16 bytes. Receiving an undersized frame indicates wire corruption or protocol
    /// desynchronization, which typically warrants terminating the transport session.
    pub fn peek(frame: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        let mut r = Reader::new(frame);
        let header = Self::decode(&mut r)?;
        Ok((header, r.rest()))
    }
}
