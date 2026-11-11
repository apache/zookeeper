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

//! Connection string parsing routines supporting legacy C syntax and canonical Java syntax.
//!
//! ### Legacy C Parsing Semantics
//!
//! Historical C clients (`zookeeper_init` and `resolve_hosts` in `zookeeper.c`) parse connection
//! strings according to idiosyncratic rules:
//! - Chroot extraction splits at the first forward slash (`/`) anywhere in the connection string,
//!   even when embedded within host tokens (e.g., `"h1:2181/app,h2:2181"` yields host `h1:2181`
//!   and chroot `/app,h2:2181`). A standalone chroot of `"/"` is treated as no chroot.
//! - The ensemble host list is separated strictly by commas (`,`), discarding empty tokens.
//! - Port numbers are identified after the final colon (`:`) of each entry. IPv6 square brackets
//!   lack special escaping, causing `[::1]:2181` to yield `[::1]` (which fails DNS resolution)
//!   while `::1:2181` yields `::1`.
//! - Numeric ports are converted using `strtol(text, &end, 0)`, requiring strict end-of-string
//!   matching and non-zero positive values. The raw port text is preserved verbatim as the service
//!   argument for `getaddrinfo`.
//! - Leading whitespace before hostnames is discarded, while trailing whitespace is preserved.
//!
//! ### Modern Java Parsing Semantics
//!
//! Java clients (`ConnectStringParser`) parse comma-separated host-port specifications, trimming
//! surrounding whitespace, accommodating IPv6 literals enclosed in brackets (`[::1]:2181`), and
//! defaulting to port 2181 when omitted.

use std::fmt;

use super::path::{PathError, is_valid_path, validate_path_strict};

/// POSIX `EINVAL` error code constant.
pub const EINVAL: i32 = 22;
/// ZooKeeper system failure error code (`ZSYSTEMERROR`).
pub const ZSYSTEMERROR: i32 = -1;
/// ZooKeeper invalid argument error code (`ZBADARGUMENTS`).
pub const ZBADARGUMENTS: i32 = -8;

/// Default ZooKeeper ensemble client port (2181).
pub const DEFAULT_PORT: u16 = 2181;

/// Type alias binding target architecture signed C `long`.
#[cfg(windows)]
pub type CLong = i32;
/// Type alias binding target architecture signed C `long`.
#[cfg(not(windows))]
pub type CLong = i64;

/// Identifies ASCII whitespace characters conforming to standard C locale `isspace`.
///
/// Emulates whitespace skipping during C `strtol` parsing prior to locale mutation.
pub fn is_c_space(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r')
}

/// Evaluates the numeric digit value of an ASCII alphanumeric byte in base-36 representation.
fn digit_value(b: u8) -> Option<u32> {
    match b {
        b'0'..=b'9' => Some(u32::from(b.wrapping_sub(b'0'))),
        b'a'..=b'z' => Some(u32::from(b.wrapping_sub(b'a')).saturating_add(10)),
        b'A'..=b'Z' => Some(u32::from(b.wrapping_sub(b'A')).saturating_add(10)),
        _ => None,
    }
}

/// Indicates whether the target platform's C `strtol` recognizes the C23 `0b` binary prefix in
/// base 0.
///
/// Starting in glibc 2.38, `strtol` parses `0b101` as a binary literal under C23 or when
/// `_GNU_SOURCE` is defined. Because the Autotools build of the legacy C library defines
/// `_GNU_SOURCE`, this port enables binary prefix recognition on GNU/Linux targets while omitting
/// it on non-glibc platforms.
const BINARY_PREFIX: bool = cfg!(all(target_os = "linux", target_env = "gnu"));

