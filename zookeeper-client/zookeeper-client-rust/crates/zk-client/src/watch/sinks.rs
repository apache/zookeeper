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

//! Registry of active watcher sinks and event dispatch planning.
//!
//! The [`WatchSinks`] table associates each unique [`WatchId`] with its corresponding
//! destination sink (either a one-shot outcome slot or a persistent event queue).
//! To prevent lock contention and inverted hierarchy deadlocks, event delivery is split
//! into two phases:
//! 1. **Planning**: Under the `sinks` leaf lock, matching watcher registrations are
//!    identified, extracted or cloned, and mapped into [`Delivery`] descriptors.
//! 2. **Execution**: After the `sinks` lock is released, deliveries are applied directly
//!    to their individual slots or queues, which notify or wake their respective consumers
//!    outside of any enclosing mutexes.

use std::collections::HashMap;
use std::sync::Arc;

use crate::error::WatchError;
use crate::slot::Slot;
use crate::tokens::WatchId;

use super::{EventQueue, WatchedEvent};

/// Result yielded to a watcher: either an incoming [`WatchedEvent`] or a terminal [`WatchError`].
pub(crate) type WatchOutcome = Result<WatchedEvent, WatchError>;

/// Target sink destination for watcher event delivery.
#[derive(Clone, Debug)]
pub(crate) enum Sink {
    /// One-shot watcher slot resolving a single event or terminal error, detached upon delivery.
    Oneshot(Arc<Slot<WatchOutcome>>),
    /// Persistent watcher queue receiving a stream of events until terminal closure.
    Persistent(Arc<EventQueue>),
}

/// Table of active watcher sinks indexed by their unique [`WatchId`].
#[derive(Debug, Default)]
pub(crate) struct WatchSinks {
    map: HashMap<WatchId, Sink>,
}

impl WatchSinks {
    /// Constructs a new, empty watch sink registry.
    pub(crate) fn new() -> Self {
        Self::default()
    }

    /// Registers a new watcher sink.
    ///
    /// Sinks are inserted prior to submitting the arming wire request to ensure that
    /// events arriving immediately after server side processing cannot be missed.
    pub(crate) fn insert(&mut self, id: WatchId, sink: Sink) {
        self.map.insert(id, sink);
    }

    /// Removes and returns the sink associated with the given identifier, allowing the caller to
    /// deallocate it outside the lock.
    pub(crate) fn remove(&mut self, id: WatchId) -> Option<Sink> {
        self.map.remove(&id)
    }

    /// Returns the number of registered sinks currently held in the table.
    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.map.len()
    }

    /// Plans delivery of a single watched event to the designated list of watcher identifiers.
    ///
    /// One-shot sinks are removed from the registry upon dispatch; persistent sinks remain
    /// registered. Stale identifiers whose receivers have already detached are skipped.
    /// Exactly one one-shot delivery is marked as counted against the read gate, preserving
    /// the protocol invariant that a single server event corresponds to one read gate credit.
    /// Persistent watcher deliveries are unmetered.
    pub(crate) fn deliver_event(
        &mut self,
        watchers: Vec<WatchId>,
        ev: &WatchedEvent,
    ) -> Vec<Delivery> {
        let mut out = Vec::with_capacity(watchers.len());
        let mut counted = false;
        for id in watchers {
            let persistent = match self.map.get(&id) {
                None => continue,
                Some(Sink::Persistent(q)) => Some(Arc::clone(q)),
                Some(Sink::Oneshot(_)) => None,
            };
            match persistent {
                Some(q) => out.push(Delivery::new(Sink::Persistent(q), Ok(ev.clone()), false)),
                None => {
                    if let Some(sink) = self.map.remove(&id) {
                        let count = !counted;
                        counted = true;
                        out.push(Delivery::new(sink, Ok(ev.clone()), count));
                    }
                }
            }
        }
        out
    }

    /// Plans delivery of a terminal error to the specified watchers, removing them from the table.
    pub(crate) fn terminate(&mut self, watchers: Vec<WatchId>, err: &WatchError) -> Vec<Delivery> {
        watchers
            .into_iter()
            .filter_map(|id| self.map.remove(&id))
            .map(|sink| Delivery::new(sink, Err(err.clone()), false))
            .collect()
    }

    /// Plans terminal error delivery to all registered sinks and drains the registry.
    ///
    /// Invoked when the client transitions into a terminal state or when the background
    /// driver terminates. Sinks are processed in ascending [`WatchId`] order to ensure
    /// deterministic wake sequencing.
    pub(crate) fn terminate_all(&mut self, err: &WatchError) -> Vec<Delivery> {
        // Sort by ascending WatchId to preserve deterministic chronological notification ordering.
        let mut all: Vec<(WatchId, Sink)> = self.map.drain().collect();
        all.sort_by_key(|(id, _)| *id);
        all.into_iter()
            .map(|(_, sink)| Delivery::new(sink, Err(err.clone()), false))
            .collect()
    }
}

