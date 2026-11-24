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

//! Parser and validator for ZooKeeper connection strings.
//!
//! Conforms to canonical ZooKeeper connect string grammar as implemented by
//! `zk_proto::native::parse_connect_string_native`. Decomposes comma-separated
//! host lists, optional port overrides, IPv6 address brackets, and an optional trailing
//! chroot namespace suffix into validated runtime configurations.

use std::borrow::Cow;

use zk_proto::HostSpec;
use zk_proto::native::{NativeParseError, parse_connect_string_native};

use crate::error::Error;

/// Parsed representation of a validated ZooKeeper connection string.
#[derive(Clone, Debug)]
pub(crate) struct ConnectString {
    /// Ordered list of ensemble host specifications parsed as native endpoints.
    pub(crate) hosts: Vec<HostSpec>,
    /// Host list substring isolated prior to any trailing chroot path, used for diagnostics and
    /// logging.
    pub(crate) host_text: String,
    /// Optional hierarchical chroot namespace path, or `None` if connecting directly to root.
    pub(crate) chroot: Option<String>,
}

/// Parses a ZooKeeper connection string into host specifications and an optional chroot suffix.
///
/// # Errors
///
/// Returns [`Error::InvalidArgument`] if the connection string violates host formatting, port
/// boundaries, or chroot path syntax rules.
pub(crate) fn parse(s: &str) -> Result<ConnectString, Error> {
    let parsed = parse_connect_string_native(s).map_err(|e| invalid(s, &e))?;
    // The chroot segment commences at the first slash boundary, demarcating the end of the host
    // list.
    let host_text = match s.find('/') {
        Some(at) => s.get(..at).unwrap_or_default(),
        None => s,
    };
    Ok(ConnectString {
        hosts: parsed.hosts.into_iter().map(HostSpec::Native).collect(),
        host_text: host_text.to_owned(),
        chroot: parsed.chroot,
    })
}

/// Parses a dynamic ensemble server update string.
///
/// Dynamic reconfiguration updates modify only the ensemble server list; altering an existing
/// session's chroot namespace via dynamic server update is explicitly forbidden.
///
/// # Errors
///
/// Returns [`Error::InvalidArgument`] if syntax parsing fails or if a chroot component is
/// specified.
pub(crate) fn parse_update(s: &str) -> Result<Vec<HostSpec>, Error> {
    let parsed = parse(s)?;
    match parsed.chroot {
        Some(chroot) => Err(Error::InvalidArgument(Cow::Owned(format!(
            "update_servers cannot change the chroot: {chroot:?} in {s:?}"
        )))),
        None => Ok(parsed.hosts),
    }
}

/// Formats a low-level parse failure into a detailed [`Error::InvalidArgument`].
///
/// Path validation errors on chroot components are enriched with rule descriptions and byte
/// offsets.
fn invalid(s: &str, e: &NativeParseError) -> Error {
    let text = match e {
        NativeParseError::InvalidChroot(_) => {
            let chroot = s.find('/').and_then(|at| s.get(at..)).unwrap_or_default();
            match crate::path::check(chroot, false) {
                Err(pe) => format!("invalid chroot in connect string {s:?}: {pe}"),
                Ok(()) => format!("invalid connect string {s:?}: {e}"),
            }
        }
        _ => format!("invalid connect string {s:?}: {e}"),
    };
    Error::InvalidArgument(Cow::Owned(text))
}

#[cfg(test)]
mod tests {
    use super::*;
    use zk_proto::native::NativeHost;

    fn native(h: &HostSpec) -> (&str, u16) {
        match h {
            HostSpec::Native(NativeHost { host, port }) => (host.as_str(), *port),
            HostSpec::Legacy(_) => panic!("legacy host spec"),
        }
    }

    fn hosts(c: &ConnectString) -> Vec<(&str, u16)> {
        c.hosts.iter().map(native).collect()
    }

    fn invalid_text(s: &str) -> String {
        match parse(s) {
            Err(Error::InvalidArgument(t)) => t.into_owned(),
            other => panic!("{s:?}: {other:?}"),
        }
    }

