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

//! Session lifecycle management, terminal error mapping, and reconnection synchronization.
//!
//! This module provides shared primitives for tracking ZooKeeper session state transitions.
//! Recipes interpret terminal session states (`SessionExpired`, `AuthFailed`, `Closed`) as
//! fatal conditions that invalidate coordination guarantees and require recipe teardown.
//! Analogous mappings translate terminal watcher failures into their corresponding client errors.

use apache_zookeeper::{
    Error, ResponseFuture, SessionInfo, SessionState, StateWatcher, WatchError,
};

/// Translates a terminal [`SessionState`] into its corresponding [`Error`], returning `None` for
/// non-terminal states.
pub(crate) fn terminal_error(state: SessionState) -> Option<Error> {
    match state {
        SessionState::Expired => Some(Error::SessionExpired),
        SessionState::AuthFailed => Some(Error::AuthFailed),
        SessionState::Closed => Some(Error::Closing),
        _ => None,
    }
}

/// Converts a fatal [`WatchError`] into an [`Error`], returning `None` for transient or benign
/// events.
pub(crate) fn watch_terminal_error(e: &WatchError) -> Option<Error> {
    match e {
        WatchError::SessionExpired => Some(Error::SessionExpired),
        WatchError::AuthFailed => Some(Error::AuthFailed),
        WatchError::Closed => Some(Error::Closing),
        _ => None,
    }
}

/// Suspends until the session enters a connected state under a connection epoch strictly greater
/// than `after`.
///
/// Both read-write (`Connected`) and read-only (`ConnectedReadOnly`) states satisfy this condition.
///
/// # Errors
///
/// Returns an error if the session terminates in a fatal state (e.g. `SessionExpired`).
pub(crate) async fn connected_after(
    state: &mut StateWatcher,
    after: u64,
) -> Result<SessionInfo, Error> {
    wait_for(state, |info| {
        info.state.is_connected() && info.epoch > after
    })
    .await
}

/// Suspends until the session establishes an active read-write connection with an epoch strictly
/// greater than `after`.
///
/// Returns `Some(epoch)` on success, or `None` if the session permanently terminates.
/// Read-only connections (`ConnectedReadOnly`) are rejected because writes cannot be processed.
pub(crate) async fn writable_after(state: &mut StateWatcher, after: u64) -> Option<u64> {
    let ready = |info: &SessionInfo| info.state == SessionState::Connected && info.epoch > after;
    wait_for(state, ready).await.ok().map(|info| info.epoch)
}

/// Suspends until the session holds an active read-write (`Connected`) connection and returns its
/// connection epoch.
///
/// Resolves immediately if the session is currently `Connected`; read-only (`ConnectedReadOnly`)
/// connections do not satisfy this condition.
///
/// # Errors
///
/// Returns the corresponding terminal error if the session terminates.
pub(crate) async fn writable(state: &mut StateWatcher) -> Result<u64, Error> {
    wait_for(state, |info| info.state == SessionState::Connected)
        .await
        .map(|info| info.epoch)
}

/// Suspends execution until the provided predicate accepts the current session state, or until a
/// terminal error occurs.
async fn wait_for(
    state: &mut StateWatcher,
    ready: impl Fn(&SessionInfo) -> bool,
) -> Result<SessionInfo, Error> {
    loop {
        let info = state.current();
        if let Some(e) = terminal_error(info.state) {
            return Err(e);
        }
        if ready(&info) {
            return Ok(info);
        }
        // Terminal session transitions are persistent and will reliably unblock the loop.
        let _ = state.changed().await;
    }
}

/// Adapts an operation result, returning `Ok(None)` and waiting for reconnection if connection
/// loss occurred.
///
/// If `result` succeeded or failed with a non-connection-loss error, the result is propagated
/// immediately.
///
/// # Errors
///
/// Returns an error if the underlying operation returned a non-retryable error or if the session
/// terminated.
pub(crate) async fn after_loss<T>(
    result: Result<T, Error>,
    state: &mut StateWatcher,
    epoch: u64,
) -> Result<Option<T>, Error> {
    match result {
        Err(e) if e.is_connection_loss() => connected_after(state, epoch).await.map(|_| None),
        other => other.map(Some),
    }
}

/// Indicates whether an error represents a transient condition that may succeed upon reconnection.
pub(crate) fn is_retryable(e: &Error) -> bool {
    e.is_connection_loss() || matches!(e, Error::NotReadOnly)
}

/// Sequentially awaits a linearizing `sync` operation followed by its corresponding child
/// directory listing.
///
/// Ensures that updates committed across the ensemble are applied to the local replica before
/// inspecting children.
pub(crate) async fn synced_list(
    sync: ResponseFuture<()>,
    list: &mut ResponseFuture<Vec<String>>,
) -> Result<Vec<String>, Error> {
    match sync.await {
        Ok(()) | Err(Error::NoNode) => list.await,
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_states_map_to_errors() {
        assert_eq!(
            terminal_error(SessionState::Expired),
            Some(Error::SessionExpired)
        );
        assert_eq!(
            terminal_error(SessionState::AuthFailed),
            Some(Error::AuthFailed)
        );
        assert_eq!(terminal_error(SessionState::Closed), Some(Error::Closing));
        assert_eq!(terminal_error(SessionState::Connecting), None);
        assert_eq!(terminal_error(SessionState::Connected), None);
        assert_eq!(terminal_error(SessionState::ConnectedReadOnly), None);
    }

    #[test]
    fn terminal_watch_errors_map_to_errors() {
        assert_eq!(
            watch_terminal_error(&WatchError::SessionExpired),
            Some(Error::SessionExpired)
        );
        assert_eq!(
            watch_terminal_error(&WatchError::AuthFailed),
            Some(Error::AuthFailed)
        );
        assert_eq!(
            watch_terminal_error(&WatchError::Closed),
            Some(Error::Closing)
        );
        assert_eq!(watch_terminal_error(&WatchError::Removed), None);
        assert_eq!(watch_terminal_error(&WatchError::ConnectionLost), None);
        assert_eq!(
            watch_terminal_error(&WatchError::Lagged { missed: 1 }),
            None
        );
    }
}
