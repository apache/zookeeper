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

//! In-memory znode subtree caching recipe with persistent recursive watches.
//!
//! [`TreeCache`] maintains an in-memory projection of a ZooKeeper subtree hierarchy, kept
//! synchronized in real time via server side persistent recursive watches
//! (`AddWatchMode::PersistentRecursive`). This recipe requires ZooKeeper server version 3.6.0
//! or higher.
//!
//! # Architecture and Subsystems
//!
//! - [`tree`]: Hierarchical in-memory znode store tracking node statistics, payloads, and child
//!   relationships.
//! - [`sync`]: Parallel tree traversal engine driving initial cache hydration and
//!   post-reconnection resynchronization.
//!
//! # State Machine and Cancellation Safety
//!
//! Cache maintenance operates as a cooperative state machine driven by caller polling via
//! [`TreeCache::next`]. Internal evaluation steps store pending I/O futures and active watchers
//! within the cache struct fields. Consequently, dropping a `next` future retains in-flight
//! progress, ensuring complete cancellation safety without event loss or duplicate requests.
//!
//! # Reconnection Reconciliation and Event Gap Handling
//!
//! The cache monitors session connection epochs directly through [`StateWatcher`] rather than
//! relying solely on watch event streams. Following any network partition or session reconnection,
//! a complete tree resync is triggered. Because ZooKeeper servers do not buffer or backfill
//! watch notifications missed during disconnected periods, this full reconciliation ensures that
//! intermediate changes committed during the disconnection gap are accurately reconciled into
//! the local cache and surfaced as granular update events.

mod sync;
mod tree;

use std::collections::VecDeque;
use std::future::{Future, poll_fn};
use std::pin::Pin;
use std::sync::Arc;
use std::task::Poll;

use apache_zookeeper::{
    AddWatchMode, Client, Error, EventKind, PathArg, PathError, PersistentWatcher, ResponseFuture,
    SessionInfo, Stat, StateWatcher, WatchError, WatchedEvent, ZPathBuf,
};

use crate::shared::session::{after_loss, connected_after, terminal_error, watch_terminal_error};
use sync::{NodeRead, Read};
use tree::{Tree, depth};

/// An in-memory cache mirroring a ZooKeeper znode subtree.
///
/// Progress is driven cooperatively when application tasks await [`next`](Self::next).
/// Cache queries ([`get`](Self::get) and [`children`](Self::children)) return instantaneous
/// snapshots reflecting the state established by the most recent completed `next` call.
#[derive(Debug)]
pub struct TreeCache {
    client: Client,
    root: ZPathBuf,
    cache_data: bool,
    max_depth: Option<usize>,
    /// Persistent recursive watch registered at the subtree root, or `None` during disconnection
    /// recovery.
    watcher: Option<PersistentWatcher>,
    state: StateWatcher,
    /// Connection epoch corresponding to the most recent comprehensive subtree synchronization.
    epoch: u64,
    /// Connection epoch during which the persistent watch registration was initiated.
    armed: u64,
    tree: Tree,
    /// FIFO queue of pending change events awaiting delivery via [`next`](Self::next).
    events: VecDeque<TreeEvent>,
    step: Step,
}

/// Execution state within the tree cache reconciliation state machine.
#[derive(Debug)]
enum Step {
    /// Actively awaiting watch event notifications or session state transitions.
    Live,
    /// Asynchronously querying a single updated znode following a watch notification.
    Apply { path: ZPathBuf, read: NodeRead },
    /// Asynchronously executing a comprehensive subtree synchronization traversal.
    Sync(Box<Read>),
    /// Persistent watch was invalidated by connection loss; awaiting reconnection to re-arm.
    Rearm {
        after: u64,
        add: Option<(u64, ResponseFuture<PersistentWatcher>)>,
    },
    /// Cache has terminated; the terminal error is delivered once before subsequent calls return
    /// `None`.
    Ended(Option<Error>),
}

/// Internal wake event triggering progress within the `Live` execution step.
enum Wake {
    Watch(Option<Result<WatchedEvent, WatchError>>),
    State(SessionInfo),
    Changed,
}

impl TreeCache {
    /// Creates a builder configuring a new tree cache rooted at `root`.
    ///
    /// Path syntax validation is deferred to [`TreeCacheBuilder::start`].
    pub fn builder(client: &Client, root: impl PathArg) -> TreeCacheBuilder {
        TreeCacheBuilder {
            client: client.clone(),
            root: root.to_zpath().map(|p| p.to_buf()),
            cache_data: true,
            max_depth: None,
        }
    }

