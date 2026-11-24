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

//! Subtree synchronization engine and parallel breadth-first tree traversal.
//!
//! This module implements parallel tree traversal for populating initial [`TreeCache`] state
//! and performing complete resynchronizations following connection interruptions. Traversal
//! proceeds in breadth-first order starting from the root znode. For each discovered znode,
//! data queries (`get_data` or `exists`) and directory enumeration (`get_children`) are
//! dispatched concurrently in pipelined batches.
//!
//! # Flow Control and Queue Protection
//!
//! Traversal limits maximum concurrent node evaluations to `WINDOW` (256 nodes, translating to
//! at most 512 in-flight ZooKeeper requests). This sliding window prevents unbounded memory
//! growth and avoids overwhelming the client's internal message dispatch buffers
//! (`dispatch_queue_capacity`).
//!
//! All in-flight requests and partial traversal queues are encapsulated within the [`Read`] state
//! machine. Polling drives progress incrementally, guaranteeing that cancellation or future
//! drops never discard in-flight server responses or leave the traversal in an inconsistent state.
//!
//! # Handling Concurrent Znode Deletions
//!
//! If a znode is deleted concurrently while traversal is in flight, the query resolves with
//! `Error::NoNode`. The engine gracefully prunes the vanished node and its descendants from the
//! synchronized snapshot. Concurrent watch notifications registered during synchronization
//! subsequently reconcile any transient discrepancies.
//!
//! # Per-Node Read Errors
//!
//! A znode's data or children query can fail with an error that is neither a connection-loss
//! error nor a terminal session error. The engine then sets that znode and its descendants
//! aside without queuing its children, and continues to traverse the remaining tree. Thus a
//! single unreadable znode does not fail the entire traversal. Connection-loss and terminal
//! session errors abort the traversal with that error.
//!
//! The engine sorts these errors into two classes:
//!
//! - **Transient** errors are `Error::ThrottledOp` from the server and
//!   `Error::TooManyPendingRequests` from the local request queue. They report load, not the
//!   state of the znode, so the engine reads the znode again. When the queue of a round is
//!   empty and its requests are complete, the engine starts a new round. That round traverses
//!   again from each znode that a transient error set aside, so the znode and its whole subtree
//!   enter the snapshot. The engine waits for room in the client request queue before a round
//!   that follows `TooManyPendingRequests`. The server sends `ThrottledOp` only after its
//!   throttle wait, so each round has a natural delay. At most `ROUNDS` retry rounds occur in
//!   one traversal. After the last round, the engine skips each znode that is still unread.
//! - **Authoritative** errors are all other errors, such as `Error::NoAuth`. A new read gives
//!   the same result, so the engine skips the znode immediately.
//!
//! The engine records the path and error of each skipped znode. The traversal completes only
//! after the retry rounds stop, so the snapshot is as complete as the server permits. A
//! transient error that a later round clears is not recorded. The owning cache surfaces the
//! recorded errors. During resynchronization, the cache keeps the cached state of each skipped
//! znode and its subtree, because a failed read does not indicate that the znode is gone.

use std::collections::VecDeque;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use apache_zookeeper::{Client, Error, ReadyFuture, ResponseFuture, Stat, ZPath, ZPathBuf};

use super::CachedNode;
use super::tree::Tree;

/// Maximum number of znodes evaluated concurrently in the sliding window pipeline.
const WINDOW: usize = 256;

/// Maximum number of retry rounds for znodes that a transient error set aside in one traversal.
///
/// The bound is small because each round re-reads only the znodes that failed. It stops a
/// traversal under persistent throttling from re-reading the same znodes without end.
const ROUNDS: usize = 3;

/// Returns whether a per-node read error reports load and not the state of the znode.
fn is_transient(e: &Error) -> bool {
    matches!(e, Error::ThrottledOp | Error::TooManyPendingRequests)
}

/// Container caching an in-flight asynchronous response or its resolved value.
#[derive(Debug)]
pub(super) struct Reply<T> {
    fut: Option<ResponseFuture<T>>,
    out: Option<Result<T, Error>>,
}

impl<T> Reply<T> {
    fn new(fut: ResponseFuture<T>) -> Self {
        Self {
            fut: Some(fut),
            out: None,
        }
    }

    /// Polls the underlying response future, returning `true` once a resolved value is available.
    fn poll_ready(&mut self, cx: &mut Context<'_>) -> bool {
        if self.out.is_some() {
            return true;
        }
        let Some(fut) = self.fut.as_mut() else {
            return true;
        };
        match Pin::new(fut).poll(cx) {
            Poll::Ready(r) => {
                self.out = Some(r);
                self.fut = None;
                true
            }
            Poll::Pending => false,
        }
    }

