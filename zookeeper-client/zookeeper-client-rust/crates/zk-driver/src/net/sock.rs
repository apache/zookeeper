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

/// Configures the provided file descriptor for nonblocking I/O via `fcntl(F_SETFL, O_NONBLOCK)`.
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
