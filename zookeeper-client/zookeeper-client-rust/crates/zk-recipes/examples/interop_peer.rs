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

//! Rust subprocess peer for the Apache Curator interoperability test suite.
//!
//! Invoked by the JUnit harness in `interop/curator` as:
//!
//! ```text
//! interop_peer <connect-string>
//! ```
//!
//! Connects a single client on a dedicated I/O thread, prints `READY` to stdout,
//! and processes newline-delimited commands from stdin in order, emitting one
//! response line per command to stdout and diagnostic traces to stderr:
//!
//! | Command                       | Reply                              |
//! |-------------------------------|------------------------------------|
//! | `LOCK <path> <ms> [<data>]`   | `OK <node-name>`, `TIMEOUT`, `ERR` |
//! | `TRYLOCK <path> [<data>]`     | `OK <node-name>`, `NONE`, `ERR`    |
//! | `UNLOCK`                      | `OK`, `ERR`                        |
//! | `DROP_LOCK`                   | `OK`, `ERR`                        |
//! | `ABANDON_LOCK <path> <ms>`    | `OK`, `ERR`                        |
//! | `LATCH <path> <id>`           | `OK <node-name>`, `ERR`            |
//! | `AWAIT_LEADER <timeout-ms>`   | `OK`, `TIMEOUT`, `ERR`             |
//! | `IS_LEADER`                   | `OK true`, `OK false`              |
//! | `PARTICIPANTS`                | `OK <id>:<0 or 1> ...`, `ERR`      |
//! | `CLOSE_LATCH`                 | `OK`, `ERR`                        |
//! | `SESSION`                     | `OK <id-hex> <password-hex>`       |
//! | `QUIT`                        | `OK`, then the process exits       |
//!
//! The optional `<data>` argument to `LOCK` and `TRYLOCK` specifies UTF-8 payload
//! bytes for the lock node (defaulting to empty, which Java reads as the holder ID).
//!
//! Error replies take the form `ERR <Debug>` (e.g., `ERR NoNode`), `ERR BadCommand <text>`
//! for malformed input, or `ERR BadState <text>` for invalid state transitions (such as
//! `UNLOCK` when no lock is held).
//!
//! At most one lock guard and one leader latch may be active at a time. When a `LOCK`
//! times out or `ABANDON_LOCK` cancels an in-flight `acquire` future, dropping the future
//! enqueues a background delete for the participant node. The peer then issues a barrier
//! read on the lock path so that, by FIFO request ordering, the deletion is committed on
//! the server before the reply is written to stdout.
//!
//! `DROP_LOCK` drops the active guard and replies immediately without a barrier, allowing
//! the Java harness to sever the connection mid-delete and observe background guaranteed delete
//! recovery upon reconnection.
//!
//! `ABANDON_LOCK` requires the lock to be contended; if acquisition succeeds before the
//! timeout elapses, the guard is dropped and `ERR Acquired <node-name>` is returned.
//!
//! See `interop/curator/README.md` for protocol details and test scenarios.

#![allow(clippy::disallowed_macros)]
// This test peer awaits many recipe futures in one command loop. Its stack size does not matter.
#![allow(clippy::large_stack_frames)]

use std::io::{BufRead, Write};
use std::time::Duration;

use apache_zookeeper::{Client, Error, Runtime, ThreadOptions, ZPath};
use apache_zookeeper_recipes::{DistributedLock, Latch, LeaderLatch, LockGuard};

/// Session timeout for the peer client.
const SESSION_TIMEOUT: Duration = Duration::from_secs(10);

/// Substring marker identifying leader latch sequential nodes.
const LATCH_MARKER: &str = "-latch-";

