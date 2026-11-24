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

//! nonblocking TCP socket abstractions with SIGPIPE suppression and atomic descriptor flag
//! management.
//!
//! [`TcpSock`] encapsulates a nonblocking stream socket managed via `rustix::net` for
//! [`crate::iocore`] and `crate::net::probe`. All socket writes apply `MSG_NOSIGNAL` (or
//! configure socket-level `SO_NOSIGPIPE` on Apple platforms) to prevent broken connection writes
//! from triggering `SIGPIPE` process termination signals.
//!
//! # Close-on-Exec Disciplines
//!
//! - The C compatibility drop-in drivers ([`crate::polled`] and [`crate::threaded`]) apply
//!   `FD_CLOEXEC` selectively when `DriverConfig::cloexec` is enabled by the C runtime shim.
//! - Native Rust drivers ([`crate::native`] and `crate::tokio`) unconditionally enable
//!   `FD_CLOEXEC` to guarantee descriptor hygiene across `fork()` + `exec()`.
//! - On platforms lacking atomic `SOCK_CLOEXEC` support (e.g., Darwin), `fcntl(F_SETFD)` is
//!   invoked immediately following socket creation.

use std::net::SocketAddr;
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, OwnedFd};

use rustix::fs::OFlags;
use rustix::io::{Errno, FdFlags};
use rustix::net::{AddressFamily, RecvFlags, SendFlags, SocketFlags, SocketType};
use zk_proto::ConnectStart;

use crate::iocore::RawSock;

/// nonblocking stream socket wrapping an owned file descriptor.
#[derive(Debug)]
pub struct TcpSock {
    fd: OwnedFd,
}

/// Result returned upon successful creation and configuration of a [`TcpSock`].
#[derive(Debug)]
pub struct Opened {
    /// Newly allocated and configured nonblocking TCP socket.
    pub sock: TcpSock,
    /// Indicates whether disabling Nagle's algorithm (`TCP_NODELAY`) succeeded.
    pub nodelay_ok: bool,
}

/// Protocol send flags applied to socket writes to suppress SIGPIPE signals.
#[cfg(not(any(target_vendor = "apple", windows)))]
const SEND_FLAGS: SendFlags = SendFlags::NOSIGNAL;

/// Protocol send flags for platforms relying on socket-level `SO_NOSIGPIPE` options (macOS / iOS).
#[cfg(any(target_vendor = "apple", windows))]
const SEND_FLAGS: SendFlags = SendFlags::empty();

/// Platform socket creation flag enabling nonblocking mode atomically at allocation time.
#[cfg(not(any(
    target_vendor = "apple",
    windows,
    target_os = "aix",
    target_os = "espidf",
    target_os = "haiku",
    target_os = "horizon",
    target_os = "nto",
    target_os = "vita",
)))]
const NONBLOCK_FLAG: Option<SocketFlags> = Some(SocketFlags::NONBLOCK);

/// Placeholder indicating that atomic nonblocking socket creation is unsupported on this platform.
#[cfg(any(
    target_vendor = "apple",
    windows,
    target_os = "aix",
    target_os = "espidf",
    target_os = "haiku",
    target_os = "horizon",
    target_os = "nto",
    target_os = "vita",
))]
const NONBLOCK_FLAG: Option<SocketFlags> = None;

/// Platform socket creation flag enabling close-on-exec atomically at allocation time.
#[cfg(not(any(
    target_vendor = "apple",
    windows,
    target_os = "aix",
    target_os = "haiku",
)))]
const CLOEXEC_FLAG: Option<SocketFlags> = Some(SocketFlags::CLOEXEC);

/// Placeholder indicating that atomic close-on-exec socket creation is unsupported on this
/// platform.
#[cfg(any(
    target_vendor = "apple",
    windows,
    target_os = "aix",
    target_os = "haiku",
))]
const CLOEXEC_FLAG: Option<SocketFlags> = None;

/// Maximum TCP segment payload size (in bytes) on a loopback interface.
#[cfg(any(target_os = "linux", target_os = "android"))]
const LOOPBACK_SEGMENT_BYTES: usize = 65496;

/// Minimum `TCP_MAXSEG` option value accepted by the Linux kernel.
#[cfg(any(target_os = "linux", target_os = "android"))]
const MIN_SEGMENT_OPTION: usize = 88;

