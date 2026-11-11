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

//! Watch event dispatching and connection session lifecycle state monitoring.
//!
//! The background I/O driver routes incoming watcher notifications directly into registered sinks:
//! single value response cells for one-shot watchers and bounded or unbounded FIFO queues for
//! persistent watchers. Receiving tasks consume events asynchronously via dedicated watcher
//! handles. Session state transitions bypass path watchers entirely and are distributed through
//! [`StateWatcher`] subscriptions.

mod oneshot;
mod persistent;
mod sinks;
mod state;

pub use oneshot::OneshotWatcher;
pub(crate) use persistent::EventQueue;
pub use persistent::{Next, PersistentWatcher};
pub(crate) use sinks::{Applied, Delivery, Sink, WatchOutcome, WatchSinks};
pub(crate) use state::StateCell;
pub use state::{Changed, SessionInfo, SessionState, StateWatcher};

use zk_proto::TerminateReason;

use crate::error::WatchError;
use crate::path::ZPathBuf;
use crate::types::Zxid;

/// Notification event emitted when a monitored znode undergoes a state transition.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WatchedEvent {
    /// Specific category of mutation that occurred on the monitored node.
    pub kind: EventKind,
    /// Absolute znode path within the client's namespace with ensemble chroot prefix stripped.
    pub path: ZPathBuf,
    /// ZooKeeper transaction identifier associated with the mutation event, or `None` if omitted
    /// by older servers.
    pub zxid: Option<Zxid>,
}

/// Classification of znode state change events reported by ZooKeeper servers.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum EventKind {
    /// A new znode was created at the monitored path (`EventType::NodeCreated`).
    NodeCreated,
    /// An existing znode was deleted at the monitored path (`EventType::NodeDeleted`).
    NodeDeleted,
    /// Binary data or metadata stat of the monitored node was updated
    /// (`EventType::NodeDataChanged`).
    NodeDataChanged,
    /// Child nodes were added to or removed from the monitored path
    /// (`EventType::NodeChildrenChanged`).
    NodeChildrenChanged,
}

/// Registration mode governing persistent watch scope and event triggers (ZooKeeper 3.6+).
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum AddWatchMode {
    /// Monitors mutations occurring strictly on the registered znode path itself.
    Persistent,
    /// Recursively monitors creation, deletion, and mutation events on the target node and all
    /// descendant subtrees.
    PersistentRecursive,
}

impl AddWatchMode {
    /// Converts the mode into its corresponding wire integer opcode representation.
    pub(crate) const fn wire(self) -> i32 {
        match self {
            Self::Persistent => 0,
            Self::PersistentRecursive => 1,
        }
    }

    /// Maps the watch mode to its corresponding [`WatcherKind`] for deregistration via
    /// `remove_watches`.
    #[cfg(test)]
    pub(crate) const fn kind(self) -> WatcherKind {
        match self {
            Self::Persistent => WatcherKind::Persistent,
            Self::PersistentRecursive => WatcherKind::PersistentRecursive,
        }
    }
}

/// Watcher category selector used when selectively deregistering active watchers via
/// `remove_watches`.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum WatcherKind {
    /// One-shot child watchers registered via `get_children` queries.
    Children,
    /// One-shot data and node existence watchers registered via `get_data` or `exists` queries.
    Data,
    /// Matches all active watcher types across the specified path.
    Any,
    /// Standard persistent watchers registered on the specified path.
    Persistent,
    /// Persistent recursive watchers monitoring the node and descendant hierarchies.
    PersistentRecursive,
}

impl WatcherKind {
    /// Maps the watcher category to its wire integer representation.
    pub(crate) const fn wire(self) -> i32 {
        match self {
            Self::Children => 1,
            Self::Data => 2,
            Self::Any => 3,
            Self::Persistent => 4,
            Self::PersistentRecursive => 5,
        }
    }
}

