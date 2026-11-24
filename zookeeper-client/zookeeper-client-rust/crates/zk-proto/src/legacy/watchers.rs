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

//! Legacy client watcher table management and event dispatch ordering.
//!
//! Replicates the watcher registration and notification mechanisms of the historical C client
//! (`zk_hashtable.c`). The client manages three discrete watcher tables: data watchers, existence
//! watchers, and child watchers. Each table maps a znode path to an ordered list of watcher
//! registrations. In C, registrations consist of `(watcher_fn, context)` pointer pairs; this safe
//! port generalizes registrations across any token type `W: Clone + Eq`, where equality replaces
//! pointer comparison.
//!
//! ### Dispatch and Ordering Invariants
//!
//! To ensure bit-for-bit behavioral equivalence with historical C runtimes:
//! - Newly registered watchers are prepended to the head of the path's registration list, unless
//!   an equivalent watcher is already registered.
//! - Event collection inspects tables in fixed order, traversing each per-path list from head to
//!   tail. Duplicate watchers encountered during collection are deduplicated (retaining first
//!   occurrence), and final delivery is executed in the strict reverse of collection order
//!   (reproducing C's stack-based reversal).
//! - Znode data and child events consume and deregister matching watchers from their respective
//!   tables. Session state events broadcast to all registered watchers across all tables without
//!   purging registrations.

use super::hashtable::{LegacyHashTable, TARGET_C_CHAR_SIGNED};

/// ZooKeeper node creation event notification code (`CREATED_EVENT_DEF`).
pub const CREATED_EVENT: i32 = 1;
/// ZooKeeper node deletion event notification code (`DELETED_EVENT_DEF`).
pub const DELETED_EVENT: i32 = 2;
/// ZooKeeper node data modification event notification code (`CHANGED_EVENT_DEF`).
pub const CHANGED_EVENT: i32 = 3;
/// ZooKeeper child list modification event notification code (`CHILD_EVENT_DEF`).
pub const CHILD_EVENT: i32 = 4;
/// ZooKeeper session state lifecycle transition event code (`SESSION_EVENT_DEF`).
pub const SESSION_EVENT: i32 = -1;

/// Watcher classification for child node changes (`ZWATCHTYPE_CHILD`).
pub const WATCH_TYPE_CHILD: i32 = 1;
/// Watcher classification for node data and existence changes (`ZWATCHTYPE_DATA`).
pub const WATCH_TYPE_DATA: i32 = 2;
/// Watcher classification spanning all watch categories (`ZWATCHTYPE_ANY`).
pub const WATCH_TYPE_ANY: i32 = 3;

/// Deduplicates collected watcher tokens retaining first occurrence, then reverses the sequence
/// for delivery.
///
/// Faithfully reproduces the C client's delivery queue construction, where handlers are discovered
/// sequentially and prepended onto a singly-linked delivery list (`add_to_list`), resulting in
/// reversed dispatch order.
pub fn fan_out<W: Eq, I: IntoIterator<Item = W>>(collected: I) -> Vec<W> {
    let mut kept: Vec<W> = Vec::new();
    for w in collected {
        if !kept.contains(&w) {
            kept.push(w);
        }
    }
    kept.reverse();
    kept
}

/// Path-to-watcher mapping table representing one watcher category in the legacy C client.
///
/// Per-path registration lists maintain head-first insertion order. Paths are pruned immediately
/// once their associated registration list is drained.
#[derive(Debug, Clone)]
pub struct LegacyWatchTable<W> {
    table: LegacyHashTable<Vec<W>>,
}

impl<W: Clone + Eq> LegacyWatchTable<W> {
    /// Initializes an empty watcher table preallocated with legacy C default capacity (53 hash
    /// buckets).
    pub fn new(c_char_signed: bool) -> Self {
        Self {
            table: LegacyHashTable::new(c_char_signed),
        }
    }

    /// Initializes an empty watcher table configured with the target platform's C `char`
    /// signedness.
    pub fn new_native() -> Self {
        Self::new(TARGET_C_CHAR_SIGNED)
    }

