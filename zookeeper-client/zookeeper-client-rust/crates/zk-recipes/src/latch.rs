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

//! `LeaderLatch`: Distributed leader election over a designated ZooKeeper path.
//!
//! This recipe implements distributed leader election wire compatible with Apache Curator's
//! `LeaderLatch`. Contending participants create sequential ephemeral znodes adhering to the
//! naming pattern `<latch_path>/_c_<uuid>-latch-<seq>`, storing their unique participant
//! identifier within the znode payload. The participant whose node occupies the lowest position
//! in Curator's sequential ordering is elected leader. Each non-leader participant monitors only
//! its immediate predecessor in the sequence, avoiding thundering herd effects upon leadership
//! turnover.
//!
//! # State Machine and Evaluation Lifecycle
//!
//! The [`Latch`] maintains an asynchronous state machine driven entirely by caller polling
//! via [`Latch::changed`] or [`Latch::await_leadership`]. In-flight network operations and
//! active one-shot watchers are retained across poll boundaries, providing complete cancellation
//! safety without progress loss when futures are dropped and re-awaited.
//!
//! Leadership remains valid only while the client session maintains an active, unbroken
//! `Connected` state within the specific connection epoch during which election was evaluated.
//! Any transition away from read-write connectivity immediately revokes leadership. Upon
//! entering a new connection epoch, the state machine verifies the continued existence of its
//! participant znode before reevaluating relative sequence ordering.
//!
//! # Read-Only Partition Safety
//!
//! Unlike Apache Curator's Java implementation, which may continue asserting leadership on
//! read-only connections (`ConnectedReadOnly`) through cached watches, this implementation
//! deliberately suspends leadership whenever operating against a read-only server. A read-only
//! replica may reside in a minority partition whose state has drifted while the quorum majority
//! expired the old session and elected a new leader. Leadership is resumed only once a full
//! read-write connection to the ensemble is reestablished.
//!
//! # Protocol and Compatibility Semantics
//!
//! - **Automatic Participant Recreation**: If this participant's znode disappears from the
//!   children listing (e.g. via external deletion), the latch automatically provisions a new
//!   candidate znode with a fresh UUID, matching the behavior of Curator's `reset`.
//! - **Safe Read-Only Retry**: Creations failing with `NotReadOnly` wait for subsequent read-write
//!   reconnection, ensuring no partial writes compromise state.
//! - **Dynamic Participant Discovery**: [`Latch::participants`] gracefully ignores znodes that
//!   are concurrently unlinked during enumeration, mirroring Curator's tolerance for ephemeral
//!   churn.

use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Mutex, PoisonError};
use std::task::{Context, Poll};

use apache_zookeeper::{
    Client, Error, OneshotWatcher, PathArg, ResponseFuture, SessionInfo, SessionState, Stat,
    StateWatcher, ZPath, ZPathBuf,
};

use crate::shared::order::{LATCH_MARKER, position, predecessor, sort_children};
use crate::shared::participant::{ParticipantNode, create_participant};
use crate::shared::protected::ProtectedId;
use crate::shared::select::{Either, select};
use crate::shared::session::{terminal_error, watch_terminal_error};

/// Distributed leader election factory and configuration builder.
///
/// Implements leader election wire-compatible with Apache Curator's `LeaderLatch` on the
/// same path hierarchy. Heterogeneous Java and Rust clusters can safely co-exist and exchange
/// leadership seamlessly.
#[derive(Debug)]
pub struct LeaderLatch {
    client: Client,
    path: ZPathBuf,
    id: Vec<u8>,
}

impl LeaderLatch {
    /// Creates a new leader election participant configured for `latch_path` with the specified
    /// `participant_id`.
    ///
    /// The participant identifier is encoded into the znode payload, allowing peer processes
    /// to inspect leadership identity using [`Latch::participants`] or Curator's `getParticipants`.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if `latch_path` violates ZooKeeper path syntax.
    pub fn new(
        client: &Client,
        latch_path: impl PathArg,
        participant_id: impl Into<Vec<u8>>,
    ) -> Result<LeaderLatch, Error> {
        let path = latch_path.to_zpath()?.to_buf();
        Ok(Self {
            client: client.clone(),
            path,
            id: participant_id.into(),
        })
    }