    /// Extracts the completed response value, returning `Error::Closing` if already consumed.
    fn take(&mut self) -> Result<T, Error> {
        self.out.take().unwrap_or(Err(Error::Closing))
    }
}

/// Asynchronous query fetching either node payload and stat, or stat metadata alone.
#[derive(Debug)]
pub(super) enum NodeRead {
    /// `get_data` request issued when payload caching is enabled.
    Data(Reply<(Vec<u8>, Stat)>),
    /// Lightweight `exists` query issued when payload caching is disabled.
    Stat(Reply<Option<Stat>>),
}

impl NodeRead {
    /// Dispatches an asynchronous node query for `path` according to payload caching configuration.
    pub(super) fn submit(client: &Client, path: ZPath<'_>, cache_data: bool) -> Self {
        if cache_data {
            Self::Data(Reply::new(client.get_data(path)))
        } else {
            Self::Stat(Reply::new(client.exists(path)))
        }
    }

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> bool {
        match self {
            Self::Data(r) => r.poll_ready(cx),
            Self::Stat(r) => r.poll_ready(cx),
        }
    }

    /// Consumes the query response, mapping `NoNode` to `Ok(None)`.
    fn take(&mut self) -> Result<Option<CachedNode>, Error> {
        match self {
            Self::Data(r) => match r.take() {
                Ok((data, stat)) => Ok(Some(CachedNode {
                    stat,
                    data: Some(Arc::from(data)),
                })),
                Err(Error::NoNode) => Ok(None),
                Err(e) => Err(e),
            },
            Self::Stat(r) => r
                .take()
                .map(|s| s.map(|stat| CachedNode { stat, data: None })),
        }
    }

    /// Polls the node query until completion, yielding `Ok(None)` if the znode does not exist.
    pub(super) fn poll(&mut self, cx: &mut Context<'_>) -> Poll<Result<Option<CachedNode>, Error>> {
        if self.poll_ready(cx) {
            Poll::Ready(self.take())
        } else {
            Poll::Pending
        }
    }
}

/// Tracks concurrent data and children queries for a single znode in the pipeline.
#[derive(Debug)]
struct InFlight {
    path: ZPathBuf,
    depth: usize,
    node: NodeRead,
    /// Pipelined children query, or `None` if the node resides at the configured maximum caching
    /// depth.
    children: Option<Reply<Vec<String>>>,
}

impl InFlight {
    fn poll_ready(&mut self, cx: &mut Context<'_>) -> bool {
        self.node.poll_ready(cx) && self.children.as_mut().is_none_or(|c| c.poll_ready(cx))
    }

    /// Consumes the node and children responses, returning `None` if the node was deleted
    /// concurrently.
    fn take(&mut self) -> Result<Option<(CachedNode, Vec<String>)>, Error> {
        let Some(node) = self.node.take()? else {
            return Ok(None);
        };
        let names = match self.children.as_mut().map(Reply::take) {
            None => Vec::new(),
            Some(Ok(names)) => names,
            Some(Err(Error::NoNode)) => return Ok(None),
            Some(Err(e)) => return Err(e),
        };
        Ok(Some((node, names)))
    }
}

/// State machine driving parallel breadth-first subtree synchronization.
#[derive(Debug)]
pub(super) struct Read {
    /// Session connection epoch recorded when traversal commenced.
    epoch: u64,
    cache_data: bool,
    max_depth: Option<usize>,
    /// Queue of discovered candidate paths awaiting evaluation, ordered breadth-first alongside
    /// depth.
    todo: VecDeque<(ZPathBuf, usize)>,
    /// Sliding window pipeline of currently active node queries.
    flight: VecDeque<InFlight>,
    tree: Tree,
    /// Paths and errors of znodes skipped during traversal. Authoritative skips come first in
    /// breadth-first order, then the znodes that were still unread after the last retry round.
    skipped: Vec<(ZPathBuf, Error)>,
    /// Paths, depths, and errors of znodes that a transient error set aside in this round.
    retry: Vec<(ZPathBuf, usize, Error)>,
    /// Number of retry rounds started.
    rounds: usize,
    /// Wait for room in the client request queue before the next round is dispatched.
    ready: Option<ReadyFuture>,
}

