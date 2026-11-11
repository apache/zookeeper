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

//! In-memory znode hierarchy storage and differential tree reconciliation.
//!
//! Because server side persistent recursive watches emit only `NodeCreated`, `NodeDataChanged`,
//! and `NodeDeleted` events (never `NodeChildrenChanged`), the [`Tree`] maintains directory
//! parent-child relationships synthesized from individual creation and deletion notifications
//! and full synchronization traversals.
//!
//! # Structural and Consistency Invariants
//!
//! 1. **Strict Monotonic Ordering by ZXID**: A queried node updates or replaces an existing
//!    cached entry only if it represents a newer transaction, either an altered `czxid`
//!    (indicating znode recreation at the same path) or a strictly greater `mzxid`.
//! 2. **Continuous Hierarchical Lineage**: Non-root znodes are inserted into the cache only if
//!    their parent znode is already present in memory, guaranteeing an unbroken ancestor chain
//!    up to the configured subtree root.
//! 3. **Bottom-Up Subtree Invalidation**: Subtree removals are processed in deepest-first order,
//!    emitting child removal events before their respective parent removal events.
//!
//! During resynchronization, the differential comparison algorithm reconciles the previous tree
//! against the newly traversed tree according to these invariants, emitting granular update,
//! addition, and removal events for observed mutations.

use std::cmp::Reverse;
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::ops::Bound;

use apache_zookeeper::{ZPath, ZPathBuf};

use super::{CachedNode, TreeEvent};

/// Internal representation of a cached znode along with its set of immediate child names.
#[derive(Debug)]
struct Entry {
    /// Absolute ZooKeeper path matching the map key.
    path: ZPathBuf,
    node: CachedNode,
    children: BTreeSet<String>,
}

/// In-memory znode hierarchy indexed by lexicographical path keys.
///
/// Subtree ranges are queried efficiently via B-tree key range scans from `<path>/` to `<path>0`.
#[derive(Debug)]
pub(super) struct Tree {
    root: ZPathBuf,
    nodes: BTreeMap<String, Entry>,
}

/// Computes the relative hierarchy depth of `path` with respect to `root`, returning `None` if
/// outside the subtree.
pub(super) fn depth(root: ZPath<'_>, path: ZPath<'_>) -> Option<usize> {
    if !path.starts_with(root) {
        return None;
    }
    path.components()
        .count()
        .checked_sub(root.components().count())
}

/// Computes the path depth level by counting path separator delimiters.
fn level(path: &str) -> usize {
    if path == "/" {
        0
    } else {
        path.matches('/').count()
    }
}

/// Comparator ordering entries top-down (parents before children), broken tie-wise by
/// lexicographical path order.
fn parents_first(a: &&Entry, b: &&Entry) -> std::cmp::Ordering {
    level(a.path.as_str())
        .cmp(&level(b.path.as_str()))
        .then_with(|| a.path.cmp(&b.path))
}

/// Comparator ordering entries bottom-up (children before parents), broken tie-wise by
/// lexicographical path order.
fn deepest_first(a: &&Entry, b: &&Entry) -> std::cmp::Ordering {
    Reverse(level(a.path.as_str()))
        .cmp(&Reverse(level(b.path.as_str())))
        .then_with(|| a.path.cmp(&b.path))
}

impl Tree {
    /// Constructs an empty tree container rooted at `root`.
    pub(super) fn new(root: ZPathBuf) -> Self {
        Self {
            root,
            nodes: BTreeMap::new(),
        }
    }

