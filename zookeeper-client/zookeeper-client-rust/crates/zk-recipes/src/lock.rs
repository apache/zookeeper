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

//! `DistributedLock`: Distributed mutual exclusion lock on a ZooKeeper path.
//!
//! This implementation follows the standard sequential ephemeral znode pattern
//! and maintains wire compatibility with Apache Curator's `InterProcessMutex`. Rust and Java
//! processes can safely contend for the same lock path concurrently.
//!
//! Each competing process creates an ephemeral sequential znode adhering to the pattern
//! `<lock_path>/_c_<uuid>-lock-<seq>`. The participant whose node possesses the lowest
//! sequence number (evaluated using Curator's sort order) holds the lock. All subsequent
//! participants observe the znode immediately preceding their own in the sorted sequence,
//! preventing the thundering herd problem when the lock holder releases or disconnects.
//!
//! The acquisition state machine maintains ownership of the participant znode in a local
//! RAII wrapper. Consequently, premature exits, whether due to unrecoverable server errors
//! or task cancellation (dropping the future), automatically trigger guaranteed deletion
//! of the candidate node. Only upon successful acquisition is the node transferred into
//! the returned [`LockGuard`].
//!
//! # Behavioral Refinements Relative to Apache Curator
//!
//! - **Automatic Participant Recreation on Missing Znode**: In Apache Curator, if a candidate's
//!   own znode is missing from the parent children listing (for example, if deleted by an
//!   errant administrative operation), acquisition immediately aborts with an exception. In
//!   contrast, this implementation generates a fresh participant node with a new UUID and
//!   continues the acquisition loop, upholding the contractual promise of `acquire`.
//! - **Connection-Loss Tolerant Deletion**: Both explicit `release` and the cleanup path of
//!   `try_acquire` actively retry deletion upon connection loss or transient network failure.
//!   This prevents orphaned ephemeral nodes from holding locks indefinitely until session expiry.
//! - **Linearizable Recovery via Pre-Listing Sync**: When recovering from an ambiguous
//!   creation following a connection loss, a `sync` request precedes the subsequent child listing.
//!   This ensures that even if reconnecting to a lagging follower ensemble replica, newly
//!   committed transactions are visible before concluding whether the node exists.
//! - **Read-Write Quorum Enforcement**: Unlike Apache Curator, which may grant lock ownership
//!   from a child listing served by a read-only replica, this implementation evaluates ownership
//!   strictly from child listings served over read-write (`Connected`) sessions, avoiding stale
//!   views from isolated minority partitions.
//!
//! # Guaranteed Cleanup Semantics
//!
//! Asynchronous Rust futures cannot block on drop. If a caller drops a waiting or holding
//! guard, the associated znode or pending creation request is handed off to the global
//! guaranteed deletion engine. If the node path is known, it is enqueued for deletion; if
//! an in-flight creation outcome was ambiguous, the engine searches for and deletes the node
//! identified by its unique protected UUID prefix. Retries occur automatically upon every
//! session reconnect until deletion is confirmed or the session permanently expires.

use apache_zookeeper::{
    Client, Error, EventKind, OneshotWatcher, PathArg, StateWatcher, ZPath, ZPathBuf,
};

use crate::shared::order::{LOCK_MARKER, position, predecessor, sort_children};
use crate::shared::participant::{ParticipantNode, create_participant};
use crate::shared::protected::ProtectedId;
use crate::shared::select::{Either, select};
use crate::shared::session::{after_loss, terminal_error, watch_terminal_error, writable};

/// A distributed, non-reentrant mutual exclusion lock.
///
/// This lock is wire-compatible with Apache Curator's `InterProcessMutex` on the same
/// path hierarchy. Within a single client process, a secondary `acquire` invocation will
/// contend as an independent participant; reentrancy is deliberately not supported.
///
/// # Connection State Handling
///
/// While the underlying [`StateWatcher`] reports `Connecting`, the caller cannot determine
/// whether the session will recover or ultimately expire. Callers should suspend critical
/// section work until connectivity is restored to `Connected`. Similarly, operating under
/// `ConnectedReadOnly` requires pausing execution, as a read-only server may belong to a
/// network partition where the quorum has already expired the session and granted the lock
/// to another participant.
///
/// Both [`acquire`](Self::acquire) and [`try_acquire`](Self::try_acquire) suspend while the
/// session is `ConnectedReadOnly`, evaluating lock ownership exclusively from child listings
/// served over read-write connections.
#[derive(Debug)]
pub struct DistributedLock {
    client: Client,
    path: ZPathBuf,
    data: Vec<u8>,
}

