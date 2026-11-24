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

//! ZooKeeper watcher registration and event dispatch registry.
//!
//! This module manages [`Watches`], which tracks armed client side watch registrations across
//! data, exist, child, persistent, and persistent recursive watch categories. When responses
//! confirm registration, watches are armed in the appropriate tables; when incoming server events
//! arrive, matching watchers are collected and dispatched, unregistering one-shot watches while
//! retaining persistent ones. During session re-establishment, active registrations are compiled
//! into `SetWatches` or `SetWatches2` wire packets.
//!
//! Internal watch tables index paths using their full server side paths, including the configured
//! client chroot prefix, matching C client behavior and allowing direct transmission in
//! `SetWatches` without runtime path reconstruction. Chroot prefixes are stripped exclusively when
//! constructing application-facing [`WatchedEvent`] notifications.

use std::collections::{BTreeMap, HashMap};

use zk_jute::proto::{SetWatches, SetWatches2, WatcherEvent};
use zk_jute::{JString, JVec, Record, opcode, rc, xid};

use crate::action::RemoveHint;
use crate::compat::WatchTable;
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::legacy::chroot::is_config_node;
use crate::legacy::watchers::{
    CHANGED_EVENT, CHILD_EVENT, CREATED_EVENT, DELETED_EVENT, LegacyWatchTable, LegacyWatchers,
    SESSION_EVENT, fan_out,
};
use crate::log::{LogArg, LogRecord, LogSite, str_arg};
use crate::output::{Output, TerminateReason, WatchedEvent};
use crate::recv::Flow;
use crate::requests::{FrameKind, OutFrame, Unwatch, WatchKind, WatchReg, completion};
use crate::state::Cause;
use crate::time::Millis;

/// ZooKeeper protocol watch type targeting child node changes (`ZWATCHTYPE_CHILD`).
const WTYPE_CHILD: i32 = 1;
/// ZooKeeper protocol watch type targeting data changes and node existence (`ZWATCHTYPE_DATA`).
const WTYPE_DATA: i32 = 2;
/// ZooKeeper protocol watch type matching any registered watch category (`ZWATCHTYPE_ANY`).
const WTYPE_ANY: i32 = 3;
/// ZooKeeper protocol watch type targeting persistent watches (`ZWATCHTYPE_PERSISTENT`, native mode
/// only).
const WTYPE_PERSISTENT: i32 = 4;
/// ZooKeeper protocol watch type targeting persistent recursive watches (`ZWATCHTYPE_RECURSIVE`,
/// native mode only).
const WTYPE_RECURSIVE: i32 = 5;

/// Ordered enumeration of all watch kinds matching the sequence of lists in `SetWatches` /
/// `SetWatches2` packets.
const ALL_KINDS: [WatchKind; 5] = [
    WatchKind::Data,
    WatchKind::Exist,
    WatchKind::Child,
    WatchKind::Persistent,
    WatchKind::PersistentRecursive,
];

/// Maps a watch kind to its corresponding wire-level watch type category on the server.
///
/// Data and existence watches share the unified data watch class on the ZooKeeper server.
const fn class_wtype(kind: WatchKind) -> i32 {
    match kind {
        WatchKind::Data | WatchKind::Exist => WTYPE_DATA,
        WatchKind::Child => WTYPE_CHILD,
        WatchKind::Persistent => WTYPE_PERSISTENT,
        WatchKind::PersistentRecursive => WTYPE_RECURSIVE,
    }
}

/// Returns the sequence of watch kinds selected by a wire watch type, matching Java client removal
/// precedence.
const fn ordered_kinds(wtype: i32) -> &'static [WatchKind] {
    match wtype {
        WTYPE_CHILD => &[WatchKind::Child],
        WTYPE_DATA => &[WatchKind::Data, WatchKind::Exist],
        WTYPE_ANY => &[
            WatchKind::Child,
            WatchKind::Data,
            WatchKind::Exist,
            WatchKind::Persistent,
            WatchKind::PersistentRecursive,
        ],
        WTYPE_PERSISTENT => &[WatchKind::Persistent],
        WTYPE_RECURSIVE => &[WatchKind::PersistentRecursive],
        _ => &[],
    }
}

/// Returns the legacy watch kinds selected by a wire watch type in `removeWatchers` order.
///
/// Legacy C client tables do not support persistent or recursive watch kinds.
const fn legacy_kinds(wtype: i32) -> &'static [WatchKind] {
    match wtype {
        WTYPE_CHILD => &[WatchKind::Child],
        WTYPE_DATA => &[WatchKind::Data, WatchKind::Exist],
        WTYPE_ANY => &[WatchKind::Child, WatchKind::Data, WatchKind::Exist],
        _ => &[],
    }
}

/// Determines the target watch table armed by a successful operation reply.
///
/// An `exists` watch registration arms the data table upon `ZOK` (since the node exists) and
/// arms the exist table upon `ZNONODE` (awaiting node creation). A `getData` watch arms only upon
/// `ZOK`.
const fn armed_table(kind: WatchKind, rc_value: i32) -> Option<WatchKind> {
    match kind {
        WatchKind::Exist if rc_value == rc::ZOK => Some(WatchKind::Data),
        WatchKind::Exist if rc_value == rc::ZNONODE => Some(WatchKind::Exist),
        WatchKind::Exist => None,
        _ if rc_value == rc::ZOK => Some(kind),
        _ => None,
    }
}

/// Deduplicates elements in a vector, retaining the first occurrence of each element while
/// preserving relative order.
fn dedup_keep_first<W: Eq>(seq: Vec<W>) -> Vec<W> {
    let mut kept: Vec<W> = Vec::with_capacity(seq.len());
    for w in seq {
        if !kept.contains(&w) {
            kept.push(w);
        }
    }
    kept
}

/// Returns a node path followed by each of its ancestral parent directories up to `/`, ordered
/// nearest ancestor first.
///
/// Mirrors the hierarchical traversal order of the Java client `PathParentIterator`.
fn path_lineage(path: &[u8]) -> Vec<&[u8]> {
    let mut out: Vec<&[u8]> = vec![path];
    if path.first() != Some(&b'/') {
        return out;
    }
    let mut cur = path;
    while cur.len() > 1 {
        cur = match cur.iter().rposition(|&b| b == b'/') {
            Some(0) | None => b"/",
            Some(i) => cur.get(..i).unwrap_or(b"/"),
        };
        out.push(cur);
    }
    out
}

/// Returns the textual representation of a ZooKeeper watcher event type matching C client
/// `watcherEvent2String` output.
const fn event_name(event_type: i32) -> &'static [u8] {
    match event_type {
        0 => b"ZOO_ERROR_EVENT",
        CREATED_EVENT => b"ZOO_CREATED_EVENT",
        DELETED_EVENT => b"ZOO_DELETED_EVENT",
        CHANGED_EVENT => b"ZOO_CHANGED_EVENT",
        CHILD_EVENT => b"ZOO_CHILD_EVENT",
        SESSION_EVENT => b"ZOO_SESSION_EVENT",
        -2 => b"ZOO_NOTWATCHING_EVENT",
        _ => b"INVALID_EVENT",
    }
}

/// An insertion-ordered path-keyed map associating znode paths with their registered watcher
/// collections.
///
/// Assigns monotonically increasing 64-bit sequence numbers to newly inserted paths to guarantee
/// stable iteration ordering matching registration history. If a path is completely removed and
/// subsequently re-added, it receives a new sequence number, moving to the end of the iteration
/// sequence.
pub(crate) struct PathMap<W> {
    /// Stored entries indexed by assigned sequence number.
    entries: BTreeMap<u64, (Vec<u8>, Vec<W>)>,
    /// Lookup index mapping paths to their current sequence numbers.
    index: HashMap<Vec<u8>, u64>,
    /// Monotonically increasing counter for assigning sequence numbers to newly encountered paths.
    next_seq: u64,
}

impl<W> PathMap<W> {
    /// Constructs a new, empty path map.
    pub(crate) fn new() -> Self {
        Self {
            entries: BTreeMap::new(),
            index: HashMap::new(),
            next_seq: 0,
        }
    }

    /// Retrieves the registered watcher list for a specific path, if present.
    pub(crate) fn get(&self, path: &[u8]) -> Option<&Vec<W>> {
        let seq = self.index.get(path)?;
        self.entries.get(seq).map(|(_, list)| list)
    }

    /// Retrieves a mutable reference to the registered watcher list for a specific path.
    pub(crate) fn get_mut(&mut self, path: &[u8]) -> Option<&mut Vec<W>> {
        let seq = self.index.get(path)?;
        self.entries.get_mut(seq).map(|(_, list)| list)
    }