    /// Enters election contention by creating the initial ephemeral participant znode.
    ///
    /// Returns an un-evaluated [`Latch`] handle. Leadership status is initially unasserted
    /// ([`Latch::is_leader`] returns `false`) until evaluated via [`Latch::changed`] or
    /// [`Latch::await_leadership`].
    ///
    /// # Cancellation Safety
    ///
    /// If dropped while the creation request is in-flight, guaranteed deletion ensures any
    /// znode ultimately created by the ensemble will be safely removed, preventing abandoned
    /// participant nodes from stalling election progression.
    ///
    /// # Errors
    ///
    /// Returns an error if the ZooKeeper session terminates or if znode creation fails.
    pub async fn start(&self) -> Result<Latch, Error> {
        let state = self.client.state_watcher();
        let node = make_node(&self.client, self.path.as_path(), &self.id).await?;
        let epoch = state.current().epoch;
        Ok(Latch {
            client: self.client.clone(),
            path: self.path.clone(),
            id: self.id.clone(),
            node: Some(node),
            leader: false,
            state,
            epoch,
            step: Step::Evaluate,
            ended: None,
        })
    }
}

/// An active leader election participant handle.
///
/// State evaluation progresses cooperatively whenever the application awaits
/// [`changed`](Self::changed) or [`await_leadership`](Self::await_leadership).
/// Current leadership state can be synchronously queried via [`is_leader`](Self::is_leader).
///
/// Disconnection or degradation to `Connecting` or `ConnectedReadOnly` immediately revokes
/// leadership assertions. Upon re-establishing full read-write connectivity in a new epoch,
/// the latch re-confirms its znode existence and re-evaluates leader ranking.
///
/// # Resource Cleanup
///
/// Dropping the latch automatically schedules guaranteed deletion of its participant znode,
/// allowing subsequent participants in the sequence to assume leadership. Explicit cleanup
/// with server confirmation is available via [`close`](Self::close).
#[derive(Debug)]
pub struct Latch {
    client: Client,
    /// Target parent znode path under which participant nodes are created.
    path: ZPathBuf,
    /// Application-provided participant identifier embedded in node payloads.
    id: Vec<u8>,
    /// Active participant znode representation, or `None` during recreation or after shutdown.
    node: Option<ParticipantNode>,
    /// Cached outcome of the most recent election evaluation; [`Latch::is_leader`] additionally
    /// validates live session connectivity and epoch continuity.
    leader: bool,
    state: StateWatcher,
    /// Connection epoch corresponding to the most recent participant znode verification.
    epoch: u64,
    /// Current internal evaluation step, holding pending futures or registered watchers.
    step: Step,
    /// Terminal error if the underlying ZooKeeper session has expired or aborted.
    ended: Option<Error>,
}

/// Internal execution step within the leader election evaluation state machine.
#[derive(Debug)]
enum Step {
    /// Initial state: initiate children enumeration under the latch path.
    Evaluate,
    /// Asynchronous children enumeration request is in flight.
    Listing(ResponseFuture<Vec<String>>),
    /// Asynchronous watched read of the immediate predecessor znode is in flight.
    Watching(ResponseFuture<(Vec<u8>, Stat, OneshotWatcher)>),
    /// Suspended awaiting notification of predecessor znode deletion or modification.
    Waiting(OneshotWatcher),
    /// Currently asserting leadership; awaiting session transitions or disconnects.
    Leading,
    /// Disconnected from ensemble; awaiting reconnection into a new epoch.
    Suspended,
    /// Connection re-established; verify existence of the participant znode.
    Check,
    /// Asynchronous existence check for the participant znode is in flight.
    Checking(ResponseFuture<Option<Stat>>),
    /// Candidate znode was removed; initiate creation of a replacement znode.
    Create,
    /// Asynchronous creation of replacement participant znode is in flight.
    Creating(PendingNode),
    /// Latch has terminated following explicit closure or session expiration.
    Ended,
}