impl Read {
    /// Initializes a new breadth-first subtree traversal rooted at `root` for the specified
    /// session epoch.
    pub(super) fn start(
        client: &Client,
        root: &ZPathBuf,
        cache_data: bool,
        max_depth: Option<usize>,
        epoch: u64,
    ) -> Self {
        let mut read = Self {
            epoch,
            cache_data,
            max_depth,
            todo: VecDeque::from([(root.clone(), 0)]),
            flight: VecDeque::new(),
            tree: Tree::new(root.clone()),
            skipped: Vec::new(),
            retry: Vec::new(),
            rounds: 0,
            ready: None,
        };
        read.fill(client);
        read
    }

    /// Returns the session connection epoch recorded at the start of traversal.
    pub(super) fn epoch(&self) -> u64 {
        self.epoch
    }

    /// Removes and returns the recorded paths and errors of znodes skipped during traversal.
    pub(super) fn take_skipped(&mut self) -> Vec<(ZPathBuf, Error)> {
        std::mem::take(&mut self.skipped)
    }

    /// Dispatches additional node queries until the active pipeline reaches the `WINDOW` limit.
    fn fill(&mut self, client: &Client) {
        while self.flight.len() < WINDOW {
            let Some((path, depth)) = self.todo.pop_front() else {
                return;
            };
            let node = NodeRead::submit(client, path.as_path(), self.cache_data);
            let children = if self.max_depth.is_some_and(|m| depth >= m) {
                None
            } else {
                Some(Reply::new(client.get_children(&path)))
            };
            self.flight.push_back(InFlight {
                path,
                depth,
                node,
                children,
            });
        }
    }

    /// Starts a retry round for the znodes that a transient error set aside, if one is due.
    ///
    /// Returns `false` when no retry is necessary or the round bound is reached. In the second
    /// case, the unread znodes are recorded as skipped.
    fn next_round(&mut self, client: &Client) -> bool {
        if self.retry.is_empty() {
            return false;
        }
        let retry = std::mem::take(&mut self.retry);
        if self.rounds >= ROUNDS {
            self.skipped
                .extend(retry.into_iter().map(|(path, _, e)| (path, e)));
            return false;
        }
        self.rounds = self.rounds.saturating_add(1);
        // A local queue rejection fails at once. The wait for queue room prevents a round
        // that the same full queue rejects again.
        if retry
            .iter()
            .any(|(_, _, e)| matches!(e, Error::TooManyPendingRequests))
        {
            self.ready = Some(client.ready());
        }
        self.todo
            .extend(retry.into_iter().map(|(path, depth, _)| (path, depth)));
        true
    }

    /// Progresses the traversal pipeline, returning the fully populated [`Tree`] snapshot upon
    /// completion.
    pub(super) fn poll(
        &mut self,
        cx: &mut Context<'_>,
        client: &Client,
    ) -> Poll<Result<Tree, Error>> {
        loop {
            if let Some(ready) = self.ready.as_mut() {
                // A session failure is not handled here. The reads of the round then fail
                // with that error and abort the traversal.
                if Pin::new(ready).poll(cx).is_pending() {
                    return Poll::Pending;
                }
                self.ready = None;
            }
            self.fill(client);
            let Some(front) = self.flight.front_mut() else {
                if self.next_round(client) {
                    continue;
                }
                let empty = Tree::new(self.tree.root().to_buf());
                return Poll::Ready(Ok(std::mem::replace(&mut self.tree, empty)));
            };
            if !front.poll_ready(cx) {
                return Poll::Pending;
            }
            let Some(mut done) = self.flight.pop_front() else {
                continue;
            };
            let (node, mut names) = match done.take() {
                Ok(Some(found)) => found,
                Ok(None) => continue,
                // The znode and its subtree are set aside because its children are never
                // queued. Its parent stays in the snapshot, so a retry round can insert it.
                Err(e) if is_transient(&e) => {
                    self.retry.push((done.path, done.depth, e));
                    continue;
                }
                Err(e) if !e.is_connection_loss() && !e.is_session_terminal() => {
                    self.skipped.push((done.path, e));
                    continue;
                }
                Err(e) => return Poll::Ready(Err(e)),
            };
            // Breadth-first traversal guarantees ancestors are inserted before their descendants.
            if !self.tree.insert(done.path.as_path(), node) {
                continue;
            }
            names.sort();
            let Some(depth) = done.depth.checked_add(1) else {
                continue;
            };
            for name in names {
                // Child names returned by ZooKeeper are guaranteed to form valid child paths.
                if let Ok(child) = done.path.as_path().join(&name) {
                    self.todo.push_back((child, depth));
                }
            }
        }
    }
}