    /// Accesses the watcher list for a path, initializing a new empty list at the end of the
    /// insertion order if absent.
    pub(crate) fn entry_or_default(&mut self, path: &[u8]) -> &mut Vec<W> {
        let seq = match self.index.get(path) {
            Some(seq) => *seq,
            None => {
                let seq = self.take_seq();
                self.index.insert(path.to_vec(), seq);
                seq
            }
        };
        &mut self
            .entries
            .entry(seq)
            .or_insert_with(|| (path.to_vec(), Vec::new()))
            .1
    }

    /// Removes a path and its associated watchers from the map, returning the watcher list if it
    /// was present.
    pub(crate) fn remove(&mut self, path: &[u8]) -> Option<Vec<W>> {
        let seq = self.index.remove(path)?;
        self.entries.remove(&seq).map(|(_, list)| list)
    }

    /// Iterates over all registered paths and their watcher lists in chronological insertion order.
    pub(crate) fn iter(&self) -> impl Iterator<Item = (&Vec<u8>, &Vec<W>)> {
        self.entries.values().map(|(path, list)| (path, list))
    }

    /// Iterates over all registered paths in chronological insertion order.
    pub(crate) fn keys(&self) -> impl Iterator<Item = &Vec<u8>> {
        self.entries.values().map(|(path, _)| path)
    }

    /// Iterates over all registered watcher lists in chronological insertion order.
    pub(crate) fn values(&self) -> impl Iterator<Item = &Vec<W>> {
        self.entries.values().map(|(_, list)| list)
    }

    /// Allocates the next sequence number, compacting and re-numbering existing entries from zero
    /// if the counter saturates.
    fn take_seq(&mut self) -> u64 {
        if self.next_seq == u64::MAX {
            let old = core::mem::take(&mut self.entries);
            self.index.clear();
            self.next_seq = 0;
            for (path, list) in old.into_values() {
                let seq = self.next_seq;
                self.next_seq = self.next_seq.saturating_add(1);
                self.index.insert(path.clone(), seq);
                self.entries.insert(seq, (path, list));
            }
        }
        let seq = self.next_seq;
        self.next_seq = self.next_seq.saturating_add(1);
        seq
    }
}

/// Native mode watch registry organizing active watches across dedicated insertion-ordered path
/// tables.
///
/// Paths within each category table iterate in chronological order of initial registration, and
/// individual watcher callbacks registered on a common path preserve their registration sequence.
pub(crate) struct OrderedWatches<W> {
    /// Active one-shot data change watches.
    pub(crate) data: PathMap<W>,
    /// Active one-shot node existence watches.
    pub(crate) exist: PathMap<W>,
    /// Active one-shot child node change watches.
    pub(crate) child: PathMap<W>,
    /// Active persistent watches triggered repeatedly on data and child events.
    pub(crate) persistent: PathMap<W>,
    /// Active persistent recursive watches triggered on events across the target path and its
    /// subtree.
    pub(crate) persistent_recursive: PathMap<W>,
}

impl<W> OrderedWatches<W> {
    /// Constructs a fresh set of empty insertion-ordered watch tables.
    pub(crate) fn new() -> Self {
        Self {
            data: PathMap::new(),
            exist: PathMap::new(),
            child: PathMap::new(),
            persistent: PathMap::new(),
            persistent_recursive: PathMap::new(),
        }
    }
}

impl<W: Clone + Eq> OrderedWatches<W> {
    /// Borrows the path table corresponding to a specific watch kind.
    fn table(&self, kind: WatchKind) -> &PathMap<W> {
        match kind {
            WatchKind::Data => &self.data,
            WatchKind::Exist => &self.exist,
            WatchKind::Child => &self.child,
            WatchKind::Persistent => &self.persistent,
            WatchKind::PersistentRecursive => &self.persistent_recursive,
        }
    }

    /// Mutably borrows the path table corresponding to a specific watch kind.
    fn table_mut(&mut self, kind: WatchKind) -> &mut PathMap<W> {
        match kind {
            WatchKind::Data => &mut self.data,
            WatchKind::Exist => &mut self.exist,
            WatchKind::Child => &mut self.child,
            WatchKind::Persistent => &mut self.persistent,
            WatchKind::PersistentRecursive => &mut self.persistent_recursive,
        }
    }

    /// Registers a watcher callback on the specified path under a given watch kind, returning true
    /// if added.
    ///
    /// Duplicate registrations of the identical watcher on the same path are suppressed, returning
    /// false.
    fn insert(&mut self, kind: WatchKind, path: &[u8], w: W) -> bool {
        let list = self.table_mut(kind).entry_or_default(path);
        if list.contains(&w) {
            false
        } else {
            list.push(w);
            true
        }
    }

    /// Checks whether the specified path holds an active watch registration.
    ///
    /// Passing `None` checks for the presence of any active watcher on the path, matching Java
    /// client semantics.
    fn contains(&self, kind: WatchKind, path: &[u8], w: Option<&W>) -> bool {
        self.table(kind).get(path).is_some_and(|list| match w {
            None => !list.is_empty(),
            Some(w) => list.contains(w),
        })
    }

    /// Unregisters a specific watcher (or all watchers if `None` is provided) from the given path
    /// and kind.
    ///
    /// Cleans up empty path entries from the underlying map and returns the list of removed
    /// watchers.
    fn remove(&mut self, kind: WatchKind, path: &[u8], w: Option<&W>) -> Vec<W> {
        let table = self.table_mut(kind);
        let Some(w) = w else {
            return table.remove(path).unwrap_or_default();
        };
        let Some(list) = table.get_mut(path) else {
            return Vec::new();
        };
        let mut removed = Vec::new();
        list.retain(|x| {
            if x == w {
                removed.push(x.clone());
                false
            } else {
                true
            }
        });
        if list.is_empty() {
            table.remove(path);
        }
        removed
    }

    /// Removes a path entry completely from the specified table, returning its registered watchers
    /// in registration order.
    fn take(&mut self, kind: WatchKind, path: &[u8]) -> Vec<W> {
        self.table_mut(kind).remove(path).unwrap_or_default()
    }

    /// Clones the registered watchers for a path without modifying table state.
    fn copy(&self, kind: WatchKind, path: &[u8]) -> Vec<W> {
        self.table(kind).get(path).cloned().unwrap_or_default()
    }

    /// Collects and dispatches matching watchers for an incoming znode event under native ordered
    /// table semantics.
    ///
    /// One-shot watch tables (exist, data, child) are drained and removed upon triggering.
    /// Persistent watches are preserved and appended for all node events. Persistent recursive
    /// watches are evaluated across the target path and its ancestor hierarchy for node creation,
    /// modification, and deletion events.
    fn collect_node_event(&mut self, event_type: i32, path: &[u8]) -> Vec<W> {
        let mut seq: Vec<W> = Vec::new();
        match event_type {
            CREATED_EVENT | CHANGED_EVENT => {
                seq.extend(self.take(WatchKind::Exist, path));
                seq.extend(self.take(WatchKind::Data, path));
            }
            CHILD_EVENT => {
                seq.extend(self.take(WatchKind::Child, path));
            }
            DELETED_EVENT => {
                seq.extend(self.take(WatchKind::Child, path));
                seq.extend(self.take(WatchKind::Exist, path));
                seq.extend(self.take(WatchKind::Data, path));
            }
            _ => return Vec::new(),
        }
        seq.extend(self.copy(WatchKind::Persistent, path));
        if event_type != CHILD_EVENT {
            for p in path_lineage(path) {
                seq.extend(self.copy(WatchKind::PersistentRecursive, p));
            }
        }
        dedup_keep_first(seq)
    }
}

/// Underlying storage engine managing watch tables across protocol compatibility profiles.
pub(crate) enum Store<W> {
    /// Legacy hash table implementation faithfully reproducing C client hash table mechanics and
    /// iteration anomalies.
    Legacy(LegacyWatchers<W>),
    /// Heap-allocated native insertion-ordered tables supporting persistent and recursive watches.
    Ordered(Box<OrderedWatches<W>>),
}

/// Comprehensive client side ZooKeeper watch registry.
pub(crate) struct Watches<W> {
    /// Active watch storage backing engine.
    pub(crate) store: Store<W>,
}

/// Aggregate collection of active watch paths across all categories, prepared for `SetWatches`
/// batch encoding.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct WatchKeys {
    /// Active data watch paths.
    pub(crate) data: Vec<Vec<u8>>,
    /// Active existence watch paths.
    pub(crate) exist: Vec<Vec<u8>>,
    /// Active child watch paths.
    pub(crate) child: Vec<Vec<u8>>,
    /// Active persistent watch paths.
    pub(crate) persistent: Vec<Vec<u8>>,
    /// Active persistent recursive watch paths.
    pub(crate) recursive: Vec<Vec<u8>>,
}