/// Relative position of this participant's znode within the sorted child list.
enum Place {
    /// This participant's znode is the earliest in sequence and holds the lock.
    First,
    /// This participant's znode is preceded by another znode, identified by name.
    After(String),
    /// This participant's znode was not found in the child list.
    Missing,
}

impl DistributedLock {
    /// Creates a new distributed lock bound to `lock_path`.
    ///
    /// This constructor merely validates the path syntax and initializes internal state;
    /// no remote network operations or ZooKeeper transactions are executed.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if `lock_path` does not conform to ZooKeeper path syntax.
    pub fn new(client: &Client, lock_path: impl PathArg) -> Result<DistributedLock, Error> {
        let path = lock_path.to_zpath()?.to_buf();
        Ok(Self {
            client: client.clone(),
            path,
            data: Vec::new(),
        })
    }

    /// Configures custom application payload data to store in the participant znode.
    ///
    /// By default, participant znodes are created with empty payloads. This payload is
    /// ignored by both this crate and Apache Curator, but can be leveraged for custom
    /// participant diagnostics or inspection.
    #[must_use]
    pub fn node_data(mut self, data: Vec<u8>) -> Self {
        self.data = data;
        self
    }

    /// Acquires the distributed lock, waiting asynchronously until ownership is granted.
    ///
    /// The acquisition procedure creates a protected sequential-ephemeral participant znode
    /// under the lock path and inspects the ordered children. If the created node has the
    /// lowest sequence number, ownership is immediately granted. Otherwise, a one-shot watch
    /// is set on the immediate predecessor znode. The loop suspends until that predecessor
    /// is deleted, re-evaluating the hierarchy after each notification. Transient `Connecting`
    /// or `ConnectedReadOnly` states do not abort the wait. Upon reestablishing a read-write
    /// connection, the child list is refreshed.
    ///
    /// If an external actor removes this participant's znode, the state machine recovers
    /// by creating a new participant znode with a fresh UUID and resuming contention.
    ///
    /// # Cancellation Safety
    ///
    /// Dropping this future before lock acquisition completes safely schedules guaranteed
    /// deletion of the participant znode. If the create request was in-flight when dropped,
    /// the cleanup subsystem tracks and unlinks the node by its protected identifier upon
    /// server response or subsequent reconnection.
    ///
    /// # Errors
    ///
    /// Returns an error if the underlying ZooKeeper session terminates (e.g. `SessionExpired`,
    /// `AuthFailed`), or if an authoritative server error occurs (e.g. `NoAuth`).
    // Clippy counts the awaited request futures as stack locals. They live in the state of this
    // future, which the caller owns. Boxing them would add an allocation to each acquire.
    #[allow(clippy::large_stack_frames)]
    pub async fn acquire(&self) -> Result<LockGuard, Error> {
        let mut state = self.client.state_watcher();
        let mut node = self.create_node().await?;
        // Retain the armed predecessor watch across reconnects if the predecessor name remains
        // unchanged.
        let mut watch: Option<(String, OneshotWatcher)> = None;
        loop {
            let epoch = writable(&mut state).await?;
            let Some(children) = after_loss(self.list().await, &mut state, epoch).await? else {
                continue;
            };
            // Verify that the connection epoch did not advance to a subsequent (potentially
            // read-only) connection while the child listing was in flight.
            if state.current().epoch != epoch {
                continue;
            }
            let pred = match place(children, node.name()) {
                Place::First => return Ok(LockGuard::new(node, state)),
                Place::Missing => {
                    watch = None;
                    node.forget();
                    node = self.create_node().await?;
                    continue;
                }
                Place::After(pred) => pred,
            };
            let mut watcher = match watch.take() {
                Some((armed, w)) if armed == pred => w,
                _ => {
                    let pred_path = self.path.as_path().join(&pred)?;
                    let read = match self.client.get_data_watched(&pred_path).await {
                        Err(Error::NoNode) => continue,
                        read => read,
                    };
                    let Some((_, _, w)) = after_loss(read, &mut state, epoch).await? else {
                        continue;
                    };
                    w
                }
            };
            if new_epoch(&mut watcher, &mut state, epoch).await? {
                watch = Some((pred, watcher));
            }
        }
    }