impl Latch {
    /// Returns `true` if this participant currently holds valid leadership.
    ///
    /// Leadership requires both that the most recent election evaluation elected this participant
    /// and that the underlying session remains continuously `Connected` within the evaluation
    /// epoch. Any disconnection, transition to `ConnectedReadOnly`, or terminal session state
    /// causes this method to return `false` immediately without waiting for a subsequent
    /// [`changed`](Self::changed) poll (which will still report the revocation as `Ok(false)`).
    ///
    /// Reflects the client's local session view, which is bounded by the negotiated session
    /// timeout window.
    #[must_use]
    pub fn is_leader(&self) -> bool {
        self.leader && self.holds()
    }

    /// Suspends until a transition in leadership status occurs, returning the updated state.
    ///
    /// Transitions away from read-write connectivity (`Connecting`, `ConnectedReadOnly`, or
    /// advancing connection epochs) immediately yield `Ok(false)`. Leadership confirmation
    /// (`Ok(true)`) occurs only while the session remains uninterruptedly `Connected` within
    /// the evaluation epoch. If a terminal session failure occurs while holding leadership,
    /// this method yields `Ok(false)` prior to surfacing the terminal error on subsequent calls.
    ///
    /// This method is cancellation-safe: dropping the future retains all partial progress.
    ///
    /// # Errors
    ///
    /// Returns the terminal session error if the ZooKeeper session expires. Individual transient
    /// request failures are returned once, and retried on the subsequent invocation.
    pub async fn changed(&mut self) -> Result<bool, Error> {
        loop {
            if let Some(e) = &self.ended {
                return Err(e.clone());
            }
            match self.advance().await {
                // If the session changed concurrently with the election response, defer
                // leadership assertion until the new state is processed.
                Ok(Some(true)) if !self.holds() => {}
                Ok(Some(value)) if value != self.leader => {
                    self.leader = value;
                    return Ok(value);
                }
                Ok(_) => {}
                Err(e) if e.is_session_terminal() => {
                    self.end(e.clone());
                    if self.leader {
                        // Leadership revocation is surfaced to the caller prior to delivering
                        // the terminal error.
                        self.leader = false;
                        return Ok(false);
                    }
                    return Err(e);
                }
                Err(e) => return Err(e),
            }
        }
    }

    /// Suspends asynchronously until this participant is elected leader under an active
    /// read-write session.
    ///
    /// If leadership is already held and the session epoch remains valid, this method resolves
    /// immediately.
    ///
    /// # Errors
    ///
    /// Returns an error if the underlying ZooKeeper session terminates.
    pub async fn await_leadership(&mut self) -> Result<(), Error> {
        loop {
            if let Some(e) = &self.ended {
                return Err(e.clone());
            }
            if self.leader && self.holds() {
                return Ok(());
            }
            if self.changed().await? {
                return Ok(());
            }
        }
    }

    /// Enumerates all active election participants ordered by sequence position, with the leader
    /// first.
    ///
    /// Participant payloads are fetched concurrently. Znodes deleted concurrently during
    /// enumeration are omitted, matching Apache Curator's tolerance for transient member
    /// departures.
    ///
    /// # Errors
    ///
    /// Returns an error if directory enumeration or node payload queries fail with an
    /// unrecoverable error.
    pub async fn participants(&self) -> Result<Vec<Participant>, Error> {
        let mut children = match self.client.get_children(&self.path).await {
            Ok(children) => children,
            Err(Error::NoNode) => return Ok(Vec::new()),
            Err(e) => return Err(e),
        };
        sort_children(&mut children, LATCH_MARKER);
        let mut reads = Vec::with_capacity(children.len());
        for child in &children {
            let path = self.path.as_path().join(child)?;
            reads.push(self.client.get_data(&path));
        }
        let mut out: Vec<Participant> = Vec::with_capacity(reads.len());
        for read in reads {
            match read.await {
                Ok((id, _)) => {
                    let is_leader = out.is_empty();
                    out.push(Participant { id, is_leader });
                }
                Err(Error::NoNode) => {}
                Err(e) => return Err(e),
            }
        }
        Ok(out)
    }