impl WatchKeys {
    /// Returns true if no registered watch paths exist across any category.
    pub(crate) fn is_empty(&self) -> bool {
        self.data.is_empty()
            && self.exist.is_empty()
            && self.child.is_empty()
            && self.persistent.is_empty()
            && self.recursive.is_empty()
    }
}

/// Borrows the corresponding legacy watch table for a kind, returning `None` for unsupported
/// persistent categories.
fn legacy_table<W>(l: &LegacyWatchers<W>, kind: WatchKind) -> Option<&LegacyWatchTable<W>> {
    match kind {
        WatchKind::Data => Some(&l.data),
        WatchKind::Exist => Some(&l.exist),
        WatchKind::Child => Some(&l.child),
        WatchKind::Persistent | WatchKind::PersistentRecursive => None,
    }
}

/// Mutably borrows the corresponding legacy watch table for a kind, returning `None` for
/// unsupported persistent categories.
fn legacy_table_mut<W>(
    l: &mut LegacyWatchers<W>,
    kind: WatchKind,
) -> Option<&mut LegacyWatchTable<W>> {
    match kind {
        WatchKind::Data => Some(&mut l.data),
        WatchKind::Exist => Some(&mut l.exist),
        WatchKind::Child => Some(&mut l.child),
        WatchKind::Persistent | WatchKind::PersistentRecursive => None,
    }
}

/// Unregisters a watcher from a single legacy table matching C client `removeWatcher` semantics,
/// returning removed entries.
fn legacy_remove<W: Clone + Eq>(
    table: &mut LegacyWatchTable<W>,
    path: &[u8],
    w: Option<&W>,
) -> Vec<W> {
    match w {
        None => table.take(path),
        Some(w) => {
            if table.contains(path, Some(w)) {
                table.remove(path, Some(w));
                vec![w.clone()]
            } else {
                Vec::new()
            }
        }
    }
}

impl<W: Clone + Eq> Watches<W> {
    /// Instantiates an empty watch registry configured with the requested table engine and C
    /// character signedness.
    pub(crate) fn new(table: WatchTable, c_char_signed: bool) -> Self {
        let store = match table {
            WatchTable::LegacyHashtable => Store::Legacy(LegacyWatchers::new(c_char_signed)),
            WatchTable::Ordered => Store::Ordered(Box::new(OrderedWatches::new())),
        };
        Self { store }
    }

    /// Indicates whether a watcher matching the specified type filter is actively armed on the
    /// path.
    ///
    /// In legacy hash table mode, this mirrors C client `pathHasWatcher` behavior where passing
    /// `None` unconditionally matches if the type is valid. In native ordered table mode, passing
    /// `None` checks whether any watcher of that category is registered on the path.
    pub(crate) fn has(&self, path: &[u8], wtype: i32, w: Option<&W>) -> bool {
        match &self.store {
            Store::Legacy(l) => l.has_watcher(path, wtype, w),
            Store::Ordered(o) => ordered_kinds(wtype)
                .iter()
                .any(|kind| o.contains(*kind, path, w)),
        }
    }

    /// Unregisters watchers matching the given path and type filter, returning the removed watchers
    /// in removal order.
    pub(crate) fn remove(&mut self, path: &[u8], wtype: i32, w: Option<&W>) -> Vec<W> {
        let mut removed = Vec::new();
        match &mut self.store {
            Store::Legacy(l) => {
                for kind in legacy_kinds(wtype) {
                    if let Some(table) = legacy_table_mut(l, *kind) {
                        removed.extend(legacy_remove(table, path, w));
                    }
                }
            }
            Store::Ordered(o) => {
                for kind in ordered_kinds(wtype) {
                    removed.extend(o.remove(*kind, path, w));
                }
            }
        }
        dedup_keep_first(removed)
    }

    /// Arms a watch registration within the corresponding category table based on operation return
    /// code.
    ///
    /// Returns true if the registration was accepted and false if suppressed (e.g. error return
    /// code or duplicate).
    pub(crate) fn arm(&mut self, kind: WatchKind, path: &[u8], w: W, rc_value: i32) -> bool {
        let Some(target) = armed_table(kind, rc_value) else {
            return false;
        };
        match &mut self.store {
            Store::Legacy(l) => legacy_table_mut(l, target).is_some_and(|t| t.insert(path, w)),
            Store::Ordered(o) => o.insert(target, path, w),
        }
    }

    /// Collects and dispatches registered watchers for a znode event in delivery order, purging
    /// triggered one-shot watches.
    pub(crate) fn collect_event(&mut self, event_type: i32, path: &[u8]) -> Vec<W> {
        match &mut self.store {
            Store::Legacy(l) => l.collect_node_event(event_type, path),
            Store::Ordered(o) => o.collect_node_event(event_type, path),
        }
    }

    /// Gathers watchers to be notified of session state transitions in delivery order without
    /// mutating table registrations.
    ///
    /// When session fan-out is enabled, legacy tables reproduce C client ordering (path watchers in
    /// reverse collection order followed by default watcher), while native tables dispatch all path
    /// watchers in table order before the default watcher. When session fan-out is disabled,
    /// notifications are routed solely to the default watcher.
    pub(crate) fn session(&self, default: Option<W>, fanout: bool) -> Vec<W> {
        if !fanout {
            return default.into_iter().collect();
        }
        match (&self.store, default) {
            (Store::Legacy(l), Some(d)) => l.collect_session_event(&d),
            (Store::Legacy(l), None) => {
                let mut seq: Vec<&W> = Vec::new();
                for table in [&l.data, &l.exist, &l.child] {
                    for (_, list) in table.iter() {
                        seq.extend(list.iter());
                    }
                }
                fan_out(seq).into_iter().cloned().collect()
            }
            (Store::Ordered(_), default) => {
                let mut all = self.all_watchers();
                if let Some(d) = default {
                    all.retain(|w| *w != d);
                    all.push(d);
                }
                all
            }
        }
    }

    /// Enumerates all currently armed watchers across all category tables (data, exist, child,
    /// persistent, recursive).
    pub(crate) fn all_watchers(&self) -> Vec<W> {
        let mut seq: Vec<W> = Vec::new();
        match &self.store {
            Store::Legacy(l) => {
                for table in [&l.data, &l.exist, &l.child] {
                    for (_, list) in table.iter() {
                        seq.extend(list.iter().cloned());
                    }
                }
            }
            Store::Ordered(o) => {
                for kind in ALL_KINDS {
                    for list in o.table(kind).values() {
                        seq.extend(list.iter().cloned());
                    }
                }
            }
        }
        dedup_keep_first(seq)
    }

    /// Purges all watch registrations from all category tables, returning the unique set of cleared
    /// watchers.
    pub(crate) fn clear(&mut self) -> Vec<W> {
        let all = self.all_watchers();
        match &mut self.store {
            Store::Legacy(l) => {
                l.data.clear();
                l.exist.clear();
                l.child.clear();
            }
            Store::Ordered(o) => **o = OrderedWatches::new(),
        }
        all
    }

    /// Verifies whether the server watch class corresponding to a watch kind remains armed by at
    /// least one watcher on the path.
    fn class_in_use(&self, kind: WatchKind, path: &[u8]) -> bool {
        self.has(path, class_wtype(kind), None) && {
            // Legacy C hash tables return true for `None` regardless of path occupancy,
            // requiring an explicit inspection of the underlying bucket list.
            match &self.store {
                Store::Legacy(l) => match kind {
                    WatchKind::Data | WatchKind::Exist => {
                        l.data.watchers(path).next().is_some()
                            || l.exist.watchers(path).next().is_some()
                    }
                    _ => legacy_table(l, kind).is_some_and(|t| t.watchers(path).next().is_some()),
                },
                Store::Ordered(_) => true,
            }
        }
    }

    /// Unregisters a watcher from all category tables across all paths.
    ///
    /// Returns a list of `(kind, path)` pairs where removing the watcher leaves the path with no
    /// remaining active watchers belonging to that server watch class.
    pub(crate) fn detach(&mut self, w: &W) -> Vec<(WatchKind, Vec<u8>)> {
        let mut touched: Vec<(WatchKind, Vec<u8>)> = Vec::new();
        match &mut self.store {
            Store::Legacy(l) => {
                for kind in [WatchKind::Data, WatchKind::Exist, WatchKind::Child] {
                    if let Some(table) = legacy_table_mut(l, kind) {
                        let paths: Vec<Vec<u8>> = table
                            .iter()
                            .filter(|(_, list)| list.contains(w))
                            .map(|(p, _)| p.to_vec())
                            .collect();
                        for p in paths {
                            table.remove(&p, Some(w));
                            touched.push((kind, p));
                        }
                    }
                }
            }
            Store::Ordered(o) => {
                for kind in ALL_KINDS {
                    let paths: Vec<Vec<u8>> = o
                        .table(kind)
                        .iter()
                        .filter(|(_, list)| list.contains(w))
                        .map(|(p, _)| p.clone())
                        .collect();
                    for p in paths {
                        o.remove(kind, &p, Some(w));
                        touched.push((kind, p));
                    }
                }
            }
        }
        touched
            .into_iter()
            .filter(|(kind, p)| !self.class_in_use(*kind, p))
            .collect()
    }

