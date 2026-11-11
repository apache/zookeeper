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

//! Reserved transaction identifiers (`xid` values) used by the ZooKeeper protocol.
//!
//! ZooKeeper multiplexes requests, responses, and unsolicited server events across
//! a single connection using 32-bit transaction identifiers (`xid`). While application
//! requests use monotonically increasing positive integers assigned by the client,
//! negative `xid` values are reserved by the wire protocol for out-of-band signals,
//! connection maintenance (such as heartbeats), authentication exchanges, and session
//! state re-establishment. These constants mirror the definitions established in the
//! C client (`zk_adaptor.h`) and standard ZooKeeper Java server implementation.

/// Transaction identifier (`xid`) indicating an unsolicited watch notification triggered on the
/// server.
pub const WATCHER_EVENT: i32 = -1;
/// Transaction identifier (`xid`) reserved for connection heartbeat ping requests and responses.
pub const PING: i32 = -2;
/// Transaction identifier (`xid`) used for client authentication packets and their acknowledgments.
pub const AUTH: i32 = -4;
/// Transaction identifier (`xid`) assigned to `SetWatches` requests when reregistering active
/// watches upon session reconnection.
pub const SET_WATCHES: i32 = -8;
