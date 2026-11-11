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

//! Ephemeral participant znode lifecycle management and protected creation recovery.
//!
//! Coordination recipes contend by registering sequential ephemeral znodes under a common
//! parent directory. This module coordinates the resilient creation of these participant
//! nodes via [`create_participant`], incorporating Apache Curator's protected mode recovery
//! protocol to resolve ambiguous creation outcomes following transient network disconnections.
//!
//! # Cancellation Safety and Drop Semantics
//!
//! Asynchronous ZooKeeper operations dispatch over the wire immediately upon invocation.
//! Dropping a client [`ResponseFuture`] does not abort an in-flight server request.
//! To provide robust cancellation safety without runtime background task leaks,
//! [`ParticipantNode`] wraps the znode path in an RAII guard. When dropped, it automatically
//! delegates node unlinking to the [`super::guaranteed`] subsystem.
//!
//! If a task is cancelled while node creation is in-flight or unresolved, the associated
//! [`ProtectedId`] is handed off to guaranteed deletion, ensuring that any znode created by
//! the ensemble despite client disconnection will eventually be reclaimed.

use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll, Waker};

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    Client, CreateMode, Error, ResponseFuture, SessionState, Stat, StateWatcher, Version, ZPath,
    ZPathBuf,
};

use super::guaranteed;
use super::parents::ensure_parents;
use super::protected::ProtectedId;
use super::session::{connected_after, is_retryable, synced_list};

/// RAII guard managing ownership of an active ephemeral participant znode.
///
/// When dropped, delegates guaranteed asynchronous deletion of the znode to [`super::guaranteed`].
#[derive(Debug)]
pub(crate) struct ParticipantNode {
    client: Client,
    path: ZPathBuf,
    /// Guard activation flag; set to `false` via [`forget`](Self::forget) when the node is
    /// already unlinked.
    armed: bool,
}

impl ParticipantNode {
    /// Constructs a new participant node guard assuming ownership of the znode at `path`.
    pub(crate) fn new(client: &Client, path: ZPathBuf) -> Self {
        Self {
            client: client.clone(),
            path,
            armed: true,
        }
    }

    /// Returns the absolute ZooKeeper path of this participant node (without chroot prefix).
    pub(crate) fn path(&self) -> ZPath<'_> {
        self.path.as_path()
    }

    /// Returns the leaf child name component of the participant znode path.
    pub(crate) fn name(&self) -> &str {
        self.path.as_path().name().unwrap_or_default()
    }

    /// Returns a reference to the ZooKeeper client session owning this participant node.
    pub(crate) fn client(&self) -> &Client {
        &self.client
    }

    /// Disarms the guard, suppressing deletion on drop when the node is already confirmed
    /// unlinked.
    pub(crate) fn forget(mut self) {
        self.armed = false;
    }

    /// Deletes the participant znode and awaits server confirmation, retrying across connection
    /// losses.
    ///
    /// If connection loss occurs during deletion, the method waits for reconnection and retries.
    /// Receiving `NoNode` on a retry confirms that the earlier deletion succeeded on the ensemble.
    /// If deletion encounters an unexpected non-retryable error, the node remains armed for
    /// background guaranteed cleanup before surfacing the error to the caller.
    ///
    /// # Errors
    ///
    /// Returns an error if deletion cannot be confirmed or if the session terminates.
    pub(crate) async fn delete_confirmed(self) -> Result<(), Error> {
        let mut state = self.client.state_watcher();
        // Tracks whether a preceding deletion request may have been committed on the ensemble
        // despite connection loss.
        let mut maybe_applied = false;
        let result = loop {
            let sent = state.current();
            let sent = (sent.state, sent.epoch);
            match self.client.delete(&self.path, None::<Version>).await {
                Err(Error::NoNode) if maybe_applied => break Ok(()),
                Err(e) if is_retryable(&e) => {
                    maybe_applied |= e.is_connection_loss() && !read_only_epoch(&state, sent);
                    if let Err(t) = connected_after(&mut state, sent.1).await {
                        break Err(t);
                    }
                }
                other => break other,
            }
        };
        match &result {
            // The znode was confirmed deleted or the session expired; disarm the guard.
            Ok(()) | Err(Error::NoNode) => self.forget(),
            Err(e) if e.is_session_terminal() => self.forget(),
            // Transfer responsibility for deletion to the guaranteed background cleanup engine.
            Err(_) => {}
        }
        result
    }
}

/// Determines whether the session was in a read-only state during request submission and remains
/// in the same epoch.
fn read_only_epoch(state: &StateWatcher, sent: (SessionState, u64)) -> bool {
    sent.0 == SessionState::ConnectedReadOnly && state.current().epoch == sent.1
}

impl Drop for ParticipantNode {
    fn drop(&mut self) {
        if self.armed {
            guaranteed::delete(&self.client, self.path.clone());
        }
    }
}

/// Tracks an in-flight participant znode creation future, managing cancellation cleanup upon drop.
struct PendingCreate<'a> {
    client: &'a Client,
    parent: ZPath<'a>,
    id: &'a ProtectedId,
    /// Connection epoch during which the create request was originally dispatched.
    sent: u64,
    reply: Option<ResponseFuture<(ZPathBuf, Stat)>>,
}