    /// Extracts all active watch paths across all category tables in table iteration order.
    pub(crate) fn keys(&self) -> WatchKeys {
        match &self.store {
            Store::Legacy(l) => WatchKeys {
                data: l.data.keys().map(<[u8]>::to_vec).collect(),
                exist: l.exist.keys().map(<[u8]>::to_vec).collect(),
                child: l.child.keys().map(<[u8]>::to_vec).collect(),
                persistent: Vec::new(),
                recursive: Vec::new(),
            },
            Store::Ordered(o) => WatchKeys {
                data: o.data.keys().cloned().collect(),
                exist: o.exist.keys().cloned().collect(),
                child: o.child.keys().cloned().collect(),
                persistent: o.persistent.keys().cloned().collect(),
                recursive: o.persistent_recursive.keys().cloned().collect(),
            },
        }
    }
}

/// Encodes a `SetWatches` (opcode 101) or `SetWatches2` (opcode 105) request packet body depending
/// on presence of persistent watches.
fn encode_set_watches(batch: [Vec<JString>; 5], relative_zxid: i64) -> (i32, Vec<u8>) {
    let [data, exist, child, persistent, recursive] = batch;
    if persistent.is_empty() && recursive.is_empty() {
        let req = SetWatches {
            relative_zxid,
            data_watches: JVec::from(data),
            exist_watches: JVec::from(exist),
            child_watches: JVec::from(child),
        };
        (opcode::SET_WATCHES, req.to_bytes())
    } else {
        let req = SetWatches2 {
            relative_zxid,
            data_watches: JVec::from(data),
            exist_watches: JVec::from(exist),
            child_watches: JVec::from(child),
            persistent_watches: JVec::from(persistent),
            persistent_recursive_watches: JVec::from(recursive),
        };
        (opcode::SET_WATCHES2, req.to_bytes())
    }
}

/// Partitions registered watch paths into batched `SetWatches` request packet frames respecting
/// byte payload limits.
///
/// Watch paths are batched in table order up to `limit` total path bytes per packet. Individual
/// paths exceeding the batch limit are isolated into dedicated single-entry packets to guarantee
/// complete re-registration.
pub(crate) fn set_watches_packets(
    keys: &WatchKeys,
    relative_zxid: i64,
    limit: Option<u32>,
) -> Vec<(i32, Vec<u8>)> {
    let limit = limit.map_or(usize::MAX, |l| usize::try_from(l).unwrap_or(usize::MAX));
    let mut packets = Vec::new();
    let mut batch: [Vec<JString>; 5] = Default::default();
    let mut batch_bytes: usize = 0;
    let mut batch_keys: usize = 0;
    let sources = [
        &keys.data,
        &keys.exist,
        &keys.child,
        &keys.persistent,
        &keys.recursive,
    ];
    for (slot, list) in sources.iter().enumerate() {
        for key in list.iter() {
            if batch_keys > 0 && batch_bytes.saturating_add(key.len()) > limit {
                packets.push(encode_set_watches(
                    core::mem::take(&mut batch),
                    relative_zxid,
                ));
                batch_bytes = 0;
                batch_keys = 0;
            }
            if let Some(dst) = batch.get_mut(slot) {
                dst.push(JString::Bytes(key.clone()));
            }
            batch_bytes = batch_bytes.saturating_add(key.len());
            batch_keys = batch_keys.saturating_add(1);
        }
    }
    if batch_keys > 0 {
        packets.push(encode_set_watches(batch, relative_zxid));
    }
    packets
}

impl<T: Tokens> Connection<T> {
    /// Indicates whether native watch dispatch semantics apply (suppressing empty watch
    /// notifications and emitting `WatchTerminated`).
    fn watches_native(&self) -> bool {
        !self.compat.session_fanout_to_path_watchers
    }

    /// Emits a [`Output::WatchTerminated`] notification for unregistered watchers when running
    /// under native semantics.
    fn watches_push_terminated(&mut self, watchers: Vec<T::Watcher>, reason: TerminateReason) {
        if self.watches_native() && !watchers.is_empty() {
            self.out.push(Output::WatchTerminated { watchers, reason });
        }
    }

    /// Determines whether an outstanding in-flight request carries an armed watch registration of
    /// this server class on the path.
    fn watches_in_flight(&self, server_path: &[u8], wtype: i32) -> bool {
        self.requests.fifo.iter().any(|p| {
            p.watch
                .as_ref()
                .is_some_and(|r| r.path == server_path && class_wtype(r.kind) == wtype)
        })
    }

    /// Translates an internal server path into its application-facing client representation by
    /// stripping the configured chroot prefix.
    fn watches_client_path(&self, server_path: &[u8]) -> JString {
        if self.cfg.chroot.is_none() {
            return JString::Bytes(server_path.to_vec());
        }
        let mut logs = Vec::new();
        self.strip_path(server_path, &mut logs)
            .unwrap_or_else(|| JString::Bytes(server_path.to_vec()))
    }

    /// Checks whether an armed watch matching the specified wire type filter is registered on the
    /// given server path.
    ///
    /// Accepts wire types: 1 (child), 2 (data and exist), 3 (any), and natively 4 (persistent) and
    /// 5 (persistent recursive).
    #[must_use]
    pub fn has_watcher(&self, server_path: &[u8], wtype: i32, w: Option<&T::Watcher>) -> bool {
        self.watches.has(server_path, wtype, w)
    }

    /// Unregisters all occurrences of a watcher across all paths and categories, returning an
    /// optional server removal hint.
    ///
    /// If removing the watcher completely exhausts all active client registrations for that path
    /// and server watch class, and no pending in-flight requests hold an unfulfilled registration
    /// of the same class, this method returns a [`RemoveHint`] enabling the driver to issue an
    /// explicit server side `removeWatches` request.
    pub fn detach_watcher(&mut self, w: &T::Watcher) -> Option<RemoveHint> {
        let emptied = self.watches.detach(w);
        for (kind, path) in emptied {
            let wtype = class_wtype(kind);
            if self.watches_in_flight(&path, wtype) {
                continue;
            }
            return Some(RemoveHint {
                path: self.watches_client_path(&path),
                wtype,
            });
        }
        None
    }

    /// Arms a watch registration upon receiving a successful operation reply.
    ///
    /// Data, child, and persistent watches arm upon `ZOK`. Existence watches arm in the data table
    /// upon `ZOK` (the node exists) and in the exist table upon `ZNONODE` (awaiting node
    /// creation). Error replies do not arm watches.
    pub(crate) fn arm_watch(&mut self, reg: WatchReg<T::Watcher>, rc: i32) {
        self.watches.arm(reg.kind, &reg.path, reg.watcher, rc);
    }

    /// Removes local watch registrations following a successful server side `removeWatches` reply
    /// and emits termination notifications.
    pub(crate) fn apply_unwatch(&mut self, u: Unwatch<T::Watcher>) {
        let removed = self.watches.remove(&u.path, u.wtype, u.watcher.as_ref());
        self.watches_push_terminated(removed, TerminateReason::Removed);
    }

    /// Validates and optionally executes local watch removal during client-initiated
    /// `removeWatches` request preparation.
    ///
    /// Returns `Err(ZNOWATCHER)` if no matching active watch is found on the path. If `local` is
    /// enabled, the matching registrations are excised immediately.
    pub(crate) fn check_remove_watches(
        &mut self,
        server_path: &[u8],
        wtype: i32,
        watcher: Option<&T::Watcher>,
        local: bool,
    ) -> Result<(), i32> {
        if !self.watches.has(server_path, wtype, watcher) {
            return Err(rc::ZNOWATCHER);
        }
        if local {
            let removed = self.watches.remove(server_path, wtype, watcher);
            self.watches_push_terminated(removed, TerminateReason::Removed);
        }
        Ok(())
    }