/// Maximum `TCP_MAXSEG` option value accepted by the Linux kernel.
#[cfg(any(target_os = "linux", target_os = "android"))]
const MAX_SEGMENT_OPTION: usize = 32_767;

/// TCP segment payload capacity (in bytes) per loopback packet.
///
/// The Linux kernel transmits packets of up to 64 KiB over the loopback device, carrying a whole
/// number of segments per packet. After the kernel header reserve, 65215 bytes remain for
/// segments on Linux 5.14 for `x86_64`; 65100 bytes retains a margin for kernels with a larger
/// reserve.
#[cfg(any(target_os = "linux", target_os = "android"))]
const LOOPBACK_PACKET_BYTES: usize = 65_100;

/// Computes the `TCP_MAXSEG` clamp for a loopback socket with an `SO_RCVBUF` allocation of
/// `rcvbuf` bytes.
///
/// The Linux kernel advertises only a fraction of `SO_RCVBUF` as the TCP receive window, reserving
/// the remainder for `sk_buff` metadata overhead. On loopback the advertised window is half the
/// buffer or more and suppresses window update ACKs until the available window can at least
/// double. Whenever a single peer segment exceeds half of the advertised window, a fast consumer
/// can leave the free window just under one MSS without triggering a window update, forcing the
/// sender to stall until the ~200 ms persist timer expires.
///
/// Bounding the segment size to at most one sixth of `rcvbuf` prevents persist timer stalls
/// whenever the effective window is at least one third of `rcvbuf`. Returns `None` when
/// `rcvbuf / 6` already meets or exceeds the loopback MTU segment limit.
///
/// Below that bound, the clamped limit is aligned downward to the largest segment size for which
/// the fewest whole segments permitted by the limit fit within a single 65100-byte loopback
/// packet. With the default 128 KiB receive buffer, an unaligned one-sixth limit of 21845 bytes
/// fits two segments per packet, whereas the aligned 21700-byte limit fits three segments per
/// packet, reducing the packets and acknowledgments required for a large transfer.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn loopback_segment_limit(rcvbuf: usize) -> Option<u32> {
    let limit = rcvbuf / 6;
    if limit >= LOOPBACK_SEGMENT_BYTES {
        return None;
    }
    // Clamp to the kernel's permissible TCP_MAXSEG bounds. Clamping downward only widens the
    // window update safety margin.
    let limit = limit.clamp(MIN_SEGMENT_OPTION, MAX_SEGMENT_OPTION);
    // Because the aligned segment size never exceeds the clamped limit, the safety margin against
    // persist timer stalls is preserved.
    let segments = LOOPBACK_PACKET_BYTES.div_ceil(limit);
    let aligned = LOOPBACK_PACKET_BYTES
        .checked_div(segments)
        .map_or(limit, |size| size.max(MIN_SEGMENT_OPTION).min(limit));
    u32::try_from(aligned).ok()
}

/// Applies a pre-handshake `TCP_MAXSEG` ceiling derived from the socket's initial receive buffer
/// size on loopback paths.
///
/// Because MSS negotiation occurs during the SYN exchange, the option must be configured prior to
/// `connect()` so it bounds both peer transmissions and local segments. Errors are ignored as this
/// is best effort performance tuning.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn limit_loopback_segments(fd: &OwnedFd) {
    let Ok(rcvbuf) = rustix::net::sockopt::socket_recv_buffer_size(fd) else {
        return;
    };
    if let Some(mss) = loopback_segment_limit(rcvbuf) {
        let _ignored = socket2::SockRef::from(fd).set_tcp_mss(mss);
    }
}

/// Determines whether a connection to `addr` routes over the kernel loopback interface.
///
/// Non-loopback targets are probed by connecting an ephemeral UDP socket (which transmits no
/// packets) and comparing `getsockname()` against `addr`. Probe errors conservatively yield
/// `false`, and any false positive merely applies a smaller MSS than otherwise.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn uses_loopback_device(addr: &SocketAddr) -> bool {
    let ip = addr.ip().to_canonical();
    if ip.is_loopback() || ip.is_unspecified() {
        return true;
    }
    // Unwrap IPv4-mapped IPv6 addresses to an AF_INET target while preserving IPv6 scope IDs on
    // native IPv6 addresses.
    let target = if ip == addr.ip() {
        *addr
    } else {
        SocketAddr::new(ip, addr.port())
    };
    let family = if target.is_ipv4() {
        AddressFamily::INET
    } else {
        AddressFamily::INET6
    };
    let Ok(udp) = rustix::net::socket_with(family, SocketType::DGRAM, SocketFlags::CLOEXEC, None)
    else {
        return false;
    };
    if rustix::net::connect(&udp, &target).is_err() {
        return false;
    }
    rustix::net::getsockname(&udp)
        .ok()
        .and_then(|any| SocketAddr::try_from(any).ok())
        .is_some_and(|local| local.ip().to_canonical() == ip)
}