    /// Retrieves a snapshot of a cached znode, or `None` if the node is absent or path syntax is
    /// invalid.
    pub fn get(&self, path: impl PathArg) -> Option<CachedNode> {
        let p = path.to_zpath().ok()?;
        self.tree.get(p)
    }

    /// Returns a sorted list of child znode names for the specified path, or `None` if not
    /// present in cache.
    pub fn children(&self, path: impl PathArg) -> Option<Vec<String>> {
        let p = path.to_zpath().ok()?;
        self.tree.children(p)
    }

    /// Advances cache synchronization, yielding the next tree mutation or lifecycle event.
    ///
    /// Following initialization via `start`, yields a sequence of [`TreeEvent::NodeAdded`] events
    /// covering initial subtree contents, concluding with [`TreeEvent::Initialized`]. Following
    /// connection recovery or watch lag recovery, a resynchronization cycle executes, yielding
    /// diff events followed by [`TreeEvent::Resynced`].
    ///
    /// This method is cancellation-safe: dropping the future retains queued events and pending
    /// I/O state.
    ///
    /// Terminal session failures yield `Some(Err(..))` once before subsequent invocations yield
    /// `None`. Transient read errors (such as `NoAuth`) are surfaced as errors while allowing
    /// cache progression to continue.
    pub async fn next(&mut self) -> Option<Result<TreeEvent, Error>> {
        loop {
            if let Some(ev) = self.events.pop_front() {
                return Some(Ok(ev));
            }
            let out = match &mut self.step {
                Step::Ended(e) => return e.take().map(Err),
                Step::Live => self.live().await,
                Step::Apply { .. } => self.apply().await,
                Step::Sync(_) => self.sync().await,
                Step::Rearm { .. } => self.rearm().await,
            };
            if let Some(e) = out {
                return Some(Err(e));
            }
        }
    }

    /// Awaits watch notifications, session state transitions, or connection changes while in the
    /// `Live` state.
    async fn live(&mut self) -> Option<Error> {
        let Some(watcher) = self.watcher.as_mut() else {
            return self.end_on_lost_watcher();
        };
        let client = &self.client;
        let last = self.epoch;
        let mut next = watcher.next();
        let mut changed = self.state.changed();
        let wake = poll_fn(|cx| {
            if let Poll::Ready(o) = Pin::new(&mut next).poll(cx) {
                return Poll::Ready(Wake::Watch(o));
            }
            let info = client.state();
            if info.state.is_terminal() || (info.state.is_connected() && info.epoch > last) {
                return Poll::Ready(Wake::State(info));
            }
            if Pin::new(&mut changed).poll(cx).is_ready() {
                return Poll::Ready(Wake::Changed);
            }
            Poll::Pending
        })
        .await;
        drop(next);
        drop(changed);
        match wake {
            Wake::Watch(Some(Ok(ev))) => {
                self.on_event(ev);
                None
            }
            Wake::Watch(Some(Err(e))) => self.on_watch_error(&e),
            Wake::Watch(None) => self.end_on_lost_watcher(),
            Wake::State(info) => match terminal_error(info.state) {
                Some(e) => self.end(e),
                None => {
                    self.start_sync(info.epoch);
                    None
                }
            },
            Wake::Changed => None,
        }
    }

    /// Processes an incoming watch notification, filtering by depth and scheduling znode queries
    /// or pruning.
    fn on_event(&mut self, ev: WatchedEvent) {
        let path = ev.path.as_path();
        let Some(d) = depth(self.root.as_path(), path) else {
            return;
        };
        if self.max_depth.is_some_and(|m| d > m) {
            return;
        }
        let cached = self.tree.node(path).map(|n| n.stat);
        match ev.kind {
            EventKind::NodeCreated | EventKind::NodeDataChanged => {
                // If the cached znode mzxid is already at or beyond the event zxid, newer data is
                // already present.
                if ev.zxid.zip(cached).is_some_and(|(z, s)| s.mzxid >= z) {
                    return;
                }
                let read = NodeRead::submit(&self.client, path, self.cache_data);
                self.step = Step::Apply {
                    path: ev.path,
                    read,
                };
            }
            EventKind::NodeDeleted => {
                // If the cached znode was created after this deletion event, it represents a
                // replacement node.
                if ev.zxid.zip(cached).is_some_and(|(z, s)| s.czxid > z) {
                    return;
                }
                self.tree.remove_subtree(path, &mut self.events);
            }
            // Persistent recursive watches emit only Created, DataChanged, and Deleted events.
            _ => {}
        }
    }