impl Future for PendingCreate<'_> {
    type Output = Result<(ZPathBuf, Stat), Error>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let this = self.get_mut();
        let Some(reply) = this.reply.as_mut() else {
            // Invariant check: future polled again after completion.
            return Poll::Ready(Err(Error::InvalidState));
        };
        let out = Pin::new(reply).poll(cx);
        if out.is_ready() {
            this.reply = None;
        }
        out
    }
}

impl Drop for PendingCreate<'_> {
    fn drop(&mut self) {
        let Some(mut reply) = self.reply.take() else {
            return;
        };
        let parent = self.parent.to_buf();
        if !reply.is_ready() {
            guaranteed::abandon_create(self.client, parent, self.id.clone(), self.sent, reply);
            return;
        }
        let mut cx = Context::from_waker(Waker::noop());
        match Pin::new(&mut reply).poll(&mut cx) {
            Poll::Ready(Ok((path, _))) => guaranteed::delete(self.client, path),
            Poll::Ready(Err(e)) if e.is_connection_loss() => {
                guaranteed::find_and_delete(self.client, parent, self.id.clone(), self.sent);
            }
            // Request failed with an authoritative server rejection; no znode was allocated.
            _ => {}
        }
    }
}

/// RAII guard managing recovery for a creation request whose outcome was rendered ambiguous by
/// connection loss.
struct Unresolved<'a> {
    client: &'a Client,
    parent: ZPath<'a>,
    id: &'a ProtectedId,
    /// Connection epoch during which the ambiguous creation request was dispatched.
    sent: u64,
    armed: bool,
}

impl Drop for Unresolved<'_> {
    fn drop(&mut self) {
        if self.armed {
            guaranteed::find_and_delete(
                self.client,
                self.parent.to_buf(),
                self.id.clone(),
                self.sent,
            );
        }
    }
}

/// Provisions a sequential-ephemeral participant znode adhering to the pattern
/// `<parent>/_c_<uuid>-<marker><seq>`.
///
/// Executes Curator-compatible protected-mode creation:
/// 1. Submits an `EphemeralSequential` creation request under `parent`.
/// 2. If the parent path does not exist (`NoNode`), provisions intermediate parent container
///    nodes and retries.
/// 3. If transient connection loss occurs, awaits reconnection, performs a linearizable directory
///    inspection via `sync` + `get_children`, and adopts any matching child with prefix
///    `_c_<uuid>-`.
/// 4. If rejected with `NotReadOnly`, awaits reconnection to a read-write server before retrying.
///
/// # Errors
///
/// Returns an error if the ZooKeeper session terminates or if an unrecoverable server failure
/// occurs.
pub(crate) async fn create_participant(
    client: &Client,
    parent: ZPath<'_>,
    id: &ProtectedId,
    marker: &str,
    data: &[u8],
) -> Result<ParticipantNode, Error> {
    let path = parent.join(&id.node_name(marker))?;
    let mut state = client.state_watcher();
    loop {
        let epoch = state.current().epoch;
        let create = PendingCreate {
            client,
            parent,
            id,
            sent: epoch,
            reply: Some(client.create(
                &path,
                data,
                CreateMode::EphemeralSequential,
                OPEN_ACL_UNSAFE,
            )),
        };
        match create.await {
            Ok((created, _)) => return Ok(ParticipantNode::new(client, created)),
            Err(Error::NoNode) => match ensure_parents(client, parent).await {
                Ok(()) | Err(Error::NoNode) => {}
                Err(e) if e.is_connection_loss() => {}
                Err(e) => return Err(e),
            },
            Err(e) if e.is_connection_loss() => {
                let mut unresolved = Unresolved {
                    client,
                    parent,
                    id,
                    sent: epoch,
                    armed: true,
                };
                connected_after(&mut state, epoch).await?;
                let found = find_protected(client, parent, id, &mut state).await?;
                unresolved.armed = false;
                if let Some(node) = found {
                    return Ok(node);
                }
            }
            // Read-only replicas cannot accept write requests; wait for read-write ensemble
            // connectivity.
            Err(Error::NotReadOnly) => {
                connected_after(&mut state, epoch).await?;
            }
            Err(e) => return Err(e),
        }
    }
}

/// Inspects parent directory children to locate and adopt an existing znode matching the
/// protected UUID.
async fn find_protected(
    client: &Client,
    parent: ZPath<'_>,
    id: &ProtectedId,
    state: &mut StateWatcher,
) -> Result<Option<ParticipantNode>, Error> {
    let children = synced_children(client, parent, state).await?;
    match children.as_deref().and_then(|c| id.find_in(c)) {
        Some(name) => Ok(Some(ParticipantNode::new(client, parent.join(name)?))),
        None => Ok(None),
    }
}

/// Performs a synchronized listing of the parent directory following ensemble reconnection.
///
/// Precedes the `get_children` request with a `sync` operation to ensure that committed
/// transactions from preceding connection epochs are visible even if reconnecting to a lagging
/// replica.
async fn synced_children(
    client: &Client,
    parent: ZPath<'_>,
    state: &mut StateWatcher,
) -> Result<Option<Vec<String>>, Error> {
    loop {
        let epoch = state.current().epoch;
        let sync = client.sync(parent);
        let mut list = client.get_children(parent);
        match synced_list(sync, &mut list).await {
            Ok(children) => return Ok(Some(children)),
            Err(Error::NoNode) => return Ok(None),
            Err(e) if is_retryable(&e) => {
                connected_after(state, epoch).await?;
            }
            Err(e) => return Err(e),
        }
    }
}
