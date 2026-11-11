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

//! Jute serialization primitives, record definitions, and wire codecs for the ZooKeeper
//! protocol.
//!
//! This foundational crate implements the core binary serialization format and wire framing
//! specified by ZooKeeper. Designed with zero third party runtime dependencies and no
//! direct I/O coupling, it provides deterministic, low overhead encoding and decoding routines
//! suitable for high throughput network clients.
//!
//! Key components provided by this crate include:
//!
//! - Generated protocol records corresponding to the canonical Jute definitions in
//!   `org.apache.zookeeper.data` and `org.apache.zookeeper.proto`, exposed via [`data`] and
//!   [`proto`].
//! - The core [`Record`] serialization trait, bounded input [`Reader`], and null-preserving
//!   wire primitives ([`JString`], [`JBuffer`], and [`JVec`]).
//! - Big-endian length-prefixed packet framing facilities ([`FrameDecoder`], [`encode_frame`]).
//! - The specialized fixed-layout connection negotiation codec ([`handshake`]).
//! - Atomic transaction batch streaming and response demultiplexing ([`multi`]).
//! - Standard protocol enumeration constants, including opcodes ([`opcode`]), reserved
//!   transaction identifiers ([`xid`]), and status codes ([`rc`]).
//!
//! Code Generation:
//!
//! Record structures are automatically generated from the upstream `zookeeper.jute` IDL
//! specification via the `RustGenerator` compiler target in `zookeeper-jute`. To rebuild the
//! generated records, execute `mvn -pl zookeeper-jute generate-sources` from the project
//! repository root.

#![forbid(unsafe_code)]
#![cfg_attr(test, allow(clippy::disallowed_macros))]

mod frame;
pub mod handshake;
pub mod multi;
pub mod opcode;
pub mod rc;
mod reply;
mod wire;
pub mod xid;

/// Container module encapsulating auto-generated Jute record implementations.
///
/// Structures declared within `zookeeper_jute.rs` are re-exported publicly
/// through the top-level [`data`] and [`proto`] modules.
mod generated {
    include!("generated/zookeeper_jute.rs");
}

pub use frame::{
    FrameDecoder, FrameError, FrameStart, Pushed, begin_frame, encode_frame, finish_frame,
};
pub use generated::{data, proto};
pub use reply::REPLY_HEADER_LEN;
pub use wire::{DecodeError, JBuffer, JString, JVec, Reader, Record};
