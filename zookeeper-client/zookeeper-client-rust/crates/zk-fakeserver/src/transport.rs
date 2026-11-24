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

//! Transport abstraction decoupling network stream I/O from the protocol framing loop.
//!
//! The server connection loop performs all network operations through the [`Transport`] trait.
//! Plain TCP is the default transport, while TLS acceptors wrap accepted sockets in encrypted
//! session streams before handing them to the connection loop, keeping protocol parsing and
//! dispatch transport-agnostic.

use std::fmt;
use std::io::{self, Read, Write};
use std::net::TcpStream;

/// server side byte stream established over an accepted TCP socket.
pub trait Transport: Read + Write + Send {
    /// Borrows the underlying TCP socket so the connection loop can configure read/write timeouts,
    /// inspect peer addresses, or initiate connection shutdown.
    fn tcp(&self) -> &TcpStream;

    /// Concludes the transport session prior to socket shutdown.
    ///
    /// `graceful` is `true` for normal connection closures (such as a client close request) and
    /// `false` for abrupt terminations or administrative kills. Transports requiring protocol
    /// level teardown perform it here. Plain TCP requires no action.
    fn finish(&mut self, _graceful: bool) {}
}

impl Transport for TcpStream {
    fn tcp(&self) -> &TcpStream {
        self
    }
}

/// Factory converting an accepted [`TcpStream`] into an initialized [`Transport`].
///
/// Handshake negotiation runs on dedicated connection worker threads rather than the listener
/// thread so slow handshakes (such as TLS negotiation) do not block incoming connections.
pub trait Acceptor: Send + Sync + fmt::Debug {
    /// Wraps `tcp` into a transport stream, or returns an I/O error if the transport handshake
    /// fails.
    fn accept(&self, tcp: TcpStream) -> io::Result<Box<dyn Transport>>;
}

/// Default transport acceptor that returns raw TCP sockets without encryption.
#[derive(Debug, Clone, Copy, Default)]
pub struct PlainAcceptor;

impl Acceptor for PlainAcceptor {
    fn accept(&self, tcp: TcpStream) -> io::Result<Box<dyn Transport>> {
        Ok(Box::new(tcp))
    }
}
