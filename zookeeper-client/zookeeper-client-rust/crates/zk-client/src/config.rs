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

//! Dynamic ensemble configuration parsing and cluster reconfiguration.
//!
//! ZooKeeper exposes cluster membership dynamically at `/zookeeper/config` formatted as Java
//! properties text. Each active quorum member is represented as a
//! `server.N=host:quorum:election[:role][;[client_addr:]client_port]` property alongside an
//! ensemble `version=<hex>` tracking configuration epochs. Parsers implemented here adopt lenient
//! error handling semantics. Malformed lines are skipped to preserve forward compatibility while
//! the raw configuration payload is retained intact.

use zk_jute::JString;

use crate::error::Error;

/// Structured ensemble configuration representation parsed from `/zookeeper/config` or returned by
/// reconfiguration operations.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EnsembleConfig {
    /// Verbatim text payload retrieved from the `/zookeeper/config` znode.
    pub raw: String,
    /// Monotonically increasing configuration epoch version parsed from `version=<hex>`.
    pub version: Option<i64>,
    /// Parsed ensemble server specifications preserving the order defined in the configuration.
    pub servers: Vec<ServerEntry>,
}

/// Specification of an individual ensemble server parsed from a `server.N=...` entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServerEntry {
    /// Unique numeric server identifier assigned to this ensemble node.
    pub id: u64,
    /// Hostname or IP address utilized for quorum communication.
    pub host: String,
    /// TCP port dedicated to quorum communication.
    pub quorum_port: u16,
    /// TCP port dedicated to leader election protocols.
    pub election_port: u16,
    /// Operational consensus role of the server within the quorum. Defaults to participant when
    /// unspecified.
    pub role: ServerRole,
    /// Client facing network address and port tuple if explicitly advertised in the server entry.
    pub client: Option<(String, u16)>,
}

/// Quorum membership role determining whether a server participates in voting consensus.
#[non_exhaustive]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum ServerRole {
    /// Active voting peer participating in leader election and transactional quorum commits.
    Participant,
    /// Non-voting cluster follower that replicates state transitions without contributing to
    /// quorum consensus.
    Observer,
}

/// Specification of cluster membership mutations submitted via dynamic reconfiguration.
#[non_exhaustive]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Reconfig {
    /// Incremental cluster mutation specifying joining members and departing server IDs.
    Incremental {
        /// Server specifications to enroll into the ensemble, formatted as
        /// `"server.N=host:p1:p2[:role];[caddr:]cport"`.
        joining: Vec<String>,
        /// Numeric identifiers of servers scheduled for removal from quorum membership.
        leaving: Vec<u64>,
    },
    /// Complete membership replacement superseding all existing cluster configuration.
    NonIncremental {
        /// Full set of server specifications defining the target ensemble membership.
        members: Vec<String>,
    },
}

impl Reconfig {
    /// Serializes dynamic reconfiguration parameters into the wire-format tuple expected by the
    /// server protocol.
    ///
    /// Per ZooKeeper server protocol semantics, transmitting a non-null `members` parameter
    /// indicates an unconditional membership replacement. [`Reconfig::NonIncremental`] always
    /// populates the `members` string, even if empty, while empty delta sets in
    /// [`Reconfig::Incremental`] are transmitted as null strings.
    pub(crate) fn wire(&self) -> (JString, JString, JString) {
        match self {
            Self::Incremental { joining, leaving } => {
                let joining = if joining.is_empty() {
                    JString::Null
                } else {
                    JString::from(joining.join(","))
                };
                let leaving = if leaving.is_empty() {
                    JString::Null
                } else {
                    let ids: Vec<String> = leaving.iter().map(u64::to_string).collect();
                    JString::from(ids.join(","))
                };
                (joining, leaving, JString::Null)
            }
            Self::NonIncremental { members } => (
                JString::Null,
                JString::Null,
                JString::from(members.join(",")),
            ),
        }
    }
}