/// Emulates historical C `strtol(text, &end, 0)` under standard C locale conventions.
///
/// Evaluates base-8, base-10, and base-16 numeric literals, clamping out-of-range magnitudes
/// to target `CLong` extrema and returning the converted integer alongside consumed byte length.
/// On glibc targets, the C23 `0b` prefix also selects base 2.
pub fn strtol_base0(text: &[u8]) -> (CLong, usize) {
    let mut i = 0_usize;
    while text.get(i).copied().is_some_and(is_c_space) {
        i = i.saturating_add(1);
    }
    let mut negative = false;
    match text.get(i) {
        Some(b'+') => i = i.saturating_add(1),
        Some(b'-') => {
            negative = true;
            i = i.saturating_add(1);
        }
        _ => {}
    }
    let mut base = 10_u32;
    if text.get(i) == Some(&b'0') {
        let prefix = text.get(i.saturating_add(1)).copied();
        let next = text.get(i.saturating_add(2)).copied().and_then(digit_value);
        let is_x = matches!(prefix, Some(b'x' | b'X'));
        let is_b = BINARY_PREFIX && matches!(prefix, Some(b'b' | b'B'));
        if is_x && next.is_some_and(|d| d < 16) {
            base = 16;
            i = i.saturating_add(2);
        } else if is_b && next.is_some_and(|d| d < 2) {
            base = 2;
            i = i.saturating_add(2);
        } else {
            base = 8;
        }
    }
    let start_digits = i;
    // Accumulate magnitude into negative space to accommodate two's-complement minimums.
    let mut acc: CLong = 0;
    let mut overflow = false;
    while let Some(d) = text.get(i).copied().and_then(digit_value) {
        if d >= base {
            break;
        }
        if !overflow {
            let next = acc
                .checked_mul(CLong::from(u8::try_from(base).unwrap_or(10)))
                .and_then(|v| v.checked_sub(CLong::from(u8::try_from(d).unwrap_or(0))));
            match next {
                Some(v) => acc = v,
                None => overflow = true,
            }
        }
        i = i.saturating_add(1);
    }
    if i == start_digits {
        // Hexadecimal "0x" without trailing digits defaults to 0 with offset past '0'.
        return (0, 0);
    }
    let value = if overflow {
        if negative { CLong::MIN } else { CLong::MAX }
    } else if negative {
        acc
    } else {
        acc.checked_neg().unwrap_or(CLong::MAX)
    };
    (value, i)
}

/// Truncates a C `long` value to a 32-bit signed integer via wrapping two's-complement arithmetic.
#[allow(clippy::useless_conversion)]
pub fn long_to_int(v: CLong) -> i32 {
    let bytes = i64::from(v).to_le_bytes();
    let mut low = [0_u8; 4];
    for (dst, src) in low.iter_mut().zip(bytes.iter()) {
        *dst = *src;
    }
    i32::from_le_bytes(low)
}

/// Host entry representation produced by the legacy C connection string parser.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct HostSpec {
    /// Hostname or IP address slice for DNS resolution, with leading whitespace stripped.
    pub host: Vec<u8>,
    /// Unparsed port text passed as service name to `getaddrinfo`.
    pub service: Vec<u8>,
    /// Evaluated port number derived from legacy `strtol` conversion.
    pub port: i32,
}

/// Categorical failure modes encountered during legacy connection string parsing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum HostParseErrorKind {
    /// Connection string contains zero bytes.
    Empty,
    /// Chroot path failed validation under legacy path naming rules.
    InvalidChroot,
    /// Host entry omits mandatory colon delimiter separating host from port.
    NoPort,
    /// Port string is empty, non-numeric, contains trailing junk, or evaluates to zero.
    InvalidPort,
    /// Host list consists solely of delimiter commas without valid host entries.
    NoHosts,
}

/// Detailed error context describing a failure during legacy connection string evaluation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HostParseError {
    /// Specific parsing failure classification.
    pub kind: HostParseErrorKind,
    /// ZooKeeper error return code (`rc`).
    pub rc: i32,
    /// Associated operating system errno, or `None` if legacy C client omits errno setting.
    pub errno: Option<i32>,
    /// Zero-based index of the failing host entry within the connection string.
    pub entry: Option<usize>,
    /// Host entries parsed successfully prior to encountering the error.
    pub prefix: Vec<HostSpec>,
    /// Text snippet associated with the parsing failure for diagnostic formatting.
    pub subject: Vec<u8>,
}

impl HostParseError {
    /// Retrieves the C-compatible format specifier string for diagnostic logging.
    pub fn log_format(&self) -> Option<&'static str> {
        match self.kind {
            HostParseErrorKind::NoPort => Some("no port in %s"),
            HostParseErrorKind::InvalidPort => Some("invalid port in %s"),
            _ => None,
        }
    }

    fn new(kind: HostParseErrorKind, rc: i32) -> Self {
        // Address count verification in `resolve_hosts` is the sole path omitting errno
        // assignment.
        let errno = match kind {
            HostParseErrorKind::NoHosts => None,
            _ => Some(EINVAL),
        };
        Self {
            kind,
            rc,
            errno,
            entry: None,
            prefix: Vec::new(),
            subject: Vec::new(),
        }
    }
}

impl fmt::Display for HostParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let subject = String::from_utf8_lossy(&self.subject);
        match self.kind {
            HostParseErrorKind::Empty => f.write_str("empty connect string"),
            HostParseErrorKind::InvalidChroot => write!(f, "invalid chroot {subject}"),
            HostParseErrorKind::NoPort => write!(f, "no port in {subject}"),
            HostParseErrorKind::InvalidPort => write!(f, "invalid port in {subject}"),
            HostParseErrorKind::NoHosts => f.write_str("no host in connect string"),
        }
    }
}

impl std::error::Error for HostParseError {}