    /// Ingests and processes an unsolicited server-pushed watcher notification frame (`xid == -1`).
    ///
    /// Decodes the [`WatcherEvent`] record, validates chroot prefix compliance (handling root
    /// configuration nodes specially), collects matching registered watchers while discharging
    /// one-shot registrations, and enqueues an [`Output::Watch`] event.
    pub(crate) fn on_watcher_event(&mut self, zxid: i64, body: &[u8], now: Millis) -> Flow {
        self.emit_log(LogSite::ProcessingWatcherEvent, Vec::new);
        let Ok(evt) = WatcherEvent::from_bytes(body) else {
            let rc_value = self.teardown(Cause::ProtocolError, 0, now);
            return Flow::Done(rc_value);
        };
        let raw: Vec<u8> = evt.path.as_bytes().map(<[u8]>::to_vec).unwrap_or_default();
        let mut logs: Vec<LogRecord> = Vec::new();
        let shown: &[u8] = if evt.path.is_null() { b"NULL" } else { &raw };
        if let Some(r) = self.log.record(LogSite::CallingWatcher, || {
            vec![
                str_arg(shown),
                LogArg::I64(i64::from(completion::WATCH)),
                str_arg(event_name(evt.r#type)),
            ]
        }) {
            logs.push(r);
        }
        let path = if evt.path.is_null() || self.cfg.chroot.is_none() {
            evt.path.clone()
        } else {
            let mark = logs.len();
            match self.strip_path(&raw, &mut logs) {
                Some(p) => p,
                None if is_config_node(&raw) => {
                    logs.truncate(mark);
                    evt.path.clone()
                }
                None => {
                    for r in logs.into_iter().skip(mark) {
                        self.out.push(Output::Log(r));
                    }
                    return Flow::Continue;
                }
            }
        };
        let watchers = self.watches.collect_event(evt.r#type, &raw);
        if watchers.is_empty() && self.watches_native() {
            return Flow::Continue;
        }
        self.out.push(Output::Watch {
            event: WatchedEvent {
                event_type: evt.r#type,
                state: evt.state,
                path,
                zxid,
            },
            watchers,
            delivery_logs: logs,
        });
        Flow::Continue
    }

    /// Dispatches session state transition events to registered watchers according to configured
    /// fan-out policies.
    pub(crate) fn session_watchers(&mut self) -> Vec<T::Watcher> {
        let default = self.default_watcher.snapshot();
        self.watches
            .session(default, self.compat.session_fanout_to_path_watchers)
    }

    /// Generates and enqueues prioritized `SetWatches` or `SetWatches2` wire request frames during
    /// session re-establishment.
    ///
    /// Re-registers active watches using the latest acknowledged transaction ID (`last_zxid`),
    /// batching paths up to configured byte thresholds and inserting packets at the priority
    /// transmission boundary immediately following authentication resends.
    pub(crate) fn queue_set_watches(&mut self) {
        if !self.cfg.restore_watches {
            return;
        }
        let keys = self.watches.keys();
        if keys.is_empty() {
            return;
        }
        let packets =
            set_watches_packets(&keys, self.core.last_zxid, self.compat.set_watches_batch);
        for (op, body) in packets.iter().rev() {
            let frame = Self::request_frame(xid::SET_WATCHES, *op, body);
            self.requests
                .push_priority(OutFrame::plain(FrameKind::SetWatches, frame));
        }
        let server = self.current_server();
        self.emit_log(LogSite::SendSetWatches, || vec![LogArg::Endpoint(server)]);
    }

    /// Purges all registered watches across all category tables in accordance with configured
    /// session termination policies.
    ///
    /// Explicit close operations always purge watches. Session expiration and authentication
    /// failures purge watches when `clear_watches_on_expiry` is enabled. Network disconnects clear
    /// watches only when automatic re-registration is disabled.
    pub(crate) fn clear_watches(&mut self, reason: TerminateReason) {
        let clear = match reason {
            TerminateReason::Closed | TerminateReason::Removed => true,
            TerminateReason::SessionExpired | TerminateReason::AuthFailed => {
                self.compat.clear_watches_on_expiry
            }
            TerminateReason::ConnectionLost => !self.cfg.restore_watches,
        };
        if !clear {
            return;
        }
        let removed = self.watches.clear();
        self.watches_push_terminated(removed, reason);
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::large_stack_frames
)]
mod tests {
    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, DefaultWatcher, Rng};
    use crate::request::Route;
    use crate::requests::{Pending, Shape};

    struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    struct FixedRng;
    impl Rng for FixedRng {
        fn on_resolution(&mut self) {}
        fn random(&mut self) -> i64 {
            0
        }
        fn drand48(&mut self) -> f64 {
            0.0
        }
    }

    struct Dw(Option<u32>);
    impl DefaultWatcher<u32> for Dw {
        fn snapshot(&self) -> Option<u32> {
            self.0
        }
    }

    fn conn_with(compat: Compat, default: Option<u32>) -> Connection<Tk> {
        Connection::new(
            Config::legacy(30_000),
            compat,
            DriverMode::MT,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(Dw(default)),
            Millis(0),
        )
    }

    fn legacy() -> Connection<Tk> {
        conn_with(Compat::LEGACY_C, Some(0))
    }

    fn native() -> Connection<Tk> {
        conn_with(Compat::NATIVE, None)
    }

    fn reg(kind: WatchKind, path: &[u8], w: u32) -> WatchReg<u32> {
        WatchReg {
            kind,
            path: path.to_vec(),
            watcher: w,
        }
    }

    fn event_body(t: i32, path: &[u8]) -> Vec<u8> {
        WatcherEvent {
            r#type: t,
            state: 3,
            path: JString::Bytes(path.to_vec()),
        }
        .to_bytes()
    }

    /// Drains all available outputs from the connection and collects emitted [`Output::Watch`]
    /// events.
    fn watch_outputs(c: &mut Connection<Tk>) -> Vec<(WatchedEvent, Vec<u32>, Vec<LogRecord>)> {
        let mut got = Vec::new();
        while let Some(o) = c.poll_output() {
            if let Output::Watch {
                event,
                watchers,
                delivery_logs,
            } = o
            {
                got.push((event, watchers, delivery_logs));
            }
        }
        got
    }

    fn terminated(c: &mut Connection<Tk>) -> Vec<(Vec<u32>, TerminateReason)> {
        let mut got = Vec::new();
        while let Some(o) = c.poll_output() {
            if let Output::WatchTerminated { watchers, reason } = o {
                got.push((watchers, reason));
            }
        }
        got
    }

    /// Identifies all watch category tables that actively register the specified watcher on the
    /// given path.
    fn kinds_holding(c: &Connection<Tk>, path: &[u8], w: u32) -> Vec<WatchKind> {
        match &c.watches.store {
            Store::Legacy(l) => {
                let mut v = Vec::new();
                if l.data.contains(path, Some(&w)) {
                    v.push(WatchKind::Data);
                }
                if l.exist.contains(path, Some(&w)) {
                    v.push(WatchKind::Exist);
                }
                if l.child.contains(path, Some(&w)) {
                    v.push(WatchKind::Child);
                }
                v
            }
            Store::Ordered(o) => ALL_KINDS
                .iter()
                .copied()
                .filter(|k| o.contains(*k, path, Some(&w)))
                .collect(),
        }
    }

    #[test]
    fn checker_table_every_kind_and_rc() {
        let rcs = [rc::ZOK, rc::ZNONODE, rc::ZCONNECTIONLOSS, rc::ZNOAUTH];
        for native_store in [false, true] {
            for kind in ALL_KINDS {
                for rc_value in rcs {
                    let mut c = if native_store { native() } else { legacy() };
                    c.arm_watch(reg(kind, b"/a", 7), rc_value);
                    let got = kinds_holding(&c, b"/a", 7);
                    let want: Vec<WatchKind> = match (kind, rc_value) {
                        (WatchKind::Exist, rc::ZOK) | (WatchKind::Data, rc::ZOK) => {
                            vec![WatchKind::Data]
                        }
                        (WatchKind::Exist, rc::ZNONODE) => vec![WatchKind::Exist],
                        (WatchKind::Child, rc::ZOK) => vec![WatchKind::Child],
                        (WatchKind::Persistent | WatchKind::PersistentRecursive, rc::ZOK)
                            if native_store =>
                        {
                            vec![kind]
                        }
                        _ => vec![],
                    };
                    assert_eq!(
                        got, want,
                        "store native={native_store} {kind:?} rc={rc_value}"
                    );
                }
            }
        }
    }

    #[test]
    fn arm_never_duplicates() {
        for mut c in [legacy(), native()] {
            c.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
            c.arm_watch(reg(WatchKind::Exist, b"/a", 1), rc::ZOK);
            c.arm_watch(reg(WatchKind::Data, b"/a", 2), rc::ZOK);
            c.on_watcher_event(5, &event_body(CHANGED_EVENT, b"/a"), Millis(0));
            let w = watch_outputs(&mut c);
            assert_eq!(w.len(), 1);
            let mut got = w[0].1.clone();
            got.sort_unstable();
            assert_eq!(got, vec![1, 2]);
        }
    }

    #[test]
    fn legacy_collection_order() {
        let mut c = legacy();
        // Establish initial registrations in sequence: data 1, data 2, exist 3, child 4.
        c.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        c.arm_watch(reg(WatchKind::Data, b"/a", 2), rc::ZOK);
        c.arm_watch(reg(WatchKind::Exist, b"/a", 3), rc::ZNONODE);
        c.arm_watch(reg(WatchKind::Child, b"/a", 4), rc::ZOK);
        c.on_watcher_event(9, &event_body(DELETED_EVENT, b"/a"), Millis(0));
        let w = watch_outputs(&mut c);
        assert_eq!(w.len(), 1);
        // Event delivery dispatches child watches first, then existence watches, followed by data
        // watches in registration order.
        assert_eq!(w[0].1, vec![4, 3, 1, 2]);
        assert_eq!(w[0].0.zxid, 9);
        assert_eq!(w[0].0.event_type, DELETED_EVENT);
        assert_eq!(w[0].0.path, JString::Bytes(b"/a".to_vec()));
        assert!(!c.has_watcher(b"/a", WTYPE_DATA, Some(&1)));
        assert!(!c.has_watcher(b"/a", WTYPE_CHILD, Some(&4)));

        c.arm_watch(reg(WatchKind::Data, b"/b", 1), rc::ZOK);
        c.arm_watch(reg(WatchKind::Exist, b"/b", 2), rc::ZNONODE);
        c.arm_watch(reg(WatchKind::Child, b"/b", 3), rc::ZOK);
        c.on_watcher_event(10, &event_body(CREATED_EVENT, b"/b"), Millis(0));
        let w = watch_outputs(&mut c);
        assert_eq!(w[0].1, vec![2, 1]);
        // Node creation events trigger data/exist watches while leaving child watches intact.
        assert!(c.has_watcher(b"/b", WTYPE_CHILD, Some(&3)));
        c.on_watcher_event(11, &event_body(CHILD_EVENT, b"/b"), Millis(0));
        assert_eq!(watch_outputs(&mut c)[0].1, vec![3]);
    }

    #[test]
    fn legacy_empty_event_still_goes_out_with_logs() {
        let mut c = legacy();
        c.on_watcher_event(1, &event_body(CHANGED_EVENT, b"/none"), Millis(0));
        c.on_watcher_event(1, &event_body(99, b"/none"), Millis(0));
        let w = watch_outputs(&mut c);
        assert_eq!(w.len(), 2);
        assert!(w.iter().all(|(_, ws, _)| ws.is_empty()));
        let first = &w[0].2[0];
        assert_eq!(first.site, LogSite::CallingWatcher);
        assert_eq!(
            first.args,
            vec![
                str_arg(b"/none"),
                LogArg::I64(-1),
                str_arg(b"ZOO_CHANGED_EVENT")
            ]
        );
        assert_eq!(w[1].2[0].args[2], str_arg(b"INVALID_EVENT"));
    }

    #[test]
    fn native_empty_event_has_no_output() {
        let mut c = native();
        c.on_watcher_event(1, &event_body(CHANGED_EVENT, b"/none"), Millis(0));
        assert!(watch_outputs(&mut c).is_empty());
    }

    #[test]
    fn processing_line_comes_first() {
        let mut c = legacy();
        c.on_watcher_event(1, &event_body(CHANGED_EVENT, b"/a"), Millis(0));
        match c.poll_output() {
            Some(Output::Log(r)) => assert_eq!(r.site, LogSite::ProcessingWatcherEvent),
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn ordered_collection_order() {
        let mut c = native();
        c.arm_watch(reg(WatchKind::Data, b"/a/b", 1), rc::ZOK);
        c.arm_watch(reg(WatchKind::Data, b"/a/b", 2), rc::ZOK);
        c.arm_watch(reg(WatchKind::Exist, b"/a/b", 3), rc::ZNONODE);
        c.arm_watch(reg(WatchKind::Exist, b"/a/b", 4), rc::ZNONODE);
        c.arm_watch(reg(WatchKind::Child, b"/a/b", 5), rc::ZOK);
        c.arm_watch(reg(WatchKind::Persistent, b"/a/b", 6), rc::ZOK);
        c.arm_watch(reg(WatchKind::PersistentRecursive, b"/", 7), rc::ZOK);
        c.arm_watch(reg(WatchKind::PersistentRecursive, b"/a", 8), rc::ZOK);
        c.arm_watch(reg(WatchKind::PersistentRecursive, b"/a/b", 9), rc::ZOK);
        c.arm_watch(reg(WatchKind::PersistentRecursive, b"/x", 10), rc::ZOK);

        c.on_watcher_event(1, &event_body(CHANGED_EVENT, b"/a/b"), Millis(0));
        let w = watch_outputs(&mut c);
        // Verify dispatch order: one-shot existence then data (oldest first), persistent, and
        // recursive (nearest ancestor first).
        assert_eq!(w[0].1, vec![3, 4, 1, 2, 6, 9, 8, 7]);

        // Child change events dispatch child and persistent watches without triggering recursive
        // watches.
        c.on_watcher_event(2, &event_body(CHILD_EVENT, b"/a/b"), Millis(0));
        assert_eq!(watch_outputs(&mut c)[0].1, vec![5, 6]);

        // Persistent watches survive event delivery whereas one-shot registrations are purged.
        c.arm_watch(reg(WatchKind::Data, b"/a/b", 1), rc::ZOK);
        c.arm_watch(reg(WatchKind::Exist, b"/a/b", 3), rc::ZNONODE);
        c.arm_watch(reg(WatchKind::Child, b"/a/b", 5), rc::ZOK);
        c.on_watcher_event(3, &event_body(DELETED_EVENT, b"/a/b"), Millis(0));
        assert_eq!(watch_outputs(&mut c)[0].1, vec![5, 3, 1, 6, 9, 8, 7]);
        assert!(c.has_watcher(b"/a/b", WTYPE_PERSISTENT, Some(&6)));
        assert!(!c.has_watcher(b"/a/b", WTYPE_DATA, None));
    }

    #[test]
    fn lineage_order() {
        assert_eq!(
            path_lineage(b"/a/b/c"),
            vec![&b"/a/b/c"[..], b"/a/b", b"/a", b"/"]
        );
        assert_eq!(path_lineage(b"/"), vec![&b"/"[..]]);
        assert_eq!(path_lineage(b"rel"), vec![&b"rel"[..]]);
        assert_eq!(path_lineage(b""), vec![&b""[..]]);
    }

    #[test]
    fn has_watcher_each_wtype() {
        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Data, b"/d", 1), rc::ZOK);
        l.arm_watch(reg(WatchKind::Exist, b"/e", 2), rc::ZNONODE);
        l.arm_watch(reg(WatchKind::Child, b"/c", 3), rc::ZOK);
        assert!(l.has_watcher(b"/c", WTYPE_CHILD, Some(&3)));
        assert!(!l.has_watcher(b"/d", WTYPE_CHILD, Some(&1)));
        assert!(l.has_watcher(b"/d", WTYPE_DATA, Some(&1)));
        assert!(l.has_watcher(b"/e", WTYPE_DATA, Some(&2)));
        assert!(!l.has_watcher(b"/c", WTYPE_DATA, Some(&3)));
        for (p, w) in [(&b"/d"[..], 1), (b"/e", 2), (b"/c", 3)] {
            assert!(l.has_watcher(p, WTYPE_ANY, Some(&w)));
        }
        // Under legacy C compatibility, passing `None` matches any path for a valid watch type.
        assert!(l.has_watcher(b"/nothing", WTYPE_CHILD, None));
        for bad in [0, 4, 5, 6, -1] {
            assert!(!l.has_watcher(b"/d", bad, None));
            assert!(!l.has_watcher(b"/d", bad, Some(&1)));
        }

        let mut n = native();
        n.arm_watch(reg(WatchKind::Data, b"/d", 1), rc::ZOK);
        n.arm_watch(reg(WatchKind::Exist, b"/e", 2), rc::ZNONODE);
        n.arm_watch(reg(WatchKind::Child, b"/c", 3), rc::ZOK);
        n.arm_watch(reg(WatchKind::Persistent, b"/p", 4), rc::ZOK);
        n.arm_watch(reg(WatchKind::PersistentRecursive, b"/r", 5), rc::ZOK);
        assert!(n.has_watcher(b"/c", WTYPE_CHILD, Some(&3)));
        assert!(n.has_watcher(b"/d", WTYPE_DATA, Some(&1)));
        assert!(n.has_watcher(b"/e", WTYPE_DATA, None));
        assert!(n.has_watcher(b"/p", WTYPE_PERSISTENT, Some(&4)));
        assert!(!n.has_watcher(b"/p", WTYPE_DATA, Some(&4)));
        assert!(n.has_watcher(b"/r", WTYPE_RECURSIVE, Some(&5)));
        assert!(!n.has_watcher(b"/r", WTYPE_PERSISTENT, None));
        for (p, w) in [
            (&b"/d"[..], 1),
            (b"/e", 2),
            (b"/c", 3),
            (b"/p", 4),
            (b"/r", 5),
        ] {
            assert!(n.has_watcher(p, WTYPE_ANY, Some(&w)));
        }
        // Under native ordered semantics, passing `None` requires an active registration on the
        // path.
        assert!(!n.has_watcher(b"/nothing", WTYPE_ANY, None));
        for bad in [0, 6, -1] {
            assert!(!n.has_watcher(b"/d", bad, None));
        }
    }

    #[test]
    fn remove_watches_check_and_local() {
        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        assert_eq!(
            l.check_remove_watches(b"/a", WTYPE_CHILD, Some(&1), true),
            Err(rc::ZNOWATCHER)
        );
        assert_eq!(
            l.check_remove_watches(b"/a", 9, None, true),
            Err(rc::ZNOWATCHER)
        );
        // When local removal is disabled, the operation verifies watch presence without modifying
        // registrations.
        assert_eq!(
            l.check_remove_watches(b"/a", WTYPE_DATA, Some(&1), false),
            Ok(())
        );
        assert!(l.has_watcher(b"/a", WTYPE_DATA, Some(&1)));
        assert_eq!(
            l.check_remove_watches(b"/a", WTYPE_ANY, Some(&1), true),
            Ok(())
        );
        assert!(!l.has_watcher(b"/a", WTYPE_ANY, Some(&1)));
        // Legacy mode suppresses `WatchTerminated` event emission during watch removal.
        assert!(terminated(&mut l).is_empty());

        let mut n = native();
        n.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        n.arm_watch(reg(WatchKind::Exist, b"/a", 2), rc::ZNONODE);
        n.arm_watch(reg(WatchKind::Child, b"/a", 3), rc::ZOK);
        assert_eq!(
            n.check_remove_watches(b"/b", WTYPE_DATA, None, true),
            Err(rc::ZNOWATCHER)
        );
        assert_eq!(
            n.check_remove_watches(b"/a", WTYPE_DATA, None, true),
            Ok(())
        );
        assert_eq!(
            terminated(&mut n),
            vec![(vec![1, 2], TerminateReason::Removed)]
        );
        assert!(n.has_watcher(b"/a", WTYPE_CHILD, Some(&3)));
    }

    #[test]
    fn unwatch_reply_removes() {
        let mut n = native();
        n.arm_watch(reg(WatchKind::Child, b"/a", 3), rc::ZOK);
        n.arm_watch(reg(WatchKind::Persistent, b"/a", 4), rc::ZOK);
        n.apply_unwatch(Unwatch {
            path: b"/a".to_vec(),
            wtype: WTYPE_ANY,
            watcher: None,
        });
        assert_eq!(
            terminated(&mut n),
            vec![(vec![3, 4], TerminateReason::Removed)]
        );
        assert!(!n.has_watcher(b"/a", WTYPE_ANY, None));

        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Child, b"/a", 3), rc::ZOK);
        l.arm_watch(reg(WatchKind::Child, b"/a", 4), rc::ZOK);
        l.apply_unwatch(Unwatch {
            path: b"/a".to_vec(),
            wtype: WTYPE_CHILD,
            watcher: Some(3),
        });
        assert!(!l.has_watcher(b"/a", WTYPE_CHILD, Some(&3)));
        assert!(l.has_watcher(b"/a", WTYPE_CHILD, Some(&4)));
    }