impl EnsembleConfig {
    /// Synthesizes a client connection string from advertised client addresses across all
    /// ensemble servers.
    ///
    /// Returns `None` if no server entries advertise a valid client-facing endpoint. If a server
    /// advertises a wildcard address, the quorum hostname is substituted because client
    /// connections require routable hostnames or addresses. IPv6 host addresses are automatically
    /// enclosed in brackets. The returned connection string contains no chroot prefix.
    pub(crate) fn client_connect_string(&self) -> Option<String> {
        let entries: Vec<String> = self
            .servers
            .iter()
            .filter_map(|server| {
                let (addr, port) = server.client.as_ref()?;
                let host = if is_wildcard(addr) {
                    server.host.as_str()
                } else {
                    addr.as_str()
                };
                if host.contains(':') {
                    Some(format!("[{host}]:{port}"))
                } else {
                    Some(format!("{host}:{port}"))
                }
            })
            .collect();
        if entries.is_empty() {
            None
        } else {
            Some(entries.join(","))
        }
    }
}

/// Parses the text payload retrieved from the `/zookeeper/config` znode into structured ensemble
/// configuration metadata.
///
/// # Errors
///
/// Returns [`Error::MarshallingError`] if the payload byte slice cannot be decoded as valid UTF-8.
/// Unparseable lines or malformed directives are silently ignored to preserve forward
/// compatibility.
pub(crate) fn parse_config(data: &[u8]) -> Result<EnsembleConfig, Error> {
    let raw = std::str::from_utf8(data).map_err(|_| Error::MarshallingError)?;
    let mut version = None;
    let mut servers = Vec::new();
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let (key, value) = (key.trim(), value.trim());
        if key == "version" {
            if let Ok(v) = i64::from_str_radix(value, 16) {
                version = Some(v);
            }
        } else if let Some(id) = key.strip_prefix("server.") {
            if let Some(entry) = id.parse().ok().and_then(|id| parse_server(id, value)) {
                servers.push(entry);
            }
        }
    }
    Ok(EnsembleConfig {
        raw: raw.to_owned(),
        version,
        servers,
    })
}

/// Parses a single `server.N` property line following the Java `QuorumServer` syntax:
/// `server_config[;client_config[;secure_client_config]]`.
///
/// Handles multi-address specifications separated by `|`, retaining the primary interface address.
/// Any role modifier applied to an individual interface establishes the server wide consensus
/// role. Secure client configurations are bypassed here as they are handled through separate
/// transport options.
fn parse_server(id: u64, value: &str) -> Option<ServerEntry> {
    let mut parts = value.split(';');
    let server_part = parts.next()?;
    let client = match parts.next().map(str::trim) {
        None | Some("") => None,
        Some(text) => Some(parse_client(text)?),
    };
    let mut first: Option<(String, u16, u16)> = None;
    let mut role = ServerRole::Participant;
    for address in server_part.split('|') {
        let fields = host_and_port(address.trim())?;
        let (host, quorum, election, kind) = match fields.as_slice() {
            [host, quorum, election] => (host, quorum, election, None),
            [host, quorum, election, kind] => (host, quorum, election, Some(kind)),
            _ => return None,
        };
        let quorum_port = port(quorum)?;
        let election_port = port(election)?;
        if let Some(kind) = kind {
            role = parse_role(kind)?;
        }
        if first.is_none() {
            first = Some(((*host).to_owned(), quorum_port, election_port));
        }
    }
    let (host, quorum_port, election_port) = first?;
    Some(ServerEntry {
        id,
        host,
        quorum_port,
        election_port,
        role,
        client,
    })
}

/// Parses a client endpoint specification in `[host:]port` format, defaulting to `0.0.0.0` when
/// only a port is provided.
fn parse_client(text: &str) -> Option<(String, u16)> {
    let fields = host_and_port(text)?;
    match fields.as_slice() {
        [p] => Some(("0.0.0.0".to_owned(), port(p)?)),
        [host, p] => Some(((*host).to_owned(), port(p)?)),
        _ => None,
    }
}