    /// Provides immutable access to the underlying C-compatible hash table.
    pub fn table(&self) -> &LegacyHashTable<Vec<W>> {
        &self.table
    }

    /// Returns the total count of distinct znode paths currently tracked in the table.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Indicates whether the table contains zero active watch registrations.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }

    /// Registers a watcher handle for the designated path, mimicking `activateWatcher`.
    ///
    /// Prepends the watcher to the head of the path's registration list. Returns `false` without
    /// modifying the list if an identical watcher registration already exists for that path.
    pub fn insert(&mut self, path: &[u8], watcher: W) -> bool {
        match self.table.get_mut(path) {
            Some(list) => {
                if list.contains(&watcher) {
                    false
                } else {
                    list.insert(0, watcher);
                    true
                }
            }
            None => {
                self.table.insert(path, vec![watcher]);
                true
            }
        }
    }

    /// Checks whether the designated path has an active registration matching `watcher`
    /// (`containsWatcher`).
    ///
    /// Passing `None` checks for the presence of any watch on the path, matching historical C
    /// semantics.
    pub fn contains(&self, path: &[u8], watcher: Option<&W>) -> bool {
        let Some(w) = watcher else {
            return true;
        };
        self.table.get(path).is_some_and(|list| list.contains(w))
    }

    /// Deregisters a watcher handle from the path's registration list (`removeWatcher`).
    ///
    /// If `watcher` is `None`, the path and all its associated registrations are removed.
    /// When the registration list becomes empty, the path entry is removed from the table.
    /// Removal follows `removeWatcherFromList`: first the earliest match after the head, then
    /// the head element itself if matched.
    pub fn remove(&mut self, path: &[u8], watcher: Option<&W>) {
        let Some(w) = watcher else {
            self.table.remove(path);
            return;
        };
        let Some(list) = self.table.get_mut(path) else {
            return;
        };
        if let Some(pos) = list.iter().skip(1).position(|x| x == w) {
            if let Some(index) = pos.checked_add(1) {
                if index < list.len() {
                    list.remove(index);
                }
            }
        }
        if list.first() == Some(w) {
            list.remove(0);
        }
        if list.is_empty() {
            self.table.remove(path);
        }
    }

    /// Removes a path from the table, returning its registered watchers in head-to-tail order.
    pub fn take(&mut self, path: &[u8]) -> Vec<W> {
        self.table.remove(path).unwrap_or_default()
    }

    /// Returns an iterator yielding registered watchers for the path in head-to-tail order.
    pub fn watchers(&self, path: &[u8]) -> impl Iterator<Item = &W> + '_ {
        self.table.get(path).into_iter().flatten()
    }

    /// Iterates across all tracked znode paths in legacy C hash table bucket traversal order.
    pub fn keys(&self) -> impl Iterator<Item = &[u8]> + '_ {
        self.table.keys()
    }

    /// Iterates across all tracked paths and their associated watcher lists in legacy C traversal
    /// order.
    pub fn iter(&self) -> impl Iterator<Item = (&[u8], &[W])> + '_ {
        self.table.iter().map(|(k, v)| (k, v.as_slice()))
    }

    /// Purges all paths and watch registrations from the table while retaining allocated bucket
    /// capacity.
    pub fn clear(&mut self) {
        self.table.clear();
    }
}

/// Aggregate container encapsulating the three active watcher tables of a ZooKeeper client session.
#[derive(Debug, Clone)]
pub struct LegacyWatchers<W> {
    /// Active data modification watchers (`active_node_watchers`).
    pub data: LegacyWatchTable<W>,
    /// Active node existence watchers (`active_exist_watchers`).
    pub exist: LegacyWatchTable<W>,
    /// Active child list modification watchers (`active_child_watchers`).
    pub child: LegacyWatchTable<W>,
}