    #[test]
    fn session_watchers_with_and_without_fanout() {
        // Legacy session fan-out: delivers path watchers in reverse collection order, followed by
        // the default watcher.
        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        l.arm_watch(reg(WatchKind::Child, b"/b", 2), rc::ZOK);
        l.arm_watch(reg(WatchKind::Child, b"/c", 0), rc::ZOK);
        let got = l.session_watchers();
        assert_eq!(got.last(), Some(&0));
        assert_eq!(got.len(), 3);
        let mut path_part = got[..2].to_vec();
        path_part.sort_unstable();
        assert_eq!(path_part, vec![1, 2]);
        // Session event fan-out preserves all existing watch table registrations.
        assert!(l.has_watcher(b"/a", WTYPE_DATA, Some(&1)));

        // Verify legacy session fan-out delivery when no default watcher is configured.
        let mut l = conn_with(Compat::LEGACY_C, None);
        l.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        assert_eq!(l.session_watchers(), vec![1]);

        // With session fan-out disabled, session state transitions notify only the default watcher.
        let mut n = conn_with(Compat::NATIVE, Some(42));
        n.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        assert_eq!(n.session_watchers(), vec![42]);
        let mut n = native();
        n.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        assert!(n.session_watchers().is_empty());

        // Under native ordered tables with fan-out enabled, path watchers dispatch in table order
        // before the default watcher.
        let mut compat = Compat::NATIVE;
        compat.session_fanout_to_path_watchers = true;
        let mut o = conn_with(compat, Some(9));
        o.arm_watch(reg(WatchKind::Child, b"/a", 3), rc::ZOK);
        o.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        o.arm_watch(reg(WatchKind::Data, b"/b", 9), rc::ZOK);
        assert_eq!(o.session_watchers(), vec![1, 3, 9]);
    }