/// Configures the provided file descriptor for non-blocking I/O via `fcntl(F_SETFL, O_NONBLOCK)`.
///
/// Modifies the descriptor status flags while preserving existing flags.
///
/// # Errors
///
/// Returns an [`Errno`] if querying or setting descriptor status flags fails.
pub(crate) fn set_nonblocking(fd: BorrowedFd<'_>) -> Result<(), Errno> {
    let flags = rustix::fs::fcntl_getfl(fd)?;
    rustix::fs::fcntl_setfl(fd, flags | OFlags::NONBLOCK)
}

impl TcpSock {
    /// Creates a nonblocking stream socket matching the address family of `addr`.
    ///
    /// The socket is configured with:
    /// - nonblocking I/O (either atomically via `SOCK_NONBLOCK` or via subsequent `fcntl`).
    /// - Close-on-exec (if `cloexec` is requested, via `SOCK_CLOEXEC` or `fcntl_setfd`).
    /// - `SO_NOSIGPIPE` on Apple targets to prevent process termination on broken pipes.
    /// - `TCP_NODELAY` to minimize request latency; failures to set nodelay are recorded
    ///   in `nodelay_ok` rather than aborting socket creation.
    /// - On Linux/Android loopback paths (including local host addresses routed via `lo`), a
    ///   best effort `TCP_MAXSEG` clamp derived from `SO_RCVBUF` to prevent TCP receive window
    ///   persist timer stalls.
    ///
    /// # Errors
    ///
    /// Returns an error if socket allocation or descriptor configuration fails.
    pub fn open(addr: &SocketAddr, cloexec: bool) -> Result<Opened, Errno> {
        let family = if addr.is_ipv4() {
            AddressFamily::INET
        } else {
            AddressFamily::INET6
        };
        let mut flags = SocketFlags::empty();
        if let Some(f) = NONBLOCK_FLAG {
            flags |= f;
        }
        if cloexec {
            if let Some(f) = CLOEXEC_FLAG {
                flags |= f;
            }
        }
        // Ownership is transferred immediately to ensure clean destruction on subsequent errors.
        let fd = rustix::net::socket_with(family, SocketType::STREAM, flags, None)?;
        if NONBLOCK_FLAG.is_none() {
            set_nonblocking(fd.as_fd())?;
        }
        if cloexec && CLOEXEC_FLAG.is_none() {
            rustix::io::fcntl_setfd(&fd, FdFlags::CLOEXEC)?;
        }
        // Apple targets lack MSG_NOSIGNAL; SO_NOSIGPIPE suppresses SIGPIPE for all socket
        // operations.
        #[cfg(target_vendor = "apple")]
        rustix::net::sockopt::set_socket_nosigpipe(&fd, true)?;
        // Emulate C client behavior: log TCP_NODELAY failures as warnings rather than failing the
        // connection.
        let nodelay_ok = rustix::net::sockopt::set_tcp_nodelay(&fd, true).is_ok();
        // On Linux loopback paths (including local host interfaces routed over `lo`), a single
        // 64 KiB segment can consume roughly half of the default receive buffer, preventing a fast
        // reader's free window from doubling and stalling the sender until its persist timer
        // fires. Remote paths negotiate standard interface MTUs and retain kernel defaults.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        if uses_loopback_device(addr) {
            limit_loopback_segments(&fd);
        }
        Ok(Opened {
            sock: Self { fd },
            nodelay_ok,
        })
    }

    /// Initiates an asynchronous nonblocking connection to the specified target address.
    ///
    /// Returns [`ConnectStart::Immediate`] if the connection completed synchronously (e.g.
    /// loopback), or [`ConnectStart::InProgress`] if the connection is proceeding asynchronously in
    /// the background.
    ///
    /// # Errors
    ///
    /// Returns an [`Errno`] if the connection attempt was rejected immediately by the operating
    /// system.
    pub fn start_connect(&self, addr: &SocketAddr) -> Result<ConnectStart, Errno> {
        match rustix::net::connect(&self.fd, addr) {
            Ok(()) => Ok(ConnectStart::Immediate),
            Err(Errno::INPROGRESS | Errno::WOULDBLOCK | Errno::INTR) => {
                Ok(ConnectStart::InProgress)
            }
            Err(e) => Err(e),
        }
    }

    /// Wraps a previously opened socket descriptor in a [`TcpSock`] instance.
    #[must_use]
    pub fn from_owned(fd: OwnedFd) -> Self {
        Self { fd }
    }

    /// Consumes this socket wrapper, returning the underlying owned file descriptor.
    #[must_use]
    pub fn into_owned(self) -> OwnedFd {
        self.fd
    }

    /// Returns the raw socket descriptor number exposed to the legacy C interface.
    #[must_use]
    pub fn raw(&self) -> RawSock {
        self.fd.as_raw_fd()
    }

    /// Transmits data bytes across the socket using flags suppressing SIGPIPE generation.
    ///
    /// # Errors
    ///
    /// Returns an error if the write fails; `EWOULDBLOCK` indicates the send buffer is saturated.
    pub fn send(&self, buf: &[u8]) -> Result<usize, Errno> {
        rustix::net::send(&self.fd, buf, SEND_FLAGS)
    }

    /// Receives incoming data bytes from the socket into the provided buffer, retrying upon
    /// `EINTR`.
    ///
    /// Returning `Ok(0)` with a non-empty buffer signifies remote socket closure (EOF).
    ///
    /// # Errors
    ///
    /// Returns an error if the read fails; `EWOULDBLOCK` indicates no data is currently available.
    pub fn recv(&self, buf: &mut [u8]) -> Result<usize, Errno> {
        loop {
            match rustix::net::recv(&self.fd, &mut *buf, RecvFlags::empty()) {
                Err(Errno::INTR) => {}
                Err(e) => return Err(e),
                Ok((n, _)) => return Ok(n),
            }
        }
    }

    /// Receives incoming socket data directly into the uninitialized spare capacity of `v`,
    /// advancing `v.len()` by the number of bytes read and retrying upon `EINTR`.
    ///
    /// Reads up to `v.capacity() - v.len()` bytes without reallocating or pre-zeroing memory.
    /// When `v` has no remaining spare capacity, delegates to [`Self::recv`] with an empty slice.
    /// Returning `Ok(0)` when spare capacity is non-empty indicates remote socket closure (EOF).
    ///
    /// # Errors
    ///
    /// Returns an error without modifying `v` if the read fails; `EWOULDBLOCK` indicates no data
    /// is currently available.
    pub fn recv_spare(&self, v: &mut Vec<u8>) -> Result<usize, Errno> {
        if v.capacity() == v.len() {
            return self.recv(&mut []);
        }
        loop {
            let spare = rustix::buffer::spare_capacity(&mut *v);
            match rustix::net::recv(&self.fd, spare, RecvFlags::empty()) {
                Err(Errno::INTR) => {}
                Err(e) => return Err(e),
                Ok((n, _)) => return Ok(n),
            }
        }
    }

    /// Queries the number of received bytes currently waiting unread in the socket receive buffer
    /// (`FIONREAD`) without reading or consuming any data.
    ///
    /// Returns `0` if the underlying `ioctl` query fails.
    pub(crate) fn unread_len(&self) -> u64 {
        rustix::io::ioctl_fionread(&self.fd).unwrap_or(0)
    }

    /// Queries and clears the socket's pending asynchronous error status (`SO_ERROR`).
    ///
    /// # Errors
    ///
    /// Returns any pending asynchronous error condition reported by the network stack.
    pub fn socket_error(&self) -> Result<(), Errno> {
        rustix::net::sockopt::socket_error(&self.fd)?
    }

    /// Retrieves the local socket address bound to this connection via `getsockname`.
    ///
    /// # Errors
    ///
    /// Returns an error if querying socket name fails or if the address family is unsupported.
    pub fn local_addr(&self) -> Result<SocketAddr, Errno> {
        let any = rustix::net::getsockname(&self.fd)?;
        SocketAddr::try_from(any).map_err(|_| Errno::AFNOSUPPORT)
    }

    /// Retrieves the remote peer address to which this socket is connected via `getpeername`.
    ///
    /// # Errors
    ///
    /// Returns an error if querying peer name fails, if the socket is unconnected (`ENOTCONN`),
    /// or if the address family is unsupported.
    pub fn peer_addr(&self) -> Result<SocketAddr, Errno> {
        let any = rustix::net::getpeername(&self.fd)?.ok_or(Errno::NOTCONN)?;
        SocketAddr::try_from(any).map_err(|_| Errno::AFNOSUPPORT)
    }
}