    /// Attempts to acquire the lock immediately without waiting for preceding holders.
    ///
    /// Creates a candidate participant znode and evaluates its relative order within the
    /// children list. If the node is first in sequence, ownership is granted. If another
    /// participant holds precedence, the candidate znode is promptly deleted and `Ok(None)`
    /// is returned once the deletion is confirmed by the ensemble.
    ///
    /// Ownership is evaluated exclusively from child listings served over an active read-write
    /// connection; while the session is `ConnectedReadOnly`, this method waits for read-write
    /// connectivity to resume.
    ///
    /// # Errors
    ///
    /// Returns an error if ZooKeeper communication fails or the session terminates. If deletion
    /// encounters an unexpected server error, that error is returned and the node is handed off
    /// to the guaranteed deletion subsystem.
    // The reason is the same as for `acquire`.
    #[allow(clippy::large_stack_frames)]
    pub async fn try_acquire(&self) -> Result<Option<LockGuard>, Error> {
        let mut state = self.client.state_watcher();
        let mut node = self.create_node().await?;
        loop {
            let epoch = writable(&mut state).await?;
            let Some(children) = after_loss(self.list().await, &mut state, epoch).await? else {
                continue;
            };
            // Verify that the connection epoch did not advance to a subsequent (potentially
            // read-only) connection while the child listing was in flight.
            if state.current().epoch != epoch {
                continue;
            }
            match place(children, node.name()) {
                Place::First => return Ok(Some(LockGuard::new(node, state))),
                Place::After(_) => {
                    return match node.delete_confirmed().await {
                        // If the node was already removed concurrently, the deletion requirement
                        // is satisfied.
                        Ok(()) | Err(Error::NoNode) => Ok(None),
                        Err(e) => Err(e),
                    };
                }
                Place::Missing => {
                    node.forget();
                    node = self.create_node().await?;
                }
            }
        }
    }

    /// Generates a new protected sequential-ephemeral participant znode under the lock path.
    async fn create_node(&self) -> Result<ParticipantNode, Error> {
        create_participant(
            &self.client,
            self.path.as_path(),
            &ProtectedId::new(),
            LOCK_MARKER,
            &self.data,
        )
        .await
    }

    /// Retrieves and sorts the children of the lock path according to Curator sequence rules.
    /// If the lock path does not exist, an empty list is returned.
    async fn list(&self) -> Result<Vec<String>, Error> {
        match self.client.get_children(&self.path).await {
            Ok(mut children) => {
                sort_children(&mut children, LOCK_MARKER);
                Ok(children)
            }
            Err(Error::NoNode) => Ok(Vec::new()),
            Err(e) => Err(e),
        }
    }
}

/// Determines the relative positioning of `own` within the sorted list of `children`.
fn place(children: Vec<String>, own: &str) -> Place {
    match position(&children, own) {
        None => Place::Missing,
        Some(0) => Place::First,
        Some(_) => {
            predecessor(&children, own).map_or(Place::Missing, |p| Place::After(p.to_owned()))
        }
    }
}

/// Suspends until the predecessor watch fires or a new connection epoch begins.
///
/// Returns `Ok(true)` if connectivity was re-established at an epoch greater than `epoch`,
/// indicating that the caller should retain the armed watch and re-verify the child list.
/// Returns `Ok(false)` if the predecessor znode was deleted or modified.
///
/// # Errors
///
/// Returns a terminal error if the session expires or encounters an unrecoverable state failure.
async fn new_epoch(
    watcher: &mut OneshotWatcher,
    state: &mut StateWatcher,
    epoch: u64,
) -> Result<bool, Error> {
    loop {
        match select(watcher, &mut state.changed()).await {
            Either::Left(Ok(_)) => return Ok(false),
            Either::Left(Err(e)) => return watch_terminal_error(&e).map_or(Ok(false), Err),
            Either::Right(info) => {
                if let Some(t) = terminal_error(info.state) {
                    return Err(t);
                }
                if info.state.is_connected() && info.epoch > epoch {
                    return Ok(true);
                }
            }
        }
    }
}