    fn decode_packet(op: i32, body: &[u8]) -> (i64, Vec<Vec<Vec<u8>>>) {
        let names = |v: &JVec<JString>| -> Vec<Vec<u8>> {
            v.iter()
                .map(|s| s.as_bytes().unwrap_or_default().to_vec())
                .collect()
        };
        if op == opcode::SET_WATCHES {
            let r = SetWatches::from_bytes(body).unwrap();
            (
                r.relative_zxid,
                vec![
                    names(&r.data_watches),
                    names(&r.exist_watches),
                    names(&r.child_watches),
                ],
            )
        } else {
            assert_eq!(op, opcode::SET_WATCHES2);
            let r = SetWatches2::from_bytes(body).unwrap();
            (
                r.relative_zxid,
                vec![
                    names(&r.data_watches),
                    names(&r.exist_watches),
                    names(&r.child_watches),
                    names(&r.persistent_watches),
                    names(&r.persistent_recursive_watches),
                ],
            )
        }
    }

    #[test]
    fn set_watches_batching_at_the_limit() {
        let k = |s: &str| s.as_bytes().to_vec();
        let keys = WatchKeys {
            data: vec![k("/aaaa"), k("/bbbb")],
            exist: vec![k("/cccc")],
            child: vec![k("/dddd"), k("/eeee")],
            persistent: Vec::new(),
            recursive: Vec::new(),
        };
        // With 5-byte keys and a 10-byte limit, packets batch exactly two paths each.
        let packets = set_watches_packets(&keys, 77, Some(10));
        assert_eq!(packets.len(), 3);
        let decoded: Vec<_> = packets
            .iter()
            .map(|(op, b)| decode_packet(*op, b))
            .collect();
        assert!(packets.iter().all(|(op, _)| *op == opcode::SET_WATCHES));
        assert!(decoded.iter().all(|(z, _)| *z == 77));
        assert_eq!(
            decoded[0].1,
            vec![vec![k("/aaaa"), k("/bbbb")], vec![], vec![]]
        );
        assert_eq!(
            decoded[1].1,
            vec![vec![], vec![k("/cccc")], vec![k("/dddd")]]
        );
        assert_eq!(decoded[2].1, vec![vec![], vec![], vec![k("/eeee")]]);

        // Restricting the batch limit below the combined size of two keys isolates each key into
        // its own packet.
        assert_eq!(set_watches_packets(&keys, 0, Some(9)).len(), 5);
        // Omitting the byte threshold or providing sufficient headroom combines all keys into a
        // single packet.
        assert_eq!(set_watches_packets(&keys, 0, None).len(), 1);
        assert_eq!(set_watches_packets(&keys, 0, Some(25)).len(), 1);
        assert_eq!(set_watches_packets(&keys, 0, Some(24)).len(), 2);
        // An individual key exceeding the batch size limit is transmitted in its own dedicated
        // packet without truncation.
        let big = WatchKeys {
            data: vec![k("/x"), vec![b'/'; 40], k("/y")],
            ..WatchKeys::default()
        };
        let p = set_watches_packets(&big, 0, Some(10));
        assert_eq!(p.len(), 3);
        // An empty key set produces zero request packets.
        assert!(set_watches_packets(&WatchKeys::default(), 0, Some(10)).is_empty());
    }