impl AsFd for TcpSock {
    fn as_fd(&self) -> BorrowedFd<'_> {
        self.fd.as_fd()
    }
}

/// Extracts the platform raw operating system error code from an [`Errno`].
#[must_use]
pub fn errno_value(e: Errno) -> i32 {
    e.raw_os_error()
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    #![allow(clippy::unwrap_used)]

    use std::net::{SocketAddr, UdpSocket};

    use super::{LOOPBACK_PACKET_BYTES, loopback_segment_limit, uses_loopback_device};

    /// Returns `true` if connecting to `s` routes over the loopback interface.
    fn local(s: &str) -> bool {
        uses_loopback_device(&s.parse().unwrap())
    }

    #[test]
    #[cfg_attr(miri, ignore = "Miri has no UDP sockets")]
    fn only_addresses_of_this_host_use_the_loopback_device() {
        assert!(local("127.0.0.1:9"));
        assert!(local("127.1.2.3:9"));
        assert!(local("0.0.0.0:9"));
        assert!(local("[::1]:9"));
        assert!(local("[::ffff:127.0.0.1]:9"));
        // RFC 5737 TEST-NET-1 documentation address is never assigned to a local interface.
        assert!(!local("192.0.2.1:9"));
        // The preferred outbound source IP selected for an external route belongs to this host and
        // routes via loopback.
        let udp = UdpSocket::bind("0.0.0.0:0").unwrap();
        if udp.connect("192.0.2.1:9").is_ok() {
            let own = udp.local_addr().unwrap().ip();
            assert!(uses_loopback_device(&SocketAddr::new(own, 9)), "{own}");
        }
    }

    #[test]
    fn the_segment_limit_is_at_most_a_sixth_of_the_receive_buffer() {
        assert_eq!(loopback_segment_limit(131_072), Some(21_700));
        assert_eq!(loopback_segment_limit(160_000), Some(21_700));
        assert_eq!(loopback_segment_limit(130_200), Some(21_700));
        assert_eq!(loopback_segment_limit(130_199), Some(16_275));
        assert_eq!(loopback_segment_limit(4_096), Some(678));
    }

    #[test]
    fn the_segment_limit_stays_in_the_accepted_range() {
        assert_eq!(loopback_segment_limit(0), Some(88));
        assert_eq!(loopback_segment_limit(500), Some(88));
        assert_eq!(loopback_segment_limit(196_601), Some(32_550));
        assert_eq!(loopback_segment_limit(196_602), Some(32_550));
        assert_eq!(loopback_segment_limit(392_975), Some(32_550));
    }

    /// Verifies for buffer sizes that get a limit that the aligned MSS stays within the clamped
    /// one-sixth limit and, except at the 88-byte floor, fits at least the fewest permitted
    /// segments.
    #[test]
    fn a_loopback_packet_is_filled_by_the_fewest_segments() {
        for rcvbuf in (0..392_976).step_by(37) {
            let limit = (rcvbuf / 6).clamp(88, 32_767);
            let mss = usize::try_from(loopback_segment_limit(rcvbuf).unwrap()).unwrap();
            assert!(mss <= limit, "rcvbuf {rcvbuf}: {mss} > {limit}");
            let fewest = LOOPBACK_PACKET_BYTES.div_ceil(limit);
            let held = LOOPBACK_PACKET_BYTES.checked_div(mss).unwrap();
            assert!(mss == 88 || held >= fewest, "rcvbuf {rcvbuf}: {mss}");
            // Verify that `mss` is maximal: either it equals the clamped limit, or a segment one
            // byte larger would fit fewer than `fewest` whole segments in a packet.
            let larger = LOOPBACK_PACKET_BYTES
                .checked_div(mss.checked_add(1).unwrap())
                .unwrap();
            assert!(
                mss == limit || larger < fewest,
                "rcvbuf {rcvbuf}: {mss} is not maximal"
            );
        }
    }

    #[test]
    fn a_large_receive_buffer_needs_no_limit() {
        assert_eq!(loopback_segment_limit(392_976), None);
        assert_eq!(loopback_segment_limit(usize::MAX), None);
    }
}