/// Parsed stdin command.
enum Command {
    Lock {
        path: String,
        timeout: Duration,
        data: Vec<u8>,
    },
    TryLock {
        path: String,
        data: Vec<u8>,
    },
    Unlock,
    DropLock,
    AbandonLock {
        path: String,
        after: Duration,
    },
    Latch {
        path: String,
        id: String,
    },
    AwaitLeader {
        timeout: Duration,
    },
    IsLeader,
    Participants,
    CloseLatch,
    Session,
    Quit,
}

impl Command {
    /// Parses a single command line, returning a formatted `ERR` response on failure.
    fn parse(line: &str) -> Result<Self, String> {
        let words: Vec<&str> = line.split_whitespace().collect();
        let bad = || format!("ERR BadCommand {line}");
        let ms = |w: &str| {
            w.parse::<u64>()
                .map(Duration::from_millis)
                .map_err(|_| bad())
        };
        match words.as_slice() {
            ["LOCK", path, t, data @ ..] if data.len() <= 1 => Ok(Self::Lock {
                path: (*path).to_owned(),
                timeout: ms(t)?,
                data: node_data(data),
            }),
            ["TRYLOCK", path, data @ ..] if data.len() <= 1 => Ok(Self::TryLock {
                path: (*path).to_owned(),
                data: node_data(data),
            }),
            ["UNLOCK"] => Ok(Self::Unlock),
            ["DROP_LOCK"] => Ok(Self::DropLock),
            ["ABANDON_LOCK", path, t] => Ok(Self::AbandonLock {
                path: (*path).to_owned(),
                after: ms(t)?,
            }),
            ["LATCH", path, id] if !id.contains(':') => Ok(Self::Latch {
                path: (*path).to_owned(),
                id: (*id).to_owned(),
            }),
            ["AWAIT_LEADER", t] => Ok(Self::AwaitLeader { timeout: ms(t)? }),
            ["IS_LEADER"] => Ok(Self::IsLeader),
            ["PARTICIPANTS"] => Ok(Self::Participants),
            ["CLOSE_LATCH"] => Ok(Self::CloseLatch),
            ["SESSION"] => Ok(Self::Session),
            ["QUIT"] => Ok(Self::Quit),
            _ => Err(bad()),
        }
    }
}

/// Active state of the interoperability peer across commands.
struct Peer {
    client: Client,
    /// Currently held lock guard, if any.
    guard: Option<LockGuard>,
    /// Currently running leader latch, if any.
    latch: Option<Latch>,
}

impl Peer {
    /// Executes `cmd` and returns the formatted response line.
    async fn run(&mut self, cmd: Command) -> String {
        let reply = match cmd {
            Command::Lock {
                path,
                timeout,
                data,
            } => self.lock(&path, timeout, data).await,
            Command::TryLock { path, data } => self.try_lock(&path, data).await,
            Command::Unlock => self.unlock().await,
            Command::DropLock => self.drop_lock(),
            Command::AbandonLock { path, after } => self.abandon_lock(&path, after).await,
            Command::Latch { path, id } => self.start_latch(&path, id).await,
            Command::AwaitLeader { timeout } => self.await_leader(timeout).await,
            Command::IsLeader => Ok(format!(
                "OK {}",
                self.latch.as_ref().is_some_and(Latch::is_leader)
            )),
            Command::Participants => self.participants().await,
            Command::CloseLatch => self.close_latch().await,
            Command::Session => {
                let (id, pw) = self.client.session();
                Ok(format!("OK {:x} {}", id.0, hex(pw.as_bytes())))
            }
            Command::Quit => {
                // Enqueue node deletions before closing the client session.
                self.guard = None;
                self.latch = None;
                Ok("OK".to_owned())
            }
        };
        reply.unwrap_or_else(|e| e)
    }

    /// Acquires the lock at `path`, waiting up to `timeout`.
    async fn lock(
        &mut self,
        path: &str,
        timeout: Duration,
        data: Vec<u8>,
    ) -> Result<String, String> {
        self.no_guard()?;
        let lock = DistributedLock::new(&self.client, path)
            .map_err(err)?
            .node_data(data);
        match tokio::time::timeout(timeout, lock.acquire()).await {
            Ok(Ok(guard)) => Ok(format!("OK {}", self.hold(guard))),
            Ok(Err(e)) => Err(err(e)),
            Err(_) => {
                self.barrier(path).await?;
                Ok("TIMEOUT".to_owned())
            }
        }
    }