    /// Releases election participation by deleting the participant znode and awaiting server
    /// confirmation.
    ///
    /// If a replacement participant znode creation is currently in flight, `close` awaits its
    /// completion before issuing the deletion. If the latch holds no active node, this method
    /// completes immediately.
    ///
    /// Automatic retries across connection loss ensure that unlinking is confirmed, preventing
    /// orphaned znodes from obstructing subsequent leaders.
    ///
    /// # Errors
    ///
    /// Returns an error if deletion cannot be confirmed or if the session has expired.
    pub async fn close(mut self) -> Result<(), Error> {
        if let Some(e) = self.ended.take() {
            return Err(e);
        }
        let step = std::mem::replace(&mut self.step, Step::Ended);
        let node = match (self.node.take(), step) {
            (Some(node), _) => node,
            (None, Step::Creating(pending)) => pending.await?,
            (None, _) => return Ok(()),
        };
        node.delete_confirmed().await
    }

    /// Verifies whether leadership remains valid under an uninterrupted read-write connection
    /// epoch.
    fn holds(&self) -> bool {
        let info = self.state.current();
        matches!(self.step, Step::Leading)
            && info.state == SessionState::Connected
            && info.epoch == self.epoch
    }

    /// Marks the latch as terminated with the provided terminal session error and cleans up
    /// local state.
    fn end(&mut self, e: Error) {
        self.ended = Some(e);
        self.step = Step::Ended;
        if let Some(node) = self.node.take() {
            node.forget();
        }
    }

    /// Releases the discarded participant node handle and schedules creation of a replacement
    /// znode.
    fn node_gone(&mut self) {
        if let Some(node) = self.node.take() {
            node.forget();
        }
        self.step = Step::Create;
    }

    /// Advances the evaluation state machine by executing the current step.
    ///
    /// Yields `Some(is_leader)` when a definitive leadership status transition is reached,
    /// or `None` when further asynchronous polling is needed.
    async fn advance(&mut self) -> Result<Option<bool>, Error> {
        let info = self.state.current();
        if let Some(e) = terminal_error(info.state) {
            return Err(e);
        }
        if self.revoke(&info) {
            return Ok(Some(false));
        }
        match &mut self.step {
            Step::Evaluate => {
                let list = self.client.get_children(&self.path);
                self.go(Step::Listing(list))
            }
            Step::Listing(reply) => match race(reply, &mut self.state).await {
                Some(out) => self.listed(out),
                None => Ok(None),
            },
            Step::Watching(reply) => match race(reply, &mut self.state).await {
                Some(Ok((_, _, watcher))) => self.go(Step::Waiting(watcher)),
                // Predecessor znode disappeared before the read completed; restart evaluation.
                Some(Err(Error::NoNode)) => self.go(Step::Evaluate),
                Some(Err(e)) => self.failed(e, Step::Evaluate),
                None => Ok(None),
            },
            Step::Waiting(watcher) => match race(watcher, &mut self.state).await {
                Some(out) => match out.err().as_ref().and_then(watch_terminal_error) {
                    Some(e) => Err(e),
                    // Any watcher trigger or event indicates potential hierarchy reordering.
                    None => self.go(Step::Evaluate),
                },
                None => Ok(None),
            },
            Step::Leading | Step::Suspended => {
                let _ = self.state.changed().await;
                Ok(None)
            }
            Step::Check => match &self.node {
                Some(node) => self.go(Step::Checking(self.client.exists(node.path()))),
                None => self.go(Step::Create),
            },
            Step::Checking(reply) => match race(reply, &mut self.state).await {
                Some(Ok(Some(_))) => self.go(Step::Evaluate),
                Some(Ok(None)) => {
                    self.node_gone();
                    Ok(None)
                }
                Some(Err(e)) => self.failed(e, Step::Check),
                None => Ok(None),
            },
            Step::Create => {
                let pending = self.new_node();
                self.go(Step::Creating(pending))
            }
            Step::Creating(pending) => match race(pending, &mut self.state).await {
                Some(Ok(node)) => {
                    self.node = Some(node);
                    self.epoch = self.state.current().epoch;
                    self.go(Step::Evaluate)
                }
                Some(Err(e)) => self.failed(e, Step::Create),
                None => Ok(None),
            },
            Step::Ended => Err(self.ended.clone().unwrap_or(Error::Closing)),
        }
    }