impl<W: Clone + Eq> LegacyWatchers<W> {
    /// Constructs an aggregate watcher repository with three empty category tables.
    pub fn new(c_char_signed: bool) -> Self {
        Self {
            data: LegacyWatchTable::new(c_char_signed),
            exist: LegacyWatchTable::new(c_char_signed),
            child: LegacyWatchTable::new(c_char_signed),
        }
    }

    /// Constructs an aggregate watcher repository initialized with the platform's default C `char`
    /// signedness.
    pub fn new_native() -> Self {
        Self::new(TARGET_C_CHAR_SIGNED)
    }

    /// Gathers and removes triggered watchers for a specific znode event, yielding them in
    /// dispatch order (`collectWatchers`).
    ///
    /// The paths consumed by the event are purged from their respective tables.
    /// Evaluation sequence:
    /// - `CREATED_EVENT` and `CHANGED_EVENT`: inspects `data`, then `exist`.
    /// - `CHILD_EVENT`: inspects `child`.
    /// - `DELETED_EVENT`: inspects `data`, `exist`, then `child`.
    pub fn collect_node_event(&mut self, event_type: i32, path: &[u8]) -> Vec<W> {
        let mut seq: Vec<W> = Vec::new();
        match event_type {
            CREATED_EVENT | CHANGED_EVENT => {
                seq.extend(self.data.take(path));
                seq.extend(self.exist.take(path));
            }
            CHILD_EVENT => {
                seq.extend(self.child.take(path));
            }
            DELETED_EVENT => {
                seq.extend(self.data.take(path));
                seq.extend(self.exist.take(path));
                seq.extend(self.child.take(path));
            }
            _ => {}
        }
        fan_out(seq)
    }

    /// Gathers all registered watchers for a session lifecycle notification (`collectWatchers`
    /// with `ZOO_SESSION_EVENT`).
    ///
    /// Collects the session default watcher followed by all registered watchers across `data`,
    /// `exist`, and `child` tables without deregistering them. Due to final queue reversal,
    /// the session default watcher is dispatched last.
    pub fn collect_session_event(&self, default_watcher: &W) -> Vec<W> {
        let mut seq: Vec<&W> = vec![default_watcher];
        for table in [&self.data, &self.exist, &self.child] {
            for (_, list) in table.iter() {
                seq.extend(list.iter());
            }
        }
        fan_out(seq).into_iter().cloned().collect()
    }

    /// Dispatches watcher collection for any ZooKeeper event type, selecting between node and
    /// session routines.
    pub fn collect(&mut self, event_type: i32, path: &[u8], default_watcher: &W) -> Vec<W> {
        if event_type == SESSION_EVENT {
            self.collect_session_event(default_watcher)
        } else {
            self.collect_node_event(event_type, path)
        }
    }

    /// Verifies whether a path holds an active watch registration within the specified watch
    /// category (`pathHasWatcher`).
    pub fn has_watcher(&self, path: &[u8], watch_type: i32, watcher: Option<&W>) -> bool {
        match watch_type {
            WATCH_TYPE_CHILD => self.child.contains(path, watcher),
            WATCH_TYPE_DATA => {
                self.data.contains(path, watcher) || self.exist.contains(path, watcher)
            }
            WATCH_TYPE_ANY => {
                self.child.contains(path, watcher)
                    || self.data.contains(path, watcher)
                    || self.exist.contains(path, watcher)
            }
            _ => false,
        }
    }

    /// Removes matching watch registrations from the appropriate category tables
    /// (`removeWatchers`).
    pub fn remove_watchers(&mut self, path: &[u8], watch_type: i32, watcher: Option<&W>) {
        match watch_type {
            WATCH_TYPE_CHILD => self.child.remove(path, watcher),
            WATCH_TYPE_DATA => {
                self.data.remove(path, watcher);
                self.exist.remove(path, watcher);
            }
            WATCH_TYPE_ANY => {
                self.child.remove(path, watcher);
                self.data.remove(path, watcher);
                self.exist.remove(path, watcher);
            }
            _ => {}
        }
    }
}