    /// Returns the root path of the cached subtree.
    pub(super) fn root(&self) -> ZPath<'_> {
        self.root.as_path()
    }

    /// Retrieves a cloned snapshot of the node at `path`, or `None` if not present.
    pub(super) fn get(&self, path: ZPath<'_>) -> Option<CachedNode> {
        self.node(path).cloned()
    }

    /// Borrows a reference to the cached node at `path`, or `None` if not present.
    pub(super) fn node(&self, path: ZPath<'_>) -> Option<&CachedNode> {
        self.nodes.get(path.as_str()).map(|e| &e.node)
    }

    /// Returns a sorted vector of child names under `path`, or `None` if `path` is not cached.
    pub(super) fn children(&self, path: ZPath<'_>) -> Option<Vec<String>> {
        self.nodes
            .get(path.as_str())
            .map(|e| e.children.iter().cloned().collect())
    }

    /// Inserts or updates `node` at `path`, returning `false` if `path` lacks a cached parent or
    /// falls outside the subtree.
    pub(super) fn insert(&mut self, path: ZPath<'_>, node: CachedNode) -> bool {
        if depth(self.root.as_path(), path).is_none() {
            return false;
        }
        if let Some(e) = self.nodes.get_mut(path.as_str()) {
            e.node = node;
            return true;
        }
        if path != self.root.as_path() {
            let (Some(parent), Some(name)) = (path.parent(), path.name()) else {
                return false;
            };
            let Some(p) = self.nodes.get_mut(parent.as_str()) else {
                return false;
            };
            p.children.insert(name.to_owned());
        }
        self.nodes.insert(
            path.as_str().to_owned(),
            Entry {
                path: path.to_buf(),
                node,
                children: BTreeSet::new(),
            },
        );
        true
    }

    /// Identifies all map keys corresponding to `path` and its descendant subtree.
    fn subtree_keys(&self, path: ZPath<'_>) -> Vec<String> {
        if path.is_root() {
            return self.nodes.keys().cloned().collect();
        }
        let p = path.as_str();
        let lower = format!("{p}/");
        let upper = format!("{p}0");
        let mut keys: Vec<String> = self
            .nodes
            .range::<str, _>((
                Bound::Included(lower.as_str()),
                Bound::Excluded(upper.as_str()),
            ))
            .map(|(k, _)| k.clone())
            .collect();
        if self.nodes.contains_key(p) {
            keys.push(p.to_owned());
        }
        keys
    }

    /// Removes `path` and all its descendants, enqueuing [`TreeEvent::NodeRemoved`] events in
    /// bottom-up order.
    pub(super) fn remove_subtree(&mut self, path: ZPath<'_>, events: &mut VecDeque<TreeEvent>) {
        let keys = self.subtree_keys(path);
        if keys.is_empty() {
            return;
        }
        let mut removed: Vec<Entry> = keys.iter().filter_map(|k| self.nodes.remove(k)).collect();
        removed.sort_by(|a, b| deepest_first(&a, &b));
        for e in removed {
            events.push_back(TreeEvent::NodeRemoved {
                path: e.path,
                node: e.node,
            });
        }
        if let (Some(parent), Some(name)) = (path.parent(), path.name()) {
            if let Some(p) = self.nodes.get_mut(parent.as_str()) {
                p.children.remove(name);
            }
        }
    }

    /// Reconciles an updated znode query into the tree, emitting diff events if newer by ZXID or
    /// unlinking if absent.
    pub(super) fn apply(
        &mut self,
        path: ZPath<'_>,
        read: Option<CachedNode>,
        events: &mut VecDeque<TreeEvent>,
    ) {
        let Some(new) = read else {
            self.remove_subtree(path, events);
            return;
        };
        match self.node(path).map(|n| n.stat) {
            Some(old) if old.czxid != new.stat.czxid => {
                // Different czxid indicates recreation; purge old subtree before adding
                // replacement node.
                self.remove_subtree(path, events);
                self.add(path, new, events);
            }
            Some(old) if new.stat.mzxid > old.mzxid => {
                if self.insert(path, new.clone()) {
                    events.push_back(TreeEvent::NodeUpdated {
                        path: path.to_buf(),
                        node: new,
                    });
                }
            }
            Some(_) => {}
            None => self.add(path, new, events),
        }
    }

    /// Inserts a new node into the hierarchy and enqueues a corresponding [`TreeEvent::NodeAdded`]
    /// event.
    fn add(&mut self, path: ZPath<'_>, node: CachedNode, events: &mut VecDeque<TreeEvent>) {
        if self.insert(path, node.clone()) {
            events.push_back(TreeEvent::NodeAdded {
                path: path.to_buf(),
                node,
            });
        }
    }

    /// Returns all cached entries sorted top-down in breadth-first order.
    fn breadth_first(&self) -> Vec<&Entry> {
        let mut all: Vec<&Entry> = self.nodes.values().collect();
        all.sort_by(parents_first);
        all
    }

    /// Populates the event queue with [`TreeEvent::NodeAdded`] events for all cached nodes in
    /// top-down order.
    pub(super) fn added_events(&self, events: &mut VecDeque<TreeEvent>) {
        for e in self.breadth_first() {
            events.push_back(TreeEvent::NodeAdded {
                path: e.path.clone(),
                node: e.node.clone(),
            });
        }
    }

    /// Replaces the current tree contents with a newly traversed `new` tree, enqueuing
    /// differential lifecycle events.
    ///
    /// Subtree removals are emitted first in bottom-up order, followed by additions and updates in
    /// top-down order.
    pub(super) fn resync(&mut self, new: Tree, events: &mut VecDeque<TreeEvent>) {
        let mut gone: Vec<&Entry> = self
            .nodes
            .values()
            .filter(|e| {
                new.nodes
                    .get(e.path.as_str())
                    .is_none_or(|n| n.node.stat.czxid != e.node.stat.czxid)
            })
            .collect();
        gone.sort_by(deepest_first);
        for e in gone {
            events.push_back(TreeEvent::NodeRemoved {
                path: e.path.clone(),
                node: e.node.clone(),
            });
        }
        for e in new.breadth_first() {
            match self.node(e.path.as_path()).map(|o| o.stat) {
                Some(o) if o.czxid == e.node.stat.czxid => {
                    if e.node.stat.mzxid > o.mzxid {
                        events.push_back(TreeEvent::NodeUpdated {
                            path: e.path.clone(),
                            node: e.node.clone(),
                        });
                    }
                }
                _ => events.push_back(TreeEvent::NodeAdded {
                    path: e.path.clone(),
                    node: e.node.clone(),
                }),
            }
        }
        *self = new;
    }
}