/// Deconstructs a host-and-port specification into constituent segments, stripping enclosing
/// brackets from IPv6 literals.
fn host_and_port(s: &str) -> Option<Vec<&str>> {
    let Some(inner) = s.strip_prefix('[') else {
        return Some(s.split(':').collect());
    };
    let close = inner.find(']')?;
    let host = inner.get(..close)?;
    let rest = inner.get(close.checked_add(1)?..)?;
    if rest.is_empty() {
        return Some(vec![host]);
    }
    // Java requires a colon and port specification following the closing IPv6 bracket.
    let ports = rest.strip_prefix(':').filter(|p| !p.is_empty())?;
    let mut fields = vec![host];
    fields.extend(ports.split(':'));
    Some(fields)
}

/// Parses a string slice into a TCP port number, validating numeric digits and range bounds.
fn port(text: &str) -> Option<u16> {
    let digits = text.trim();
    let digits = digits.strip_prefix('+').unwrap_or(digits);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    digits.parse().ok()
}

/// Maps a role name string to its corresponding [`ServerRole`] variant case-insensitively.
fn parse_role(text: &str) -> Option<ServerRole> {
    let text = text.trim();
    if text.eq_ignore_ascii_case("participant") {
        Some(ServerRole::Participant)
    } else if text.eq_ignore_ascii_case("observer") {
        Some(ServerRole::Observer)
    } else {
        None
    }
}