/// Planned delivery descriptor containing target sink, outcome payload, and read gate accounting.
#[derive(Debug)]
pub(crate) struct Delivery {
    sink: Sink,
    outcome: WatchOutcome,
    count: bool,
}

/// Resulting effects of an executed event delivery.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Applied {
    /// Indicates whether this delivery fulfilled a read-gate permit requirement.
    pub(crate) counted: bool,
    /// Count of missed events if a bounded persistent queue overflowed, reported for metrics
    /// observation.
    pub(crate) lagged: Option<u64>,
}

impl Delivery {
    /// Constructs a new planned delivery action.
    pub(crate) fn new(sink: Sink, outcome: WatchOutcome, count: bool) -> Self {
        Self {
            sink,
            outcome,
            count,
        }
    }

    /// Executes the planned delivery without holding the sinks registry lock.
    ///
    /// For one-shot watchers, the outcome is deposited into the slot. If the receiver has
    /// already detached, the payload is safely dropped. For persistent watchers, events
    /// are appended to the queue, and terminal errors terminate the stream.
    pub(crate) fn apply(self) -> Applied {
        match self.sink {
            Sink::Oneshot(slot) => match slot.fill(self.outcome, self.count) {
                Ok(counted) => Applied {
                    counted,
                    lagged: None,
                },
                // If the receiver was dropped or the slot was previously satisfied, the rejected
                // outcome is discarded outside the lock.
                Err(_) => Applied::default(),
            },
            Sink::Persistent(queue) => match self.outcome {
                Ok(ev) => Applied {
                    counted: false,
                    lagged: queue.push(ev),
                },
                Err(err) => {
                    queue.terminate(err);
                    Applied::default()
                }
            },
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing)]

    use std::task::{Context, Poll, Waker};

    use super::*;
    use crate::path::ZPathBuf;
    use crate::slot::Taken;
    use crate::watch::EventKind;

    fn event(path: &str) -> WatchedEvent {
        WatchedEvent {
            kind: EventKind::NodeDataChanged,
            path: ZPathBuf::new(path.to_owned()).unwrap(),
            zxid: None,
        }
    }

    fn oneshot(t: &mut WatchSinks, id: u64) -> Arc<Slot<WatchOutcome>> {
        let slot = Slot::new();
        t.insert(WatchId(id), Sink::Oneshot(Arc::clone(&slot)));
        slot
    }

    fn persistent(t: &mut WatchSinks, id: u64, bound: Option<usize>) -> Arc<EventQueue> {
        let q = EventQueue::new(bound);
        t.insert(WatchId(id), Sink::Persistent(Arc::clone(&q)));
        q
    }

    fn next(q: &EventQueue) -> Poll<Option<WatchOutcome>> {
        q.poll_next(&mut Context::from_waker(Waker::noop()))
    }

    fn taken(slot: &Slot<WatchOutcome>) -> (WatchOutcome, bool) {
        match slot.try_take() {
            Some(Taken::Value { v, counted }) => (v, counted),
            _ => panic!("no value"),
        }
    }

    #[test]
    fn an_event_counts_once_and_keeps_the_order() {
        let mut t = WatchSinks::new();
        let a = oneshot(&mut t, 1);
        let p = persistent(&mut t, 2, None);
        let b = oneshot(&mut t, 3);
        let ev = event("/x");
        let plan = t.deliver_event(vec![WatchId(2), WatchId(1), WatchId(9), WatchId(3)], &ev);
        assert_eq!(plan.len(), 3);
        assert!(matches!(plan[0].sink, Sink::Persistent(_)));
        assert!(!plan[0].count);
        assert!(plan[1].count);
        assert!(!plan[2].count);
        // One-shot sinks are drained on event delivery, whereas persistent sinks remain active.
        assert_eq!(t.len(), 1);
        let applied: Vec<Applied> = plan.into_iter().map(Delivery::apply).collect();
        assert_eq!(
            applied.iter().filter(|a| a.counted).count(),
            1,
            "one Watch item counts once"
        );
        assert_eq!(taken(&a), (Ok(ev.clone()), true));
        assert_eq!(taken(&b), (Ok(ev.clone()), false));
        assert_eq!(next(&p), Poll::Ready(Some(Ok(ev))));
    }