    /// Verifies that `SetWatches` request payloads partition at 128 KiB thresholds, and persistent
    /// watches promote to `SetWatches2`.
    #[test]
    fn set_watches_128k_split_and_setwatches2() {
        let keys = WatchKeys {
            data: (0..3000)
                .map(|i| format!("/node/{i:080}").into_bytes())
                .collect(),
            persistent: vec![b"/p".to_vec()],
            ..WatchKeys::default()
        };
        let packets = set_watches_packets(&keys, 5, Some(crate::compat::SET_WATCHES_BATCH));
        assert!(packets.len() > 1);
        let mut seen = 0usize;
        for (op, body) in &packets {
            let (_, lists) = decode_packet(*op, body);
            let bytes: usize = lists.iter().flatten().map(Vec::len).sum();
            assert!(bytes <= 128 * 1024);
            seen += lists.iter().map(Vec::len).sum::<usize>();
        }
        assert_eq!(seen, 3001);
        // Only the specific batch packet containing persistent watch keys upgrades to `SetWatches2`
        // opcode 105.
        let ops: Vec<i32> = packets.iter().map(|(op, _)| *op).collect();
        assert_eq!(ops.last(), Some(&opcode::SET_WATCHES2));
        assert!(
            ops[..ops.len() - 1]
                .iter()
                .all(|op| *op == opcode::SET_WATCHES)
        );
    }

    #[test]
    fn ordered_tables_keep_insertion_order() {
        let mut n = native();
        for (i, p) in [&b"/b"[..], b"/a", b"/c"].iter().enumerate() {
            n.arm_watch(reg(WatchKind::Data, p, u32::try_from(i).unwrap()), rc::ZOK);
        }
        let keys = n.watches.keys();
        assert_eq!(
            keys.data,
            vec![b"/b".to_vec(), b"/a".to_vec(), b"/c".to_vec()]
        );
        // Universal watcher iteration preserves the exact chronological registration order.
        assert_eq!(n.watches.all_watchers(), vec![0, 1, 2]);
        // Re-registering a previously removed path repositions it at the tail of the insertion
        // sequence.
        n.apply_unwatch(Unwatch {
            path: b"/b".to_vec(),
            wtype: WTYPE_ANY,
            watcher: None,
        });
        n.arm_watch(reg(WatchKind::Data, b"/b", 7), rc::ZOK);
        let keys = n.watches.keys();
        assert_eq!(
            keys.data,
            vec![b"/a".to_vec(), b"/c".to_vec(), b"/b".to_vec()]
        );
    }

    #[test]
    fn path_map_renumbers_at_the_counter_end() {
        let mut m: PathMap<u32> = PathMap::new();
        m.entry_or_default(b"/x").push(1);
        m.next_seq = u64::MAX;
        m.entry_or_default(b"/y").push(2);
        m.entry_or_default(b"/z").push(3);
        let keys: Vec<Vec<u8>> = m.keys().cloned().collect();
        assert_eq!(keys, vec![b"/x".to_vec(), b"/y".to_vec(), b"/z".to_vec()]);
        assert_eq!(m.get(b"/y"), Some(&vec![2]));
        assert_eq!(m.remove(b"/x"), Some(vec![1]));
        assert!(m.get(b"/x").is_none());
    }

    #[test]
    fn set_watches_keys_keep_the_chroot_and_c_order() {
        let mut l = legacy();
        for (i, p) in [&b"/app/a"[..], b"/app/b", b"/app/c"].iter().enumerate() {
            l.arm_watch(reg(WatchKind::Data, p, u32::try_from(i).unwrap()), rc::ZOK);
        }
        let keys = l.watches.keys();
        let Store::Legacy(t) = &l.watches.store else {
            panic!("legacy store expected");
        };
        let c_order: Vec<Vec<u8>> = t.data.keys().map(<[u8]>::to_vec).collect();
        assert_eq!(keys.data, c_order);
        assert!(keys.data.iter().all(|k| k.starts_with(b"/app/")));
    }

    #[test]
    fn queue_set_watches_noops() {
        // When no active watches exist, `queue_set_watches` is a no-op and enqueues no
        // transmission frames.
        let mut c = legacy();
        c.queue_set_watches();
        assert!(c.requests.tx.is_empty());
        // Disabling `restore_watches` suppresses `SetWatches` frame generation during
        // re-connection.
        let mut cfg = Config::legacy(30_000);
        cfg.restore_watches = false;
        let mut c: Connection<Tk> = Connection::new(
            cfg,
            Compat::NATIVE,
            DriverMode::NATIVE,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(Dw(None)),
            Millis(0),
        );
        c.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        c.queue_set_watches();
        assert!(c.requests.tx.is_empty());
    }

    #[test]
    fn clear_watches_by_reason() {
        // Legacy compatibility retains watch tables across session expiration and purges them upon
        // explicit close.
        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        l.clear_watches(TerminateReason::SessionExpired);
        l.clear_watches(TerminateReason::ConnectionLost);
        assert!(l.has_watcher(b"/a", WTYPE_DATA, Some(&1)));
        l.clear_watches(TerminateReason::Closed);
        assert!(!l.has_watcher(b"/a", WTYPE_DATA, Some(&1)));
        assert!(terminated(&mut l).is_empty());

        // Native mode clears watch tables upon session expiration and emits `WatchTerminated`
        // events.
        let mut n = native();
        n.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        n.arm_watch(reg(WatchKind::Child, b"/a", 1), rc::ZOK);
        n.arm_watch(reg(WatchKind::Persistent, b"/b", 2), rc::ZOK);
        // With `restore_watches` enabled, transient connection loss preserves registered watches
        // for re-registration.
        n.clear_watches(TerminateReason::ConnectionLost);
        assert!(terminated(&mut n).is_empty());
        n.clear_watches(TerminateReason::SessionExpired);
        assert_eq!(
            terminated(&mut n),
            vec![(vec![1, 2], TerminateReason::SessionExpired)]
        );
        assert!(!n.has_watcher(b"/a", WTYPE_ANY, None));
        // When watch tables are already empty, subsequent clear operations emit no termination
        // events.
        n.clear_watches(TerminateReason::Closed);
        assert!(terminated(&mut n).is_empty());
    }

    fn pending_with(reg: WatchReg<u32>) -> Pending<Tk> {
        Pending {
            xid: 1,
            shape: Shape::Data,
            route: Route::Queued,
            token: 0,
            watch: Some(reg),
            unwatch: None,
            written: false,
        }
    }

    #[test]
    fn detach_gives_hint_for_last_user() {
        let mut n = native();
        n.arm_watch(reg(WatchKind::Data, b"/a", 1), rc::ZOK);
        n.arm_watch(reg(WatchKind::Exist, b"/a", 2), rc::ZNONODE);
        n.arm_watch(reg(WatchKind::PersistentRecursive, b"/r", 3), rc::ZOK);
        // Removing one watcher does not trigger a removal hint if concurrent watchers remain armed
        // on that server class.
        assert_eq!(n.detach_watcher(&1), None);
        assert!(!n.has_watcher(b"/a", WTYPE_DATA, Some(&1)));
        assert_eq!(
            n.detach_watcher(&2),
            Some(RemoveHint {
                path: JString::Bytes(b"/a".to_vec()),
                wtype: WTYPE_DATA
            })
        );
        assert_eq!(
            n.detach_watcher(&3),
            Some(RemoveHint {
                path: JString::Bytes(b"/r".to_vec()),
                wtype: WTYPE_RECURSIVE
            })
        );
        // Attempting to detach an unregistered watcher yields no removal hint.
        assert_eq!(n.detach_watcher(&99), None);
    }

    #[test]
    fn detach_counts_in_flight_registrations() {
        let mut n = native();
        n.arm_watch(reg(WatchKind::Child, b"/a", 1), rc::ZOK);
        n.requests
            .push_pending(pending_with(reg(WatchKind::Child, b"/a", 2)));
        assert_eq!(n.detach_watcher(&1), None);
        // In-flight registrations belonging to a different server watch class do not suppress the
        // removal hint.
        n.arm_watch(reg(WatchKind::Data, b"/b", 4), rc::ZOK);
        n.requests
            .push_pending(pending_with(reg(WatchKind::Child, b"/b", 5)));
        assert_eq!(
            n.detach_watcher(&4),
            Some(RemoveHint {
                path: JString::Bytes(b"/b".to_vec()),
                wtype: WTYPE_DATA
            })
        );
    }

    #[test]
    fn detach_legacy_store() {
        let mut l = legacy();
        l.arm_watch(reg(WatchKind::Child, b"/a", 1), rc::ZOK);
        l.arm_watch(reg(WatchKind::Child, b"/a", 2), rc::ZOK);
        assert_eq!(l.detach_watcher(&1), None);
        assert_eq!(
            l.detach_watcher(&2),
            Some(RemoveHint {
                path: JString::Bytes(b"/a".to_vec()),
                wtype: WTYPE_CHILD
            })
        );
    }

    #[test]
    fn event_name_table() {
        assert_eq!(event_name(0), b"ZOO_ERROR_EVENT");
        assert_eq!(event_name(SESSION_EVENT), b"ZOO_SESSION_EVENT");
        assert_eq!(event_name(-2), b"ZOO_NOTWATCHING_EVENT");
        assert_eq!(event_name(DELETED_EVENT), b"ZOO_DELETED_EVENT");
    }
}