    /// Evaluates session state changes, returning `true` if leadership must be immediately revoked.
    ///
    /// In-flight node creations handle their own recovery and are not interrupted by session
    /// changes. Read-only connections suspend the latch because leadership requires read-write
    /// quorum access.
    fn revoke(&mut self, info: &SessionInfo) -> bool {
        if matches!(self.step, Step::Create | Step::Creating(_)) {
            return false;
        }
        if info.state != SessionState::Connected {
            if matches!(self.step, Step::Suspended) {
                return false;
            }
            self.step = Step::Suspended;
            return true;
        }
        if info.epoch <= self.epoch {
            return false;
        }
        self.epoch = info.epoch;
        self.step = Step::Check;
        true
    }

    /// Transitions the state machine to the specified step and yields `None` to continue polling.
    fn go(&mut self, step: Step) -> Result<Option<bool>, Error> {
        self.step = step;
        Ok(None)
    }

    /// Evaluates the children listing to determine leadership status or identify predecessor.
    fn listed(&mut self, out: Result<Vec<String>, Error>) -> Result<Option<bool>, Error> {
        let mut children = match out {
            Ok(children) => children,
            // The parent latch directory was removed; mark the participant node as gone.
            Err(Error::NoNode) => {
                self.node_gone();
                return Ok(Some(false));
            }
            Err(e) => return self.failed(e, Step::Evaluate),
        };
        sort_children(&mut children, LATCH_MARKER);
        let Some(own) = self.node.as_ref().map(ParticipantNode::name) else {
            self.step = Step::Create;
            return Ok(Some(false));
        };
        if position(&children, own) == Some(0) {
            self.step = Step::Leading;
            return Ok(Some(true));
        }
        let Some(before) = predecessor(&children, own) else {
            // Candidate node is absent from the child listing; mark as gone and trigger recreation.
            self.node_gone();
            return Ok(Some(false));
        };
        match self.path.as_path().join(before) {
            Ok(path) => {
                self.step = Step::Watching(self.client.get_data_watched(&path));
                Ok(Some(false))
            }
            Err(e) => self.failed(e.into(), Step::Evaluate),
        }
    }

    /// Handles step execution errors, suspending on connection loss or recording error state.
    fn failed(&mut self, e: Error, next: Step) -> Result<Option<bool>, Error> {
        if e.is_connection_loss() {
            return self.go(Step::Suspended);
        }
        self.step = next;
        Err(e)
    }

    /// Initiates creation of a replacement participant znode with a fresh UUID.
    fn new_node(&self) -> PendingNode {
        let client = self.client.clone();
        let path = self.path.clone();
        let data = self.id.clone();
        PendingNode(Mutex::new(Box::pin(async move {
            make_node(&client, path.as_path(), &data).await
        })))
    }
}

/// Polls a step future concurrently with session state changes, yielding `None` if the session
/// transitions.
async fn race<F: Future + Unpin>(step: &mut F, state: &mut StateWatcher) -> Option<F::Output> {
    match select(step, &mut state.changed()).await {
        Either::Left(out) => Some(out),
        Either::Right(_) => None,
    }
}

/// Creates a protected sequential-ephemeral participant znode carrying the participant identifier.
async fn make_node(
    client: &Client,
    path: ZPath<'_>,
    data: &[u8],
) -> Result<ParticipantNode, Error> {
    create_participant(client, path, &ProtectedId::new(), LATCH_MARKER, data).await
}

/// In-flight participant node creation future retained within the latch state machine.
///
/// Wrapped in a mutex to ensure `Sync` conformance for outer futures while preserving
/// single threaded mutability.
struct PendingNode(Mutex<NodeFuture>);

/// Boxed future yielding a created participant node or ZooKeeper error.
type NodeFuture = Pin<Box<dyn Future<Output = Result<ParticipantNode, Error>> + Send>>;

impl Future for PendingNode {
    type Output = Result<ParticipantNode, Error>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let fut = self
            .get_mut()
            .0
            .get_mut()
            .unwrap_or_else(PoisonError::into_inner);
        fut.as_mut().poll(cx)
    }
}

impl fmt::Debug for PendingNode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PendingNode").finish_non_exhaustive()
    }
}

/// Snapshot representation of an election participant.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Participant {
    /// Participant identifier retrieved from the znode data payload.
    pub id: Vec<u8>,
    /// Indicates whether this participant occupies the lowest sequence position and is thus the
    /// leader.
    pub is_leader: bool,
}
