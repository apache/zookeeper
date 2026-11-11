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

//! Synchronous ZooKeeper wire protocol client for test inspection and protocol fuzzing.
//!
//! Operates directly at the Jute frame boundary without automated reconnect loops, heartbeat
//! threads, or client side watch registration tables. Test harnesses retain direct visibility
//! into each frame transmitted or received over the socket.

use std::collections::VecDeque;
use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use zk_jute::handshake::{ConnectRequest, ConnectResponse, PASSWD_LEN, decode_connect_response};
use zk_jute::proto::{ReplyHeader, RequestHeader, WatcherEvent};
use zk_jute::{DecodeError, FrameDecoder, Pushed, Reader, Record, begin_frame, finish_frame};

/// Default network timeout applied when awaiting inbound protocol frames.
pub const DEFAULT_WAIT: Duration = Duration::from_secs(5);

/// Parsed server response frame containing a reply header and raw payload body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawReply {
    /// Correlation identifier matching the client request `xid`.
    pub xid: i32,
    /// ZooKeeper transaction identifier (`zxid`) recorded in the reply header.
    pub zxid: i64,
    /// ZooKeeper status code (0 indicates success; negative values indicate protocol errors).
    pub err: i32,
    /// Raw payload bytes following the [`ReplyHeader`].
    pub body: Vec<u8>,
}

impl RawReply {
    /// Deserializes the reply body into a typed Jute response record.
    ///
    /// # Errors
    ///
    /// Returns a [`DecodeError`] if the payload buffer is malformed or fails schema validation.
    pub fn decode<T: Record>(&self) -> Result<T, DecodeError> {
        T::from_bytes(&self.body)
    }

    /// Parses a wire-level response frame buffer into a [`RawReply`].
    ///
    /// # Errors
    ///
    /// Returns a [`DecodeError`] if the buffer is shorter than a 16-byte [`ReplyHeader`].
    pub fn parse(frame: &[u8]) -> Result<Self, DecodeError> {
        let mut r = Reader::new(frame);
        let h = ReplyHeader::decode(&mut r)?;
        Ok(Self {
            xid: h.xid,
            zxid: h.zxid,
            err: h.err,
            body: r.rest().to_vec(),
        })
    }
}

/// Asynchronous watch notification received from the server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawEvent {
    /// Cluster transaction identifier (`zxid`) recorded in the notification header.
    pub zxid: i64,
    /// Numeric watch event type (such as `NODE_CREATED`, `NODE_DELETED`, or `NODE_DATA_CHANGED`).
    pub event_type: i32,
    /// Client session state (`SYNC_CONNECTED`, `CONNECTED_READ_ONLY`, or `EXPIRED`).
    pub state: i32,
    /// Znode path associated with the watch notification.
    pub path: String,
}

/// Synchronous test client managing a raw TCP connection to a ZooKeeper server endpoint.
#[derive(Debug)]
pub struct RawClient {
    stream: TcpStream,
    dec: FrameDecoder,
    frames: VecDeque<Vec<u8>>,
    events: VecDeque<RawEvent>,
    replies: VecDeque<RawReply>,
    next_xid: i32,
    eof: bool,
}

fn timed_out() -> io::Error {
    io::Error::new(ErrorKind::TimedOut, "no frame before the timeout")
}

fn eof() -> io::Error {
    io::Error::new(ErrorKind::UnexpectedEof, "the server closed the connection")
}

impl RawClient {
    /// Establishes a TCP connection to `addr` without performing the protocol handshake.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the connection attempt fails or exceeds [`DEFAULT_WAIT`].
    pub fn connect(addr: SocketAddr) -> io::Result<Self> {
        let stream = TcpStream::connect_timeout(&addr, DEFAULT_WAIT)?;
        stream.set_nodelay(true)?;
        Ok(Self {
            stream,
            dec: FrameDecoder::new(None),
            frames: VecDeque::new(),
            events: VecDeque::new(),
            replies: VecDeque::new(),
            next_xid: 1,
            eof: false,
        })
    }

    /// Establishes a TCP connection and negotiates a new ZooKeeper session with default parameters.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the connection fails, the handshake times out, or the server
    /// closes the socket before returning a handshake response.
    pub fn open(addr: SocketAddr, timeout_ms: i32) -> io::Result<(Self, ConnectResponse)> {
        Self::open_with(
            addr,
            &ConnectRequest {
                time_out: timeout_ms,
                ..ConnectRequest::default()
            },
        )
    }