    /// Attempts nonblocking lock acquisition at `path`.
    async fn try_lock(&mut self, path: &str, data: Vec<u8>) -> Result<String, String> {
        self.no_guard()?;
        let lock = DistributedLock::new(&self.client, path)
            .map_err(err)?
            .node_data(data);
        match lock.try_acquire().await.map_err(err)? {
            Some(guard) => Ok(format!("OK {}", self.hold(guard))),
            None => Ok("NONE".to_owned()),
        }
    }

    /// Explicitly releases the held lock and awaits server confirmation.
    async fn unlock(&mut self) -> Result<String, String> {
        let guard = self
            .guard
            .take()
            .ok_or_else(|| "ERR BadState no lock held".to_owned())?;
        guard.release().await.map_err(err)?;
        Ok("OK".to_owned())
    }

    /// Drops the held lock guard without awaiting completion of the background delete.
    fn drop_lock(&mut self) -> Result<String, String> {
        self.guard
            .take()
            .map(drop)
            .ok_or_else(|| "ERR BadState no lock held".to_owned())?;
        Ok("OK".to_owned())
    }

    /// Starts lock acquisition and cancels the future after `after`.
    async fn abandon_lock(&mut self, path: &str, after: Duration) -> Result<String, String> {
        let lock = DistributedLock::new(&self.client, path).map_err(err)?;
        let outcome = tokio::time::timeout(after, lock.acquire()).await;
        // Flush any background delete queued by dropping the acquire future or guard.
        self.barrier(path).await?;
        match outcome {
            Err(_) => Ok("OK".to_owned()),
            Ok(Ok(guard)) => {
                let name = node_name(guard.node());
                drop(guard);
                self.barrier(path).await?;
                Err(format!("ERR Acquired {name}"))
            }
            Ok(Err(e)) => Err(err(e)),
        }
    }

    /// Starts a leader latch at `path` with participant ID `id`.
    async fn start_latch(&mut self, path: &str, id: String) -> Result<String, String> {
        if self.latch.is_some() {
            return Err("ERR BadState a latch runs".to_owned());
        }
        let latch = LeaderLatch::new(&self.client, path, id.into_bytes())
            .map_err(err)?
            .start()
            .await
            .map_err(err)?;
        self.latch = Some(latch);
        let name = self.latch_node(path).await?;
        Ok(format!("OK {name}"))
    }

    /// Waits up to `timeout` for the active latch to acquire leadership.
    async fn await_leader(&mut self, timeout: Duration) -> Result<String, String> {
        let latch = self.latch_mut()?;
        match tokio::time::timeout(timeout, latch.await_leadership()).await {
            Ok(Ok(())) => Ok("OK".to_owned()),
            Ok(Err(e)) => Err(err(e)),
            Err(_) => Ok("TIMEOUT".to_owned()),
        }
    }

    /// Queries the ordered participant list and leadership flags on the active latch.
    async fn participants(&mut self) -> Result<String, String> {
        let all = self.latch_mut()?.participants().await.map_err(err)?;
        let mut line = "OK".to_owned();
        for p in all {
            line.push(' ');
            line.push_str(&String::from_utf8_lossy(&p.id));
            line.push_str(if p.is_leader { ":1" } else { ":0" });
        }
        Ok(line)
    }

    /// Closes the active leader latch and awaits deletion of its participant node.
    async fn close_latch(&mut self) -> Result<String, String> {
        let latch = self
            .latch
            .take()
            .ok_or_else(|| "ERR BadState no latch".to_owned())?;
        latch.close().await.map_err(err)?;
        Ok("OK".to_owned())
    }