    #[test]
    fn hosts_ports_and_default_port() {
        let c = parse("h1:2182,h2, h3:2183 ").unwrap();
        assert_eq!(hosts(&c), vec![("h1", 2182), ("h2", 2181), ("h3", 2183)]);
        assert_eq!(c.host_text, "h1:2182,h2, h3:2183 ");
        assert_eq!(c.chroot, None);
        let c = parse("h1:").unwrap();
        assert_eq!(hosts(&c), vec![("h1", 2181)]);
    }

    #[test]
    fn ipv6_literals() {
        let c = parse("[::1]:2182,[fe80::1],::1").unwrap();
        // Bare host:port endpoints are partitioned at the final colon delimiter, matching Java
        // client behavior.
        assert_eq!(hosts(&c), vec![("::1", 2182), ("fe80::1", 2181), (":", 1)]);
        let c = parse("[2001:db8::7]:3000/app").unwrap();
        assert_eq!(hosts(&c), vec![("2001:db8::7", 3000)]);
        assert_eq!(c.chroot.as_deref(), Some("/app"));
        assert_eq!(c.host_text, "[2001:db8::7]:3000");
    }

    #[test]
    fn chroot_at_the_end() {
        let c = parse("h1:2181,h2:2181/app/sub").unwrap();
        assert_eq!(c.chroot.as_deref(), Some("/app/sub"));
        assert_eq!(c.host_text, "h1:2181,h2:2181");
        assert_eq!(hosts(&c).len(), 2);
        // A trailing slash alone designates root and normalizes to an absent chroot prefix.
        let c = parse("h1:2181/").unwrap();
        assert_eq!(c.chroot, None);
        assert_eq!(c.host_text, "h1:2181");
    }

    #[test]
    fn errors_are_invalid_argument() {
        assert!(invalid_text("").contains("empty"));
        assert!(invalid_text("   ").contains("empty"));
        assert!(invalid_text(",,").contains("no host"));
        assert!(invalid_text("/app").contains("no host"));
        assert!(invalid_text("h1:abc").contains("invalid port"));
        assert!(invalid_text("h1:65536").contains("invalid port"));
        assert!(invalid_text("h1:-1").contains("invalid port"));
        assert!(invalid_text("[::1").contains("invalid host entry"));
        assert!(invalid_text("dns-srv://zk.example.com").contains("dns-srv"));
        let t = invalid_text("h1:2181/app/");
        assert!(t.contains("invalid chroot"), "{t}");
        assert!(t.contains("must not end with /"), "{t}");
        let t = invalid_text("h1:2181/a//b");
        assert!(t.contains("empty node name"), "{t}");
        let t = invalid_text("h1:2181/a/..");
        assert!(t.contains("relative paths"), "{t}");
    }

    #[test]
    fn update_rejects_a_chroot() {
        let hosts = parse_update("h1:1,[::1]:2").unwrap();
        assert_eq!(
            hosts.iter().map(native).collect::<Vec<_>>(),
            vec![("h1", 1), ("::1", 2)]
        );
        assert!(parse_update("h1:1/").is_ok());
        match parse_update("h1:1/app") {
            Err(Error::InvalidArgument(t)) => assert!(t.contains("chroot"), "{t}"),
            other => panic!("{other:?}"),
        }
        assert!(matches!(parse_update(""), Err(Error::InvalidArgument(_))));
    }

    proptest::proptest! {
        #![proptest_config(crate::test_config::proptest())]
        #[test]
        fn parse_never_panics(s in proptest::prelude::any::<String>()) {
            let _ = parse(&s);
            let _ = parse_update(&s);
        }

        #[test]
        fn parse_never_panics_on_connect_like_text(s in "[a-z0-9\\[\\]:,/. é\u{0}]{0,24}") {
            if let Ok(c) = parse(&s) {
                assert!(!c.hosts.is_empty());
                if let Some(root) = &c.chroot {
                    assert!(crate::path::ZPath::new(root).is_ok());
                }
            }
        }
    }
}