    /// Establishes a TCP connection and negotiates a session using the given [`ConnectRequest`].
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] on network failure or premature connection closure.
    pub fn open_with(
        addr: SocketAddr,
        req: &ConnectRequest,
    ) -> io::Result<(Self, ConnectResponse)> {
        let mut c = Self::connect(addr)?;
        let resp = c.handshake(req)?;
        Ok((c, resp))
    }

    /// Re-attaches an existing session on a new TCP connection using prior session credentials.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] under the same conditions as [`RawClient::open`].
    pub fn resume(
        addr: SocketAddr,
        session_id: i64,
        passwd: [u8; PASSWD_LEN],
        timeout_ms: i32,
        last_zxid_seen: i64,
    ) -> io::Result<(Self, ConnectResponse)> {
        Self::open_with(
            addr,
            &ConnectRequest {
                time_out: timeout_ms,
                session_id,
                passwd,
                last_zxid_seen,
                ..ConnectRequest::default()
            },
        )
    }

    /// Sends a [`ConnectRequest`] frame and reads the server's [`ConnectResponse`].
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] on I/O failure, unexpected EOF, or malformed response framing.
    pub fn handshake(&mut self, req: &ConnectRequest) -> io::Result<ConnectResponse> {
        self.send_raw(&req.encode_frame())?;
        let body = self.read_frame(DEFAULT_WAIT)?;
        decode_connect_response(&body).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))
    }

    /// Borrows the underlying TCP stream for socket option inspection or timeout configuration.
    #[must_use]
    pub const fn stream(&self) -> &TcpStream {
        &self.stream
    }

    /// Writes an unframed byte slice directly to the underlying socket stream.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the socket write or flush fails.
    pub fn send_raw(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.stream.write_all(bytes)?;
        self.stream.flush()
    }

    /// Allocates and returns the next sequential request correlation identifier (`xid`).
    pub fn take_xid(&mut self) -> i32 {
        let x = self.next_xid;
        self.next_xid = self.next_xid.wrapping_add(1);
        x
    }

    /// Encodes and transmits a length-prefixed request frame with the given `xid`, `op`, and body.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if socket transmission fails.
    pub fn send(&mut self, xid: i32, op: i32, body: &[u8]) -> io::Result<()> {
        let mut out = Vec::new();
        let start = begin_frame(&mut out);
        RequestHeader { xid, r#type: op }.encode(&mut out);
        out.extend_from_slice(body);
        finish_frame(&mut out, start);
        self.send_raw(&out)
    }

    /// Serializes a Jute record and sends it with a newly allocated `xid`.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if socket transmission fails.
    pub fn send_record<R: Record>(&mut self, op: i32, req: &R) -> io::Result<i32> {
        let xid = self.take_xid();
        self.send(xid, op, &req.to_bytes())?;
        Ok(xid)
    }

    /// Reads and returns the payload bytes of the next inbound protocol frame within `timeout`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::TimedOut`](io::ErrorKind::TimedOut) if no frame arrives before
    /// `timeout`, [`ErrorKind::UnexpectedEof`](io::ErrorKind::UnexpectedEof) on connection close,
    /// or [`ErrorKind::InvalidData`](io::ErrorKind::InvalidData) if the frame length is invalid.
    pub fn read_frame(&mut self, timeout: Duration) -> io::Result<Vec<u8>> {
        let deadline = Instant::now().checked_add(timeout);
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            if let Some(f) = self.frames.pop_front() {
                return Ok(f);
            }
            if self.eof {
                return Err(eof());
            }
            let left = deadline
                .and_then(|d| d.checked_duration_since(Instant::now()))
                .filter(|d| !d.is_zero())
                .ok_or_else(timed_out)?;
            self.stream.set_read_timeout(Some(left))?;
            match self.stream.read(&mut buf) {
                Ok(0) => self.eof = true,
                Ok(n) => self.feed(buf.get(..n).unwrap_or_default())?,
                Err(e)
                    if matches!(
                        e.kind(),
                        ErrorKind::WouldBlock | ErrorKind::TimedOut | ErrorKind::Interrupted
                    ) => {}
                Err(e) if e.kind() == ErrorKind::ConnectionReset => self.eof = true,
                Err(e) => return Err(e),
            }
        }
    }

    fn feed(&mut self, data: &[u8]) -> io::Result<()> {
        let mut data = data;
        while !data.is_empty() {
            let (n, p) = self.dec.push(data);
            data = data.get(n..).unwrap_or_default();
            match p {
                Pushed::Frame(f) => self.frames.push_back(f),
                Pushed::NeedMore => {}
                Pushed::Error(e) => return Err(io::Error::new(ErrorKind::InvalidData, e)),
            }
        }
        Ok(())
    }

    /// Reads one inbound frame and routes it to either the watch event queue or the reply queue.
    fn read_one(&mut self, timeout: Duration) -> io::Result<()> {
        let f = self.read_frame(timeout)?;
        let r = RawReply::parse(&f).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))?;
        if r.xid == zk_jute::xid::WATCHER_EVENT {
            let ev = WatcherEvent::from_bytes(&r.body)
                .map_err(|e| io::Error::new(ErrorKind::InvalidData, e))?;
            self.events.push_back(RawEvent {
                zxid: r.zxid,
                event_type: ev.r#type,
                state: ev.state,
                path: ev.path.as_str().unwrap_or("").to_owned(),
            });
        } else {
            self.replies.push_back(r);
        }
        Ok(())
    }

    /// Returns the next operational reply, buffering any interleaved watch notifications.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] under the same conditions as [`RawClient::read_frame`].
    pub fn read_reply(&mut self, timeout: Duration) -> io::Result<RawReply> {
        loop {
            if let Some(r) = self.replies.pop_front() {
                return Ok(r);
            }
            self.read_one(timeout)?;
        }
    }

    /// Sends a typed request record and awaits the corresponding reply.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] on I/O failure or if the reply `xid` does not match the request.
    pub fn call<R: Record>(&mut self, op: i32, req: &R) -> io::Result<RawReply> {
        let xid = self.send_record(op, req)?;
        self.expect_reply(xid)
    }

    /// Sends an empty-body request with `op` and awaits the corresponding reply.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] under the same conditions as [`RawClient::call`].
    pub fn call_empty(&mut self, op: i32) -> io::Result<RawReply> {
        let xid = self.take_xid();
        self.send(xid, op, &[])?;
        self.expect_reply(xid)
    }

    /// Sends a heartbeat ping (`xid = -2`, `op = PING`) and awaits the ping reply.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] under the same conditions as [`RawClient::read_reply`].
    pub fn ping(&mut self) -> io::Result<RawReply> {
        self.send(zk_jute::xid::PING, zk_jute::opcode::PING, &[])?;
        self.read_reply(DEFAULT_WAIT)
    }

    fn expect_reply(&mut self, xid: i32) -> io::Result<RawReply> {
        let r = self.read_reply(DEFAULT_WAIT)?;
        if r.xid != xid {
            return Err(io::Error::new(
                ErrorKind::InvalidData,
                format!("reply xid {} for request xid {xid}", r.xid),
            ));
        }
        Ok(r)
    }

    /// Returns the next asynchronous watch notification, buffering any interleaved replies.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] under the same conditions as [`RawClient::read_frame`].
    pub fn next_event(&mut self, timeout: Duration) -> io::Result<RawEvent> {
        loop {
            if let Some(e) = self.events.pop_front() {
                return Ok(e);
            }
            self.read_one(timeout)?;
        }
    }

    /// Drains and returns all buffered watch notifications.
    pub fn take_events(&mut self) -> Vec<RawEvent> {
        self.events.drain(..).collect()
    }

    /// Waits up to `timeout` for the server to close the connection, returning `true` on EOF.
    pub fn wait_closed(&mut self, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout);
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            if self.eof {
                return true;
            }
            let Some(left) = deadline
                .and_then(|d| d.checked_duration_since(Instant::now()))
                .filter(|d| !d.is_zero())
            else {
                return false;
            };
            if self.stream.set_read_timeout(Some(left)).is_err() {
                return true;
            }
            match self.stream.read(&mut buf) {
                Ok(0) => self.eof = true,
                Ok(n) => {
                    if self.feed(buf.get(..n).unwrap_or_default()).is_err() {
                        return true;
                    }
                }
                Err(e)
                    if matches!(
                        e.kind(),
                        ErrorKind::WouldBlock | ErrorKind::TimedOut | ErrorKind::Interrupted
                    ) => {}
                Err(_) => self.eof = true,
            }
        }
    }

    /// Shuts down both directions of the underlying TCP socket.
    pub fn shutdown(&self) {
        drop(self.stream.shutdown(Shutdown::Both));
    }
}