/// Encapsulates the results of parsing a legacy C ZooKeeper connection string.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConnectString {
    /// Ensemble host specification substring preceding the chroot delimiter (`zh->hostname`).
    pub host_text: Vec<u8>,
    /// Parsed sequence of ensemble host and port specifications.
    pub hosts: Vec<HostSpec>,
    /// Configured chroot path, or `None` if absent or set to root (`"/"`).
    pub chroot: Option<Vec<u8>>,
}

/// Parses an individual host-port token matching the inner loop of `resolve_hosts`.
fn parse_entry(token: &[u8]) -> Result<HostSpec, (HostParseErrorKind, Vec<u8>)> {
    let Some(colon) = token.iter().rposition(|&b| b == b':') else {
        return Err((HostParseErrorKind::NoPort, token.to_vec()));
    };
    let host_raw = token.get(..colon).unwrap_or_default();
    let service = token.get(colon.saturating_add(1)..).unwrap_or_default();
    let (value, used) = strtol_base0(service);
    let port = long_to_int(value);
    if service.is_empty() || used != service.len() || port == 0 {
        return Err((HostParseErrorKind::InvalidPort, host_raw.to_vec()));
    }
    let skip = host_raw.iter().take_while(|&&b| is_c_space(b)).count();
    Ok(HostSpec {
        host: host_raw.get(skip..).unwrap_or_default().to_vec(),
        service: service.to_vec(),
        port,
    })
}

/// Parses a comma-delimited ensemble server list conforming to `zoo_set_servers` and
/// `resolve_hosts`.
///
/// Input is evaluated as a null-terminated C string byte slice, halting at the first NUL byte.
/// Empty strings yield an empty collection without error. Comma-only strings trigger
/// [`HostParseErrorKind::NoHosts`], mirroring C's failure to discover valid endpoints resulting in
/// `ZSYSTEMERROR`.
pub fn parse_servers(s: &[u8]) -> Result<Vec<HostSpec>, HostParseError> {
    let s = super::hashtable::c_str(s);
    if s.is_empty() {
        return Ok(Vec::new());
    }
    let mut hosts = Vec::new();
    for (k, token) in s
        .split(|&b| b == b',')
        .filter(|t| !t.is_empty())
        .enumerate()
    {
        match parse_entry(token) {
            Ok(h) => hosts.push(h),
            Err((kind, subject)) => {
                let mut e = HostParseError::new(kind, ZBADARGUMENTS);
                e.entry = Some(k);
                e.prefix = hosts;
                e.subject = subject;
                return Err(e);
            }
        }
    }
    if hosts.is_empty() {
        return Err(HostParseError::new(
            HostParseErrorKind::NoHosts,
            ZSYSTEMERROR,
        ));
    }
    Ok(hosts)
}

/// Decomposes and validates a complete legacy ZooKeeper connection string matching
/// `zookeeper_init`.
///
/// Chroot syntax validation precedes host list parsing, preserving historical C execution order.
/// Input terminates at the first embedded NUL byte.
pub fn parse_init(s: &[u8]) -> Result<ConnectString, HostParseError> {
    let s = super::hashtable::c_str(s);
    if s.is_empty() {
        return Err(HostParseError::new(
            HostParseErrorKind::Empty,
            ZBADARGUMENTS,
        ));
    }
    let (host_text, chroot) = match s.iter().position(|&b| b == b'/') {
        Some(i) => {
            let root = s.get(i..).unwrap_or_default();
            let host = s.get(..i).unwrap_or_default();
            let root = if root.len() == 1 { None } else { Some(root) };
            (host, root)
        }
        None => (s, None),
    };
    if let Some(root) = chroot {
        if !is_valid_path(root, 0) {
            let mut e = HostParseError::new(HostParseErrorKind::InvalidChroot, ZBADARGUMENTS);
            e.subject = root.to_vec();
            return Err(e);
        }
    }
    let hosts = parse_servers(host_text)?;
    Ok(ConnectString {
        host_text: host_text.to_vec(),
        hosts,
        chroot: chroot.map(<[u8]>::to_vec),
    })
}

/// Ensemble server specification produced by the modern Java-compatible connection string parser.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct NativeHost {
    /// Hostname or IP literal without enclosing IPv6 brackets.
    pub host: String,
    /// TCP network port.
    pub port: u16,
}

/// Structured representation of a connection string parsed according to canonical Java
/// conventions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NativeConnectString {
    /// Ordered sequence of ensemble server specifications.
    pub hosts: Vec<NativeHost>,
    /// Configured chroot path prefix, or `None` if absent or `"/"`.
    pub chroot: Option<String>,
}