/// An RAII guard representing active ownership of a distributed lock.
///
/// When dropped, the underlying participant znode is asynchronously submitted to the
/// guaranteed deletion subsystem to release the lock. Callers requiring explicit confirmation
/// of deletion or error handling should instead invoke [`release`](Self::release).
#[derive(Debug)]
pub struct LockGuard {
    node: ParticipantNode,
    /// State watcher tracking session transitions during lock ownership.
    state: StateWatcher,
}

impl LockGuard {
    /// Constructs a new lock guard wrapping an acquired participant znode and session watcher.
    fn new(node: ParticipantNode, state: StateWatcher) -> Self {
        Self { node, state }
    }

    /// Returns the absolute ZooKeeper path of this participant's ephemeral znode (without chroot
    /// prefix).
    #[must_use]
    pub fn node(&self) -> ZPath<'_> {
        self.node.path()
    }

    /// Explicitly deletes the participant znode, releasing the lock and confirming deletion on
    /// the server.
    ///
    /// In the event of transient network disconnections, this method automatically waits for
    /// reconnection and retries the deletion request. Receiving `NoNode` upon retry confirms that
    /// the initial deletion took effect and is treated as success. Any other unexpected failure
    /// transfers ownership to the guaranteed deletion engine before returning the error.
    ///
    /// # Errors
    ///
    /// Returns an error if deletion cannot be confirmed or if the session terminates.
    pub async fn release(self) -> Result<(), Error> {
        self.node.delete_confirmed().await
    }

    /// Awaits notification indicating that ownership of the lock has been definitively lost.
    ///
    /// Resolves with `Error::NoNode` if the participant znode is deleted (e.g. by another actor),
    /// or with the corresponding terminal session error if the underlying ZooKeeper session
    /// expires. The future maintains an active `exists` watch on the participant znode,
    /// automatically re-arming it across reconnection epochs.
    ///
    /// Transient `Connecting` and `ConnectedReadOnly` states do not resolve this future, allowing
    /// callers to tolerate temporary disconnects while pausing critical-section work whenever a
    /// read-only replica may present a stale partition view.
    pub async fn lost(&self) -> Error {
        let client = self.node.client();
        let mut state = self.state.clone();
        let mut watch: Option<OneshotWatcher> = None;
        // Connection epoch during which the watch was most recently established; None forces
        // immediate arming.
        let mut armed_at: Option<u64> = None;
        loop {
            let info = state.current();
            if let Some(t) = terminal_error(info.state) {
                return t;
            }
            let due = info.state.is_connected() && armed_at.is_none_or(|at| info.epoch > at);
            if due {
                armed_at = Some(info.epoch);
                match client.exists_watched(self.node.path()).await {
                    Ok((None, _)) => return Error::NoNode,
                    // Overwrite the existing watcher only after the new watch is confirmed to
                    // prevent gaps in coverage.
                    Ok((Some(_), w)) => watch = Some(w),
                    // Transient connection loss; re-attempt watch registration on the next
                    // connection epoch.
                    Err(e) if e.is_connection_loss() => {}
                    Err(e) => return e,
                }
                continue;
            }
            let mut changed = state.changed();
            match watch.as_mut() {
                Some(w) => match select(w, &mut changed).await {
                    Either::Left(Ok(event)) if event.kind == EventKind::NodeDeleted => {
                        return Error::NoNode;
                    }
                    // Watch triggered by metadata modification, removal, or failure to restore
                    // across session reconnect.
                    Either::Left(event) => {
                        if let Some(t) = event.err().as_ref().and_then(watch_terminal_error) {
                            return t;
                        }
                        watch = None;
                        armed_at = None;
                    }
                    Either::Right(_) => {}
                },
                None => {
                    changed.await;
                }
            }
        }
    }
}