/// Determines whether an IP address string represents a wildcard (`0.0.0.0` or `::`).
fn is_wildcard(addr: &str) -> bool {
    matches!(addr, "0.0.0.0" | "::" | "0:0:0:0:0:0:0:0")
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "server.1=10.0.0.1:2888:3888:participant;0.0.0.0:2181\n\
                          server.2=zk2.example.com:2888:3888;zk2.example.com:2181\n\
                          server.3=[2001:db8::3]:2888:3888:observer;[2001:db8::3]:2182\n\
                          version=10000000a\n";

    fn entry(id: u64, host: &str, role: ServerRole, client: Option<(&str, u16)>) -> ServerEntry {
        ServerEntry {
            id,
            host: host.to_owned(),
            quorum_port: 2888,
            election_port: 3888,
            role,
            client: client.map(|(h, p)| (h.to_owned(), p)),
        }
    }

    #[test]
    fn sample_config() {
        let c = parse_config(SAMPLE.as_bytes()).unwrap();
        assert_eq!(c.raw, SAMPLE);
        assert_eq!(c.version, Some(0x1_0000_000a));
        assert_eq!(
            c.servers,
            vec![
                entry(
                    1,
                    "10.0.0.1",
                    ServerRole::Participant,
                    Some(("0.0.0.0", 2181))
                ),
                entry(
                    2,
                    "zk2.example.com",
                    ServerRole::Participant,
                    Some(("zk2.example.com", 2181))
                ),
                entry(
                    3,
                    "2001:db8::3",
                    ServerRole::Observer,
                    Some(("2001:db8::3", 2182))
                ),
            ]
        );
        assert_eq!(
            c.client_connect_string().as_deref(),
            Some("10.0.0.1:2181,zk2.example.com:2181,[2001:db8::3]:2182")
        );
    }

    #[test]
    fn client_port_only_and_secure_port() {
        let c = parse_config(
            b"server.7=h:1:2;2181\nserver.8=h:1:2;;h:2281\nserver.9=h:1:2;h:2181;h:2281",
        )
        .unwrap();
        assert_eq!(c.version, None);
        let clients: Vec<_> = c.servers.iter().map(|s| s.client.clone()).collect();
        assert_eq!(
            clients,
            vec![
                Some(("0.0.0.0".to_owned(), 2181)),
                None,
                Some(("h".to_owned(), 2181))
            ]
        );
        assert_eq!(c.servers.first().unwrap().quorum_port, 1);
        assert_eq!(c.servers.first().unwrap().election_port, 2);
        assert_eq!(c.client_connect_string().as_deref(), Some("h:2181,h:2181"));
    }

    #[test]
    fn multi_address_keeps_the_first() {
        let c = parse_config(b"server.1=a:1:2|b:3:4:observer;5").unwrap();
        let s = c.servers.first().unwrap();
        assert_eq!(
            (s.host.as_str(), s.quorum_port, s.election_port),
            ("a", 1, 2)
        );
        assert_eq!(s.role, ServerRole::Observer);
        assert_eq!(s.client, Some(("0.0.0.0".to_owned(), 5)));
    }

    #[test]
    fn lenient_parser_skips_bad_lines() {
        let text = "# comment\n\
                    \n\
                    junk\n\
                    server.x=h:1:2;3\n\
                    server.1=h:1;3\n\
                    server.2=h:1:2:leader;3\n\
                    server.3=h:1:70000;3\n\
                    server.4=h:1:2;h:3:4\n\
                    server.5=[::1:1:2;3\n\
                    server.6=h:1:2:3:4:5;3\n\
                    version=zz\n\
                    server.10 = h:1:2 ;3\n\
                    dynamicConfigFile=/x\n";
        let c = parse_config(text.as_bytes()).unwrap();
        assert_eq!(c.version, None);
        assert_eq!(c.servers.len(), 1);
        let s = c.servers.first().unwrap();
        assert_eq!(s.id, 10);
        assert_eq!(s.client, Some(("0.0.0.0".to_owned(), 3)));
        assert_eq!(c.raw, text);
    }

    #[test]
    fn no_client_address_gives_no_connect_string() {
        let c = parse_config(b"server.1=h:1:2\nversion=1").unwrap();
        assert_eq!(c.version, Some(1));
        assert_eq!(c.servers.len(), 1);
        assert_eq!(c.client_connect_string(), None);
        let empty = parse_config(b"").unwrap();
        assert!(empty.servers.is_empty());
        assert_eq!(empty.client_connect_string(), None);
    }

    #[test]
    fn wildcard_ipv6_client_uses_the_quorum_host() {
        let c = parse_config(b"server.1=[fe80::1]:1:2;[::]:2181").unwrap();
        assert_eq!(
            c.servers.first().unwrap().client,
            Some(("::".to_owned(), 2181))
        );
        assert_eq!(c.client_connect_string().as_deref(), Some("[fe80::1]:2181"));
    }

    #[test]
    fn non_utf8_is_a_marshalling_error() {
        assert_eq!(
            parse_config(b"server.1=h:1:2;3\n\xff").unwrap_err(),
            Error::MarshallingError
        );
    }

    fn text(s: &JString) -> Option<&str> {
        s.as_str()
    }

    #[test]
    fn reconfig_wire_forms() {
        let r = Reconfig::Incremental {
            joining: vec![
                "server.4=h4:2888:3888;2181".to_owned(),
                "server.5=h5:2888:3888:observer;2181".to_owned(),
            ],
            leaving: vec![1, 2],
        };
        let (j, l, m) = r.wire();
        assert_eq!(
            text(&j),
            Some("server.4=h4:2888:3888;2181,server.5=h5:2888:3888:observer;2181")
        );
        assert_eq!(text(&l), Some("1,2"));
        assert!(m.is_null());

        let r = Reconfig::Incremental {
            joining: vec![],
            leaving: vec![3],
        };
        let (j, l, m) = r.wire();
        assert!(j.is_null());
        assert_eq!(text(&l), Some("3"));
        assert!(m.is_null());

        let r = Reconfig::NonIncremental {
            members: vec!["server.1=a:1:2;3".to_owned(), "server.2=b:1:2;3".to_owned()],
        };
        let (j, l, m) = r.wire();
        assert!(j.is_null() && l.is_null());
        assert_eq!(text(&m), Some("server.1=a:1:2;3,server.2=b:1:2;3"));

        // Transmitting an empty members collection explicitly triggers full cluster membership
        // replacement.
        let (_, _, m) = Reconfig::NonIncremental { members: vec![] }.wire();
        assert_eq!(text(&m), Some(""));
    }

    proptest::proptest! {
        #![proptest_config(crate::test_config::proptest())]
        #[test]
        fn parse_config_never_panics(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..64)) {
            if let Ok(c) = parse_config(&data) {
                let _ = c.client_connect_string();
            }
        }

        #[test]
        fn parse_config_never_panics_on_config_like_text(s in "(server\\.[0-9]{1,2}=[a-z\\[\\]:0-9;|]{0,20}\n){0,3}(version=[0-9a-g]{0,10})?") {
            let c = parse_config(s.as_bytes()).unwrap();
            assert_eq!(c.raw, s);
            let _ = c.client_connect_string();
        }
    }
}