    /// Handles watcher channel errors, initiating subtree resync on lag or re-arming on
    /// connection loss.
    fn on_watch_error(&mut self, e: &WatchError) -> Option<Error> {
        if let Some(err) = watch_terminal_error(e) {
            return self.end(err);
        }
        match e {
            WatchError::Lagged { .. } => {
                self.start_sync(self.client.state().epoch);
                None
            }
            WatchError::ConnectionLost => {
                self.watcher = None;
                self.step = Step::Rearm {
                    after: self.armed,
                    add: None,
                };
                None
            }
            WatchError::Removed => self.end(Error::NoWatcher),
            // InvalidState is non-fatal for persistent stream consumption; maintain active watch
            // state.
            _ => None,
        }
    }

    /// Awaits completion of a single znode query and reconciles the result into the cached tree.
    async fn apply(&mut self) -> Option<Error> {
        let Step::Apply { read, .. } = &mut self.step else {
            return None;
        };
        let out = poll_fn(|cx| read.poll(cx)).await;
        let Step::Apply { path, .. } = std::mem::replace(&mut self.step, Step::Live) else {
            return None;
        };
        match out {
            Ok(node) => {
                self.tree.apply(path.as_path(), node, &mut self.events);
                None
            }
            Err(e) => self.on_error(e),
        }
    }

    /// Initiates a comprehensive parallel subtree synchronization traversal for the specified
    /// session epoch.
    fn start_sync(&mut self, epoch: u64) {
        let read = Read::start(
            &self.client,
            &self.root,
            self.cache_data,
            self.max_depth,
            epoch,
        );
        self.step = Step::Sync(Box::new(read));
    }

    /// Drives subtree synchronization, calculating differential mutations and enqueuing change
    /// events.
    async fn sync(&mut self) -> Option<Error> {
        let Step::Sync(read) = &mut self.step else {
            return None;
        };
        let client = &self.client;
        let out = poll_fn(|cx| read.poll(cx, client)).await;
        let epoch = read.epoch();
        self.step = Step::Live;
        self.epoch = epoch;
        match out {
            // If a reconnection occurred during the synchronization traversal, discard results to
            // force a clean resync.
            Ok(_) if self.client.state().epoch != epoch => None,
            Ok(new) => {
                self.tree.resync(new, &mut self.events);
                self.events.push_back(TreeEvent::Resynced);
                None
            }
            Err(e) => self.on_error(e),
        }
    }

    /// Re-registers the persistent recursive watch following connection loss, transitioning to
    /// resync on success.
    async fn rearm(&mut self) -> Option<Error> {
        let Step::Rearm { after, add } = &mut self.step else {
            return None;
        };
        if add.is_none() {
            let info = match connected_after(&mut self.state, *after).await {
                Ok(info) => info,
                Err(e) => return self.end(e),
            };
            let fut = self
                .client
                .add_watch(&self.root, AddWatchMode::PersistentRecursive);
            *add = Some((info.epoch, fut));
        }
        let Some((since, fut)) = add else {
            return None;
        };
        let since = *since;
        match fut.await {
            Ok(w) => {
                self.watcher = Some(w);
                self.armed = since;
                self.start_sync(self.client.state().epoch);
                None
            }
            Err(e) if e.is_connection_loss() => {
                self.step = Step::Rearm {
                    after: since,
                    add: None,
                };
                None
            }
            Err(e) => self.end(e),
        }
    }

    /// Evaluates read operation errors, terminating on session failures while suppressing
    /// retryable losses.
    fn on_error(&mut self, e: Error) -> Option<Error> {
        if e.is_session_terminal() {
            return self.end(e);
        }
        if e.is_connection_loss() {
            return None;
        }
        Some(e)
    }

    /// Terminates the cache when the persistent watch channel closes unexpectedly.
    fn end_on_lost_watcher(&mut self) -> Option<Error> {
        let e = terminal_error(self.client.state().state).unwrap_or(Error::NoWatcher);
        self.end(e)
    }

    /// Transitions the cache into a terminal ended state, recording the fatal error for delivery.
    fn end(&mut self, e: Error) -> Option<Error> {
        self.watcher = None;
        self.step = Step::Ended(None);
        Some(e)
    }
}

