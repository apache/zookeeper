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

//! Programmable TCP proxy intermediary simulating network partitions and transit faults.
//!
//! Shared across client and recipe test suites to introduce deterministic connection severing,
//! unidirectional traffic suppression, arbitrary latency injection, and socket stall conditions
//! against live ZooKeeper server instances.

use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Programmable loopback TCP proxy mediating client-server communication channels.
///
/// Supports dynamic state transitions across bidirectional forwarding, abrupt connection
/// termination, silent byte discard, response filtering, and synthetic transmission delays
/// without requiring server side restarts.
pub struct Proxy {
    addr: std::net::SocketAddr,
    inner: Arc<ProxyInner>,
}

struct ProxyInner {
    target: std::net::SocketAddr,
    mode: AtomicU8,
    /// Delay duration in milliseconds injected before forwarding each data buffer.
    delay_ms: AtomicU64,
    stop: AtomicBool,
    conns: Mutex<Vec<std::net::TcpStream>>,
}

/// Normal bidirectional proxy operation forwarding all traffic.
const OPEN: u8 = 0;
/// Partition simulation: closes active sockets and immediately rejects subsequent connections.
const CUT: u8 = 1;
/// Connection stall simulation: preserves socket handles while silently discarding all
/// transferred bytes.
const HOLD: u8 = 2;
/// Asymmetric partition: delivers client requests while silently dropping inbound server
/// responses.
const MUTE: u8 = 3;

impl Proxy {
    /// Binds an ephemeral loopback listener forwarding traffic to the designated target port.
    pub fn start(target_port: u16) -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("proxy bind");
        let addr = listener.local_addr().expect("proxy addr");
        let inner = Arc::new(ProxyInner {
            target: std::net::SocketAddr::from(([127, 0, 0, 1], target_port)),
            mode: AtomicU8::new(OPEN),
            delay_ms: AtomicU64::new(0),
            stop: AtomicBool::new(false),
            conns: Mutex::new(Vec::new()),
        });
        let i2 = Arc::clone(&inner);
        std::thread::spawn(move || accept_loop(&listener, &i2));
        Self { addr, inner }
    }

    /// Formats the proxy's local listening socket as a client connection string.
    pub fn connect_string(&self) -> String {
        self.addr.to_string()
    }

    /// Simulates sudden network severing by terminating active sockets and rejecting incoming
    /// connections.
    pub fn cut(&self) {
        self.inner.mode.store(CUT, Ordering::SeqCst);
        self.inner.close_all();
    }

    /// Simulates socket starvation by retaining established connections while discarding all
    /// transit bytes.
    pub fn hold(&self) {
        self.inner.mode.store(HOLD, Ordering::SeqCst);
    }

    /// Simulates unidirectional partition by permitting outbound requests while discarding
    /// server replies.
    pub fn mute_replies(&self) {
        self.inner.mode.store(MUTE, Ordering::SeqCst);
    }

    /// Restores bidirectional forwarding across the proxy channel.
    pub fn restore(&self) {
        self.inner.mode.store(OPEN, Ordering::SeqCst);
    }

    /// Injects synthetic transmission latency before forwarding data frames in either direction.
    pub fn delay(&self, d: Duration) {
        let ms = u64::try_from(d.as_millis()).unwrap_or(u64::MAX);
        self.inner.delay_ms.store(ms, Ordering::SeqCst);
    }
}

impl Drop for Proxy {
    fn drop(&mut self) {
        self.inner.stop.store(true, Ordering::SeqCst);
        self.inner.close_all();
        // Synthetic connection unblocks the listener thread from blocking accept calls.
        let _ = std::net::TcpStream::connect(self.addr);
    }
}

impl ProxyInner {
    fn close_all(&self) {
        let conns = std::mem::take(&mut *self.conns.lock().unwrap_or_else(|e| e.into_inner()));
        for c in conns {
            let _ = c.shutdown(std::net::Shutdown::Both);
        }
    }

    /// Evaluates traffic filtering policy based on the active proxy mode and transfer direction.
    fn drops(&self, from_server: bool) -> bool {
        match self.mode.load(Ordering::SeqCst) {
            CUT | HOLD => true,
            MUTE => from_server,
            _ => false,
        }
    }
}

fn accept_loop(listener: &std::net::TcpListener, inner: &Arc<ProxyInner>) {
    for client in listener.incoming() {
        if inner.stop.load(Ordering::SeqCst) {
            return;
        }
        let Ok(client) = client else { continue };
        match inner.mode.load(Ordering::SeqCst) {
            CUT => {
                let _ = client.shutdown(std::net::Shutdown::Both);
                continue;
            }
            HOLD => {
                inner
                    .conns
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(client);
                continue;
            }
            _ => {}
        }
        let Ok(server) = std::net::TcpStream::connect(inner.target) else {
            let _ = client.shutdown(std::net::Shutdown::Both);
            continue;
        };
        let _ = client.set_nodelay(true);
        let _ = server.set_nodelay(true);
        let pair = (
            client.try_clone(),
            server.try_clone(),
            client.try_clone(),
            server.try_clone(),
        );
        let (Ok(c1), Ok(s1), Ok(c2), Ok(s2)) = pair else {
            continue;
        };
        {
            let mut conns = inner.conns.lock().unwrap_or_else(|e| e.into_inner());
            conns.push(c2);
            conns.push(s2);
        }
        let (i1, i2) = (Arc::clone(inner), Arc::clone(inner));
        std::thread::spawn(move || pump(client, server, &i1, false));
        std::thread::spawn(move || pump(s1, c1, &i2, true));
    }
}

/// Unidirectional byte transfer pump enforcing active drop policies and transmission delays.
fn pump(
    mut from: std::net::TcpStream,
    mut to: std::net::TcpStream,
    inner: &ProxyInner,
    from_server: bool,
) {
    use std::io::{Read, Write};
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        let n = match from.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        if inner.drops(from_server) {
            continue;
        }
        let delay = inner.delay_ms.load(Ordering::SeqCst);
        if delay > 0 {
            std::thread::sleep(Duration::from_millis(delay));
        }
        if to.write_all(&buf[..n]).is_err() {
            break;
        }
    }
    let _ = from.shutdown(std::net::Shutdown::Both);
    let _ = to.shutdown(std::net::Shutdown::Both);
}