    #[test]
    fn a_persistent_sink_gets_every_event() {
        let mut t = WatchSinks::new();
        let p = persistent(&mut t, 1, None);
        for path in ["/a", "/b"] {
            for d in t.deliver_event(vec![WatchId(1)], &event(path)) {
                assert_eq!(d.apply(), Applied::default());
            }
        }
        assert_eq!(next(&p), Poll::Ready(Some(Ok(event("/a")))));
        assert_eq!(next(&p), Poll::Ready(Some(Ok(event("/b")))));
        assert_eq!(next(&p), Poll::Pending);
    }

    #[test]
    fn a_oneshot_sink_fires_once() {
        let mut t = WatchSinks::new();
        let a = oneshot(&mut t, 1);
        assert_eq!(t.deliver_event(vec![WatchId(1)], &event("/a")).len(), 1);
        assert!(t.deliver_event(vec![WatchId(1)], &event("/a")).is_empty());
        assert!(
            t.terminate(vec![WatchId(1)], &WatchError::Removed)
                .is_empty()
        );
        drop(a);
    }

    #[test]
    fn a_dead_receiver_drops_the_outcome() {
        let mut t = WatchSinks::new();
        let a = oneshot(&mut t, 1);
        assert!(!a.drop_receiver());
        let plan = t.deliver_event(vec![WatchId(1)], &event("/a"));
        assert_eq!(plan.len(), 1);
        for d in plan {
            assert_eq!(d.apply(), Applied::default());
        }
    }

    #[test]
    fn terminate_ends_the_given_sinks_only() {
        let mut t = WatchSinks::new();
        let a = oneshot(&mut t, 1);
        let p = persistent(&mut t, 2, None);
        let other = oneshot(&mut t, 3);
        let plan = t.terminate(
            vec![WatchId(1), WatchId(2), WatchId(7)],
            &WatchError::Removed,
        );
        assert_eq!(plan.len(), 2);
        assert!(plan.iter().all(|d| !d.count));
        assert_eq!(t.len(), 1);
        for d in plan {
            assert!(!d.apply().counted);
        }
        assert_eq!(taken(&a), (Err(WatchError::Removed), false));
        assert_eq!(next(&p), Poll::Ready(Some(Err(WatchError::Removed))));
        assert_eq!(next(&p), Poll::Ready(None));
        assert!(other.try_take().is_none());
    }

    #[test]
    fn terminate_all_empties_the_table_in_id_order() {
        let mut t = WatchSinks::new();
        let mut slots = Vec::new();
        for id in [5, 1, 4, 2, 3] {
            slots.push((id, oneshot(&mut t, id)));
        }
        let p = persistent(&mut t, 6, Some(1));
        let plan = t.terminate_all(&WatchError::SessionExpired);
        assert_eq!(t.len(), 0);
        assert_eq!(plan.len(), 6);
        assert!(matches!(plan[5].sink, Sink::Persistent(_)));
        let order: Vec<bool> = plan.iter().map(|d| d.count).collect();
        assert_eq!(order, vec![false; 6]);
        for d in plan {
            assert_eq!(d.apply(), Applied::default());
        }
        for (_, s) in &slots {
            assert_eq!(taken(s), (Err(WatchError::SessionExpired), false));
        }
        assert_eq!(next(&p), Poll::Ready(Some(Err(WatchError::SessionExpired))));
        assert!(t.terminate_all(&WatchError::Closed).is_empty());
    }

    #[test]
    fn a_full_bounded_queue_reports_the_lag() {
        let mut t = WatchSinks::new();
        let p = persistent(&mut t, 1, Some(1));
        let mut lagged = Vec::new();
        for path in ["/a", "/b", "/c"] {
            for d in t.deliver_event(vec![WatchId(1)], &event(path)) {
                lagged.push(d.apply().lagged);
            }
        }
        assert_eq!(lagged, vec![None, Some(1), Some(2)]);
        assert_eq!(
            next(&p),
            Poll::Ready(Some(Err(WatchError::Lagged { missed: 2 })))
        );
        assert_eq!(next(&p), Poll::Ready(Some(Ok(event("/a")))));
    }
}