#[cfg(test)]
mod tests {
    use apache_zookeeper::{Stat, Zxid};

    use super::*;

    fn p(s: &str) -> ZPath<'_> {
        ZPath::new(s).unwrap()
    }

    fn node(czxid: i64, mzxid: i64) -> CachedNode {
        CachedNode {
            stat: Stat {
                czxid: Zxid(czxid),
                mzxid: Zxid(mzxid),
                ..Stat::default()
            },
            data: None,
        }
    }

    fn tree(root: &str, nodes: &[(&str, i64, i64)]) -> Tree {
        let mut t = Tree::new(p(root).to_buf());
        for &(path, c, m) in nodes {
            assert!(t.insert(p(path), node(c, m)), "{path}");
        }
        t
    }

    fn names(events: &VecDeque<TreeEvent>) -> Vec<String> {
        events
            .iter()
            .map(|e| match e {
                TreeEvent::NodeAdded { path, .. } => format!("+{path}"),
                TreeEvent::NodeUpdated { path, .. } => format!("~{path}"),
                TreeEvent::NodeRemoved { path, .. } => format!("-{path}"),
                other => format!("{other:?}"),
            })
            .collect()
    }

    #[test]
    fn depth_counts_components_below_the_root() {
        assert_eq!(depth(p("/"), p("/")), Some(0));
        assert_eq!(depth(p("/"), p("/a/b")), Some(2));
        assert_eq!(depth(p("/r"), p("/r")), Some(0));
        assert_eq!(depth(p("/r"), p("/r/a/b")), Some(2));
        assert_eq!(depth(p("/r"), p("/r2/a")), None);
        assert_eq!(depth(p("/r/a"), p("/r")), None);
    }

    #[test]
    fn insert_needs_a_cached_parent() {
        let mut t = tree("/r", &[("/r", 1, 1)]);
        assert!(!t.insert(p("/r/a/b"), node(3, 3)));
        assert!(!t.insert(p("/other"), node(3, 3)));
        assert!(t.insert(p("/r/a"), node(2, 2)));
        assert!(t.insert(p("/r/a/b"), node(3, 3)));
        assert_eq!(t.children(p("/r")).unwrap(), vec!["a"]);
        assert_eq!(t.children(p("/r/a")).unwrap(), vec!["b"]);
        // Replacement preserves existing child relationships.
        assert!(t.insert(p("/r/a"), node(2, 9)));
        assert_eq!(t.children(p("/r/a")).unwrap(), vec!["b"]);
        assert_eq!(t.nodes.len(), 3);
    }

    #[test]
    fn remove_subtree_goes_deepest_first_and_spares_siblings() {
        let mut t = tree(
            "/r",
            &[
                ("/r", 1, 1),
                ("/r/a", 2, 2),
                ("/r/a/x", 3, 3),
                ("/r/a/y", 4, 4),
                ("/r/a/x/1", 5, 5),
                ("/r/a0", 6, 6),
                ("/r/a-b", 7, 7),
            ],
        );
        let mut ev = VecDeque::new();
        t.remove_subtree(p("/r/a"), &mut ev);
        assert_eq!(names(&ev), ["-/r/a/x/1", "-/r/a/x", "-/r/a/y", "-/r/a"]);
        assert_eq!(t.children(p("/r")).unwrap(), vec!["a-b", "a0"]);
        assert!(t.get(p("/r/a0")).is_some());
        assert!(t.get(p("/r/a-b")).is_some());

        let mut ev = VecDeque::new();
        t.remove_subtree(p("/r/missing"), &mut ev);
        assert!(ev.is_empty());
    }

    #[test]
    fn remove_of_the_slash_root_clears_all() {
        let mut t = tree("/", &[("/", 0, 0), ("/a", 1, 1), ("/a/b", 2, 2)]);
        let mut ev = VecDeque::new();
        t.remove_subtree(p("/"), &mut ev);
        assert_eq!(names(&ev), ["-/a/b", "-/a", "-/"]);
        assert!(t.nodes.is_empty());
    }

    #[test]
    fn apply_follows_the_czxid_and_mzxid_rule() {
        let mut t = tree("/r", &[("/r", 1, 1), ("/r/a", 2, 5), ("/r/a/k", 6, 6)]);
        let mut ev = VecDeque::new();

        // Stale or identical reads do not generate update events.
        t.apply(p("/r/a"), Some(node(2, 5)), &mut ev);
        t.apply(p("/r/a"), Some(node(2, 4)), &mut ev);
        assert!(ev.is_empty());

        // Strictly greater mzxid triggers NodeUpdated while retaining existing children.
        t.apply(p("/r/a"), Some(node(2, 7)), &mut ev);
        assert_eq!(names(&ev), ["~/r/a"]);
        assert_eq!(t.children(p("/r/a")).unwrap(), vec!["k"]);

        // Differing czxid indicates node replacement; existing descendants are purged.
        ev.clear();
        t.apply(p("/r/a"), Some(node(8, 8)), &mut ev);
        assert_eq!(names(&ev), ["-/r/a/k", "-/r/a", "+/r/a"]);
        assert!(t.children(p("/r/a")).unwrap().is_empty());

        // Node absence triggers subtree removal.
        ev.clear();
        t.apply(p("/r/a"), None, &mut ev);
        assert_eq!(names(&ev), ["-/r/a"]);
        assert!(t.children(p("/r")).unwrap().is_empty());

        // Nodes lacking a cached parent are rejected.
        ev.clear();
        t.apply(p("/r/x/y"), Some(node(9, 9)), &mut ev);
        assert!(ev.is_empty());
    }

    #[test]
    fn resync_gives_one_event_for_each_difference() {
        let mut t = tree(
            "/r",
            &[
                ("/r", 1, 1),
                ("/r/same", 2, 2),
                ("/r/changed", 3, 3),
                ("/r/gone", 4, 4),
                ("/r/gone/kid", 5, 5),
                ("/r/again", 6, 6),
                ("/r/again/old", 7, 7),
            ],
        );
        let new = tree(
            "/r",
            &[
                ("/r", 1, 1),
                ("/r/same", 2, 2),
                ("/r/changed", 3, 10),
                ("/r/again", 11, 11),
                ("/r/new", 12, 12),
                ("/r/new/kid", 13, 13),
            ],
        );
        let mut ev = VecDeque::new();
        t.resync(new, &mut ev);
        assert_eq!(
            names(&ev),
            [
                "-/r/again/old",
                "-/r/gone/kid",
                "-/r/again",
                "-/r/gone",
                "+/r/again",
                "~/r/changed",
                "+/r/new",
                "+/r/new/kid",
            ]
        );
        assert_eq!(
            t.children(p("/r")).unwrap(),
            vec!["again", "changed", "new", "same"]
        );
    }

    #[test]
    fn added_events_are_parents_first() {
        let t = tree(
            "/r",
            &[
                ("/r", 1, 1),
                ("/r/b", 2, 2),
                ("/r/b/c", 3, 3),
                ("/r/z", 4, 4),
            ],
        );
        let mut ev = VecDeque::new();
        t.added_events(&mut ev);
        assert_eq!(names(&ev), ["+/r", "+/r/b", "+/r/z", "+/r/b/c"]);
    }
}