/// Configuration builder for constructing a [`TreeCache`] instance.
#[derive(Debug)]
pub struct TreeCacheBuilder {
    client: Client,
    root: Result<ZPathBuf, PathError>,
    cache_data: bool,
    max_depth: Option<usize>,
}

impl TreeCacheBuilder {
    /// Configures whether node data payloads are cached in memory. Defaults to `true`.
    ///
    /// When disabled (`false`), [`CachedNode::data`] is set to `None`, conserving memory for
    /// structure-only caches.
    #[must_use]
    pub fn cache_data(mut self, yes: bool) -> Self {
        self.cache_data = yes;
        self
    }

    /// Limits caching depth relative to the subtree root (where root is depth 0).
    ///
    /// Defaults to `None`, caching the entire descendant hierarchy. Znodes beyond this depth are
    /// ignored.
    #[must_use]
    pub fn max_depth(mut self, depth: Option<usize>) -> Self {
        self.max_depth = depth;
        self
    }

    /// Establishes the persistent recursive watch and performs initial subtree cache hydration.
    ///
    /// The persistent watch is established before reading tree contents to guarantee that mutations
    /// occurring concurrently with initial synchronization are captured without race conditions.
    /// The root znode need not exist prior to starting; the cache remains empty until created.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if `root` is invalid. Returns [`Error::Unimplemented`]
    /// on ZooKeeper servers prior to version 3.6.0. Returns a terminal error if the session
    /// expires.
    pub async fn start(self) -> Result<TreeCache, Error> {
        let root = self.root?;
        let client = self.client;
        let mut state = client.state_watcher();
        let mut watcher: Option<PersistentWatcher> = None;
        loop {
            let armed = client.state().epoch;
            // Register the new watcher before dropping the existing handle to preserve unbroken
            // watch coverage.
            let added = client
                .add_watch(&root, AddWatchMode::PersistentRecursive)
                .await;
            let Some(w) = after_loss(added, &mut state, armed).await? else {
                continue;
            };
            drop(watcher.replace(w));
            let epoch = client.state().epoch;
            let mut read = Read::start(&client, &root, self.cache_data, self.max_depth, epoch);
            let out = poll_fn(|cx| read.poll(cx, &client)).await;
            // If reconnection occurred during traversal, the watch may have dropped; restart the
            // registration loop.
            let fresh = after_loss(out, &mut state, epoch).await?;
            let Some(tree) = fresh.filter(|_| client.state().epoch == epoch) else {
                continue;
            };
            let mut events = VecDeque::new();
            tree.added_events(&mut events);
            events.push_back(TreeEvent::Initialized);
            return Ok(TreeCache {
                client,
                root,
                cache_data: self.cache_data,
                max_depth: self.max_depth,
                watcher,
                state,
                epoch,
                armed,
                tree,
                events,
                step: Step::Live,
            });
        }
    }
}

/// Immutable snapshot of a cached znode's metadata and payload.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CachedNode {
    /// ZooKeeper stat structure capturing node metadata, versioning, and transaction IDs.
    pub stat: Stat,
    /// Cached znode payload bytes, or `None` if payload caching was disabled via
    /// [`TreeCacheBuilder::cache_data`].
    pub data: Option<Arc<[u8]>>,
}

/// Mutation or synchronization lifecycle event emitted by [`TreeCache`].
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum TreeEvent {
    /// A new znode was added to the cached hierarchy.
    NodeAdded {
        /// Absolute ZooKeeper path of the added znode (without chroot prefix).
        path: ZPathBuf,
        /// Initial snapshot of the added znode contents and metadata.
        node: CachedNode,
    },
    /// An existing cached znode's payload or metadata stat was modified.
    NodeUpdated {
        /// Absolute ZooKeeper path of the updated znode (without chroot prefix).
        path: ZPathBuf,
        /// Updated snapshot reflecting the modified znode contents and metadata.
        node: CachedNode,
    },
    /// A znode was removed from the cache. Subtree deletions emit events in bottom-up (deepest
    /// first) order.
    NodeRemoved {
        /// Absolute ZooKeeper path of the removed znode (without chroot prefix).
        path: ZPathBuf,
        /// Last observed snapshot of the znode prior to removal.
        node: CachedNode,
    },
    /// Initial subtree synchronization traversal is complete.
    Initialized,
    /// Subtree reconciliation following session reconnection or event lag is complete.
    Resynced,
}
