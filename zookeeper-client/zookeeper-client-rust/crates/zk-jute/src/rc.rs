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

//! ZooKeeper wire protocol return and error status codes.
//!
//! These constants correspond to `enum ZOO_ERRORS` in the canonical C client header
//! `zookeeper.h` and the Java `KeeperException.Code` definitions. ZooKeeper servers return
//! a 32-bit signed integer status code in the `err` field of each response header.
//!
//! While the standard codes defined here cover standard protocol outcomes (ranging from
//! success (`ZOK`), to system/network faults, to node and session errors), servers or
//! custom extensions may emit unrecognized error codes. The client protocol core treats
//! unlisted values as opaque error codes and preserves them without truncation.

/// Operation completed successfully with no errors (`ZOK`).
pub const ZOK: i32 = 0;
/// Sentinel marking the boundary of system level errors (`ZSYSTEMERROR`). Never emitted by the
/// server.
pub const ZSYSTEMERROR: i32 = -1;
/// Internal runtime inconsistency encountered by the ensemble or client state machine
/// (`ZRUNTIMEINCONSISTENCY`).
pub const ZRUNTIMEINCONSISTENCY: i32 = -2;
/// Data corruption or inconsistency detected in the server database or on disk
/// (`ZDATAINCONSISTENCY`).
pub const ZDATAINCONSISTENCY: i32 = -3;
/// Network transport failure; connection to the ZooKeeper server was lost during request
/// processing (`ZCONNECTIONLOSS`).
pub const ZCONNECTIONLOSS: i32 = -4;
/// Serialization or deserialization error during Jute wire unmarshalling (`ZMARSHALLINGERROR`).
pub const ZMARSHALLINGERROR: i32 = -5;
/// Requested operation or opcode is not supported or implemented by the server (`ZUNIMPLEMENTED`).
pub const ZUNIMPLEMENTED: i32 = -6;
/// Request execution or quorum commitment timed out prior to receiving a response
/// (`ZOPERATIONTIMEOUT`).
pub const ZOPERATIONTIMEOUT: i32 = -7;
/// Request payload contained invalid parameters or malformed arguments (`ZBADARGUMENTS`).
pub const ZBADARGUMENTS: i32 = -8;
/// Operation was attempted while the client handle or session was in an invalid state
/// (`ZINVALIDSTATE`).
pub const ZINVALIDSTATE: i32 = -9;
/// Dynamic cluster reconfiguration failed because a quorum of the proposed configuration is
/// unreachable (`ZNEWCONFIGNOQUORUM`).
pub const ZNEWCONFIGNOQUORUM: i32 = -13;
/// Dynamic cluster reconfiguration failed because another configuration change is already in
/// progress (`ZRECONFIGINPROGRESS`).
pub const ZRECONFIGINPROGRESS: i32 = -14;
/// Secure transport negotiation or TLS handshake failed (`ZSSLCONNECTIONERROR`).
pub const ZSSLCONNECTIONERROR: i32 = -15;
/// Sentinel marking the boundary of application/API-level errors (`ZAPIERROR`). Never emitted by
/// the server.
pub const ZAPIERROR: i32 = -100;
/// Specified znode path does not exist on the server (`ZNONODE`).
pub const ZNONODE: i32 = -101;
/// Client lacks authentication or permissions required by the target node's ACL (`ZNOAUTH`).
pub const ZNOAUTH: i32 = -102;
/// Expected version number does not match the node's current version (optimistic locking failure)
/// (`ZBADVERSION`).
pub const ZBADVERSION: i32 = -103;
/// Attempted to create a child znode beneath an ephemeral parent node
/// (`ZNOCHILDRENFOREPHEMERALS`).
pub const ZNOCHILDRENFOREPHEMERALS: i32 = -108;
/// Target znode path already exists in the tree (`ZNODEEXISTS`).
pub const ZNODEEXISTS: i32 = -110;
/// Target znode cannot be deleted because it still contains child nodes (`ZNOTEMPTY`).
pub const ZNOTEMPTY: i32 = -111;
/// Session has expired due to timeout or leader lease expiration; client handle is dead
/// (`ZSESSIONEXPIRED`).
pub const ZSESSIONEXPIRED: i32 = -112;
/// Completion or watch callback structure is invalid or corrupt (`ZINVALIDCALLBACK`).
pub const ZINVALIDCALLBACK: i32 = -113;
/// Specified Access Control List (ACL) is malformed, invalid, or empty (`ZINVALIDACL`).
pub const ZINVALIDACL: i32 = -114;
/// Authentication credentials supplied by the client were rejected (`ZAUTHFAILED`).
pub const ZAUTHFAILED: i32 = -115;
/// Client handle is in the process of shutting down or closing its connection (`ZCLOSING`).
pub const ZCLOSING: i32 = -116;
/// Internal notification indicating no server responses remain in the queue; not an error
/// condition (`ZNOTHING`).
pub const ZNOTHING: i32 = -117;
/// Session was moved to another server connection; current server discarded the request
/// (`ZSESSIONMOVED`).
pub const ZSESSIONMOVED: i32 = -118;
/// State-mutating operation was rejected by a server operating in read-only mode
/// (`ZNOTREADONLY`).
pub const ZNOTREADONLY: i32 = -119;
/// Ephemeral node creation was attempted across a local (non-replicated) session
/// (`ZEPHEMERALONLOCALSESSION`).
pub const ZEPHEMERALONLOCALSESSION: i32 = -120;
/// Removal failed because no matching watcher registration was found at the target path
/// (`ZNOWATCHER`).
pub const ZNOWATCHER: i32 = -121;
/// Dynamic cluster reconfiguration has been disabled on the ensemble (`ZRECONFIGDISABLED`).
pub const ZRECONFIGDISABLED: i32 = -123;
/// Connection closed by server because cluster policy strictly enforces SASL authentication
/// (`ZSESSIONCLOSEDREQUIRESASLAUTH`).
pub const ZSESSIONCLOSEDREQUIRESASLAUTH: i32 = -124;
/// Request was rejected or dropped because server side request throttling thresholds were
/// exceeded (`ZTHROTTLEDOP`).
pub const ZTHROTTLEDOP: i32 = -127;