/// Deserializes a protocol-level watched event into a high-level [`WatchedEvent`].
///
/// Maps event types 1 through 4 into [`EventKind`]. Ignores session state transitions and
/// deregistration events, which are delivered out-of-band via specialized state channels. Returns
/// `None` if the event path is null or invalid UTF-8.
pub(crate) fn event_from_proto(ev: &zk_proto::WatchedEvent) -> Option<WatchedEvent> {
    let kind = match ev.event_type {
        1 => EventKind::NodeCreated,
        2 => EventKind::NodeDeleted,
        3 => EventKind::NodeDataChanged,
        4 => EventKind::NodeChildrenChanged,
        _ => return None,
    };
    let bytes = ev.path.as_bytes()?;
    let path = ZPathBuf::from_server(bytes.to_vec()).ok()?;
    let zxid = (ev.zxid > 0).then_some(Zxid(ev.zxid));
    Some(WatchedEvent { kind, path, zxid })
}

pub(super) use crate::sync::wake_quietly;

/// Maps protocol-level session termination reasons into typed [`WatchError`] outcomes.
pub(crate) fn watch_error_for(reason: TerminateReason) -> WatchError {
    match reason {
        TerminateReason::Removed => WatchError::Removed,
        TerminateReason::ConnectionLost => WatchError::ConnectionLost,
        TerminateReason::SessionExpired => WatchError::SessionExpired,
        TerminateReason::AuthFailed => WatchError::AuthFailed,
        TerminateReason::Closed => WatchError::Closed,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use super::*;

    fn proto_event(event_type: i32, path: zk_jute::JString, zxid: i64) -> zk_proto::WatchedEvent {
        zk_proto::WatchedEvent {
            event_type,
            state: 3,
            path,
            zxid,
        }
    }

    #[test]
    fn node_event_types_convert() {
        let cases = [
            (1, EventKind::NodeCreated),
            (2, EventKind::NodeDeleted),
            (3, EventKind::NodeDataChanged),
            (4, EventKind::NodeChildrenChanged),
        ];
        for (t, kind) in cases {
            let ev = event_from_proto(&proto_event(t, "/a/b".into(), 7)).unwrap();
            assert_eq!(ev.kind, kind);
            assert_eq!(ev.path.as_str(), "/a/b");
            assert_eq!(ev.zxid, Some(Zxid(7)));
        }
    }

    #[test]
    fn other_event_types_give_none() {
        // Event type -1 represents session state transitions; types 5 through 7 represent watcher
        // removals.
        for t in [-1, 0, 5, 6, 7, 8, i32::MAX, i32::MIN] {
            assert!(event_from_proto(&proto_event(t, "/a".into(), 1)).is_none());
        }
    }

    #[test]
    fn a_zxid_of_zero_or_less_gives_none() {
        for z in [0, -1, i64::MIN] {
            let ev = event_from_proto(&proto_event(3, "/a".into(), z)).unwrap();
            assert_eq!(ev.zxid, None);
        }
    }

    #[test]
    fn a_null_or_bad_path_gives_none() {
        assert!(event_from_proto(&proto_event(1, zk_jute::JString::Null, 1)).is_none());
        let bad = zk_jute::JString::from(vec![b'/', 0xff, 0xfe]);
        assert!(event_from_proto(&proto_event(1, bad, 1)).is_none());
    }

    #[test]
    fn a_panicking_waker_is_contained() {
        struct Boom;
        impl std::task::Wake for Boom {
            fn wake(self: std::sync::Arc<Self>) {
                std::panic::panic_any("waker panic");
            }
        }
        wake_quietly(std::task::Waker::from(std::sync::Arc::new(Boom)));
    }

    #[test]
    fn wire_values() {
        assert_eq!(AddWatchMode::Persistent.wire(), 0);
        assert_eq!(AddWatchMode::PersistentRecursive.wire(), 1);
        assert_eq!(WatcherKind::Children.wire(), 1);
        assert_eq!(WatcherKind::PersistentRecursive.wire(), 5);
        assert_eq!(
            AddWatchMode::PersistentRecursive.kind(),
            WatcherKind::PersistentRecursive
        );
        assert_eq!(watch_error_for(TerminateReason::Closed), WatchError::Closed);
    }
}