/// Errors arising during Java-style connection string parsing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NativeParseError {
    /// Connection string is empty or contains exclusively whitespace.
    Empty,
    /// Connection string requests unsupported `dns-srv://` discovery scheme.
    DnsSrvUnsupported,
    /// Chroot path violates strict Java path validation constraints.
    InvalidChroot(PathError),
    /// Host specification contains zero valid server endpoints.
    NoHosts,
    /// Malformed host specification token.
    InvalidEntry(String),
    /// Port token does not represent a valid 16-bit unsigned integer (`0..=65535`).
    InvalidPort(String),
}

impl fmt::Display for NativeParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            NativeParseError::Empty => f.write_str("Connect string cannot be null or empty"),
            NativeParseError::DnsSrvUnsupported => {
                f.write_str("dns-srv:// connect strings are not supported")
            }
            NativeParseError::InvalidChroot(e) => write!(f, "invalid chroot: {e}"),
            NativeParseError::NoHosts => f.write_str("connect string has no host"),
            NativeParseError::InvalidEntry(s) => write!(f, "invalid host entry {s}"),
            NativeParseError::InvalidPort(s) => write!(f, "invalid port in {s}"),
        }
    }
}

impl std::error::Error for NativeParseError {}

/// Trims leading and trailing control codes and spaces matching `java.lang.String.trim`.
fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Parses a port number verifying valid numeric formatting and 16-bit range constraints.
fn java_port(text: &str, entry: &str) -> Result<u16, NativeParseError> {
    let bad = || NativeParseError::InvalidPort(entry.to_owned());
    let digits = text.strip_prefix('+').unwrap_or(text);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        // Disallow negative values or non-digit characters.
        return Err(bad());
    }
    digits.parse::<u16>().map_err(|_| bad())
}

/// Parses an individual Java-format host-port entry, handling bracketed IPv6 literals.
fn parse_native_entry(entry: &str) -> Result<NativeHost, NativeParseError> {
    if let Some(inner) = entry.strip_prefix('[') {
        // Enforce bracketed IPv6 syntax: last ']' closes address literal.
        let Some(close) = inner.rfind(']') else {
            return Err(NativeParseError::InvalidEntry(entry.to_owned()));
        };
        let host = inner.get(..close).unwrap_or_default();
        if host.is_empty() {
            return Err(NativeParseError::InvalidEntry(entry.to_owned()));
        }
        let rest = inner.get(close.saturating_add(1)..).unwrap_or_default();
        if rest.is_empty() {
            return Ok(NativeHost {
                host: host.to_owned(),
                port: DEFAULT_PORT,
            });
        }
        let Some(port_text) = rest.strip_prefix(':') else {
            return Err(NativeParseError::InvalidEntry(entry.to_owned()));
        };
        if port_text.is_empty() {
            return Err(NativeParseError::InvalidEntry(entry.to_owned()));
        }
        return Ok(NativeHost {
            host: host.to_owned(),
            port: java_port(port_text, entry)?,
        });
    }
    match entry.rfind(':') {
        None => Ok(NativeHost {
            host: entry.to_owned(),
            port: DEFAULT_PORT,
        }),
        Some(colon) => {
            let host = entry.get(..colon).unwrap_or_default();
            let port_text = entry.get(colon.saturating_add(1)..).unwrap_or_default();
            let port = if port_text.is_empty() {
                DEFAULT_PORT
            } else {
                java_port(port_text, entry)?
            };
            Ok(NativeHost {
                host: host.to_owned(),
                port,
            })
        }
    }
}

/// Parses a ZooKeeper connection string conforming to canonical Java client syntax.
///
/// Supports comma-separated host-port entries with optional trailing chroot path (e.g.
/// `"h1:2181,h2:2181/chroot"` or `"h1,[::1]:2182"`). Entries with omitted ports default to 2181.
/// Chroot paths are validated against strict Java znode path naming rules.
pub fn parse_connect_string_native(s: &str) -> Result<NativeConnectString, NativeParseError> {
    if java_trim(s).is_empty() {
        return Err(NativeParseError::Empty);
    }
    if s.starts_with("dns-srv://") {
        return Err(NativeParseError::DnsSrvUnsupported);
    }
    let (server_part, chroot) = match s.find('/') {
        Some(i) => {
            let root = s.get(i..).unwrap_or_default();
            let server = s.get(..i).unwrap_or_default();
            if root.len() == 1 {
                (server, None)
            } else {
                validate_path_strict(root, false).map_err(NativeParseError::InvalidChroot)?;
                (server, Some(root.to_owned()))
            }
        }
        None => (s, None),
    };
    let mut hosts = Vec::new();
    for entry in server_part
        .split(',')
        .map(java_trim)
        .filter(|e| !e.is_empty())
    {
        hosts.push(parse_native_entry(entry)?);
    }
    if hosts.is_empty() {
        return Err(NativeParseError::NoHosts);
    }
    Ok(NativeConnectString { hosts, chroot })
}
