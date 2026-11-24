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

//! Configuration parameters for cluster ensemble state and individual listener endpoints.

use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use crate::script::Script;
use crate::transport::Acceptor;

/// Authentication policy governing client digest `addAuth` operations.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum AuthPolicy {
    /// Accepts any submitted digest credential, matching default Apache ZooKeeper behavior.
    #[default]
    AcceptAll,
    /// Rejects all incoming credentials with `ZAUTHFAILED` and closes the connection.
    RejectAll,
    /// Validates submitted credentials against an explicit allowlist of `user:password` pairs.
    Only(Vec<String>),
}

/// Wire format applied to connection handshake (`ConnectResponse`) payloads.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum HandshakeStyle {
    /// Modern 37-byte payload including the trailing `read_only` flag introduced in ZooKeeper 3.4.
    #[default]
    Modern,
    /// Legacy 36-byte payload omitting the `read_only` flag to emulate pre-3.4 server endpoints.
    Legacy36,
}

/// Segmentation strategy used to divide outbound Jute frames across discrete socket writes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Chunking {
    /// Writes each protocol frame in a single unsegmented write operation.
    #[default]
    Whole,
    /// Divides outbound frames into fixed-size write slices of the given byte length (0 disables
    /// segmentation).
    Fixed(usize),
    /// Partitions outbound frames into pseudo-randomly sized slices between 1 and `max` bytes
    /// using the seeded RNG.
    Random {
        /// Upper bound on the byte length of each write chunk.
        max: usize,
    },
}

/// Cluster-wide configuration governing session timeouts, authentication, and data tree policies.
#[derive(Debug, Clone)]
pub struct ClusterConfig {
    /// Deterministic RNG seed governing session IDs, passwords, fault probabilities, and chunking.
    pub seed: u64,
    /// Server ensemble member ID encoded into the high byte of generated 64-bit session IDs.
    pub server_id: u8,
    /// Minimum session timeout in milliseconds permitted during handshake negotiation.
    pub min_session_timeout_ms: i32,
    /// Maximum session timeout in milliseconds permitted during handshake negotiation.
    pub max_session_timeout_ms: i32,
    /// Enables background expiration of sessions that remain idle past their negotiated timeout.
    pub session_expiry: bool,
    /// Polling interval of the background thread checking session leases and reaping expired nodes.
    pub check_interval: Duration,
    /// Digest authentication policy enforced when evaluating `addAuth` requests.
    pub auth: AuthPolicy,
    /// Optional super-user digest (`user:base64(sha1("user:password"))`, e.g. from
    /// [`digest_id`](crate::digest_id)) that bypasses ACL checks.
    pub super_digest: Option<String>,
    /// Enables time-to-live (TTL) znode creation modes (`zookeeper.extendedTypesEnabled`).
    pub ttl_enabled: bool,
    /// Enables dynamic cluster reconfiguration via the `reconfig` opcode.
    pub reconfig_enabled: bool,
    /// Optional interval for background purging of empty container znodes and expired TTL nodes
    /// (`None` restricts reaping to explicit [`Cluster::reap`](crate::Cluster::reap) calls).
    pub reap_interval: Option<Duration>,
    /// Maximum inbound request frame size in bytes before the server closes the connection.
    pub max_frame_len: u32,
    /// Base epoch timestamp in milliseconds for the logical clock (`ctime`/`mtime = epoch_ms +
    /// zxid`).
    pub epoch_ms: i64,
}

impl Default for ClusterConfig {
    fn default() -> Self {
        Self {
            seed: 0x5EED_2181,
            server_id: 1,
            min_session_timeout_ms: 4_000,
            max_session_timeout_ms: 40_000,
            session_expiry: true,
            check_interval: Duration::from_millis(10),
            auth: AuthPolicy::AcceptAll,
            super_digest: None,
            ttl_enabled: true,
            reconfig_enabled: false,
            reap_interval: None,
            max_frame_len: 0x000F_FFFF_u32.saturating_add(1024),
            epoch_ms: 1_700_000_000_000,
        }
    }
}

/// Runtime options for an individual network listener endpoint within an ensemble.
#[derive(Clone, Default)]
pub struct ServerOptions {
    /// Configures the listener to operate in read-only mode, rejecting write transactions.
    pub read_only: bool,
    /// Explicit response override for the `isro` four-letter command (defaults to `"ro"` or `"rw"`
    /// based on `read_only`).
    pub isro_answer: Option<String>,
    /// Handshake wire format emitted by this listener.
    pub handshake: HandshakeStyle,
    /// Outbound frame segmentation profile applied by this listener.
    pub chunking: Chunking,
    /// Delay inserted between consecutive chunk writes of a segmented outbound frame.
    pub chunk_gap: Duration,
    /// Fault-injection script evaluated against frames arriving on this listener.
    pub script: Script,
    /// Optional transport acceptor for wrapping accepted TCP sockets (such as TLS; plain TCP when
    /// `None`).
    pub acceptor: Option<Arc<dyn Acceptor>>,
}

impl fmt::Debug for ServerOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ServerOptions")
            .field("read_only", &self.read_only)
            .field("isro_answer", &self.isro_answer)
            .field("handshake", &self.handshake)
            .field("chunking", &self.chunking)
            .field("chunk_gap", &self.chunk_gap)
            .field("script", &self.script)
            .field("acceptor", &self.acceptor.is_some())
            .finish()
    }
}

impl ServerOptions {
    /// Sets a custom transport acceptor (such as a TLS acceptor) for incoming connections.
    #[must_use]
    pub fn with_acceptor(mut self, acceptor: Arc<dyn Acceptor>) -> Self {
        self.acceptor = Some(acceptor);
        self
    }
}