    /// Stores `guard` and returns its child node name.
    fn hold(&mut self, guard: LockGuard) -> String {
        let name = node_name(guard.node());
        self.guard = Some(guard);
        name
    }

    /// Rejects lock acquisition if a guard is already held (`DistributedLock` is non-reentrant).
    fn no_guard(&self) -> Result<(), String> {
        match self.guard {
            Some(_) => Err("ERR BadState a lock is held".to_owned()),
            None => Ok(()),
        }
    }

    /// Returns a mutable reference to the active latch, or a `BadState` error.
    fn latch_mut(&mut self) -> Result<&mut Latch, String> {
        self.latch
            .as_mut()
            .ok_or_else(|| "ERR BadState no latch".to_owned())
    }

    /// Issues an `exists` read on `path` to ensure prior asynchronous deletes have been
    /// processed by the server via FIFO request ordering.
    async fn barrier(&self, path: &str) -> Result<(), String> {
        self.client.exists(path).await.map(drop).map_err(err)
    }

    /// Resolves the ephemeral sequential node name created by this session under `path`.
    ///
    /// Because [`Latch`] does not expose its node path directly, this queries session
    /// ephemerals via `GetEphemerals` (requires ZooKeeper 3.6+).
    async fn latch_node(&self, path: &str) -> Result<String, String> {
        let parent = ZPath::new(path).map_err(|e| err(Error::from(e)))?;
        let nodes = self.client.get_ephemerals(parent).await.map_err(err)?;
        nodes
            .iter()
            .map(|n| n.as_path())
            .find(|n| n.parent() == Some(parent) && n.name().is_some_and(is_latch_name))
            .map(node_name)
            .ok_or_else(|| "ERR NoNode".to_owned())
    }
}

/// Returns `true` if `name` matches the protected latch naming format (`_c_<uuid>-latch-<seq>`).
fn is_latch_name(name: &str) -> bool {
    name.starts_with("_c_") && name.contains(LATCH_MARKER)
}

/// Extracts optional UTF-8 node payload bytes from command tokens.
fn node_data(words: &[&str]) -> Vec<u8> {
    words
        .first()
        .map(|w| w.as_bytes().to_vec())
        .unwrap_or_default()
}

/// Returns the final path segment of `path`.
fn node_name(path: ZPath<'_>) -> String {
    path.name().unwrap_or_default().to_owned()
}

/// Formats a client [`Error`] as an `ERR` protocol line.
fn err(e: Error) -> String {
    format!("ERR {e:?}")
}

/// Encodes `bytes` as lowercase hexadecimal.
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Writes a response line to `out` and flushes stdout immediately.
fn reply(out: &mut impl Write, line: &str) -> std::io::Result<()> {
    writeln!(out, "{line}")?;
    out.flush()
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let connect = std::env::args()
        .nth(1)
        .ok_or("usage: interop_peer <connect-string>")?;
    let client = Client::builder(connect)
        .session_timeout(SESSION_TIMEOUT)
        .runtime(Runtime::Thread(ThreadOptions::default()))
        .connect()
        .await?;
    let mut peer = Peer {
        client,
        guard: None,
        latch: None,
    };
    let mut out = std::io::stdout().lock();
    reply(&mut out, "READY")?;
    // Stdin reads block the main thread while the background client I/O thread
    // maintains the ZooKeeper session.
    for line in std::io::stdin().lock().lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        let (text, quit) = match Command::parse(&line) {
            Ok(cmd) => {
                let quit = matches!(cmd, Command::Quit);
                (peer.run(cmd).await, quit)
            }
            Err(text) => (text, false),
        };
        // Log command execution to stderr to aid debugging when JUnit tests fail.
        writeln!(std::io::stderr().lock(), "interop_peer: {line} -> {text}")?;
        reply(&mut out, &text)?;
        if quit {
            break;
        }
    }
    peer.client.close().await?;
    Ok(())
}
