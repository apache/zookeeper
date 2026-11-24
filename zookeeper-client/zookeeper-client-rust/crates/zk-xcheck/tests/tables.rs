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

//! Differential tests for legacy hash tables and watcher registration tables.
//!
//! Cross validates `zk_proto::legacy::hashtable` and `zk_proto::legacy::watchers` against
//! `hashtable.c`, `hashtable_itr.c`, and `zk_hashtable.c`. Randomized operation sequences
//! drive both implementations in lockstep, verifying bucket layout, resize behavior, and
//! iteration order across both signed and unsigned C `char` watcher driver variants.

#![cfg(zkx_c)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    missing_docs
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::legacy::chroot::strip_c;
use zk_proto::legacy::hashtable::{LegacyHashTable, djb2};
use zk_proto::legacy::watchers::{LegacyWatchTable, LegacyWatchers, SESSION_EVENT};
use zk_xcheck::rng::SplitMix64;
use zk_xcheck::{CApi, with_c};

/// Generates a non-empty key up to `max` bytes, including high-bit bytes (`>= 0x80`) that
/// diverge under signed versus unsigned `char` DJB2 hashing.
fn random_key(rng: &mut SplitMix64, max: usize) -> Vec<u8> {
    let len = 1 + rng.index(max);
    (0..len)
        .map(|_| {
            if rng.chance(1, 4) {
                0x80 + rng.below(0x80) as u8
            } else {
                b'a' + rng.below(6) as u8
            }
        })
        .collect()
}

#[test]
fn djb2_matches_both_char_signs() {
    let mut rng = SplitMix64::new(0x5eed_0001);
    with_c(|c| {
        for _ in 0..20_000 {
            let key = random_key(&mut rng, 24);
            assert_eq!(c.djb2(&key, true), djb2(&key, true), "signed {key:?}");
            assert_eq!(c.djb2(&key, false), djb2(&key, false), "unsigned {key:?}");
        }
        assert_eq!(c.djb2(b"", true), djb2(b"", true));
    });
}

fn compare_tables(c_table: &zk_xcheck::CHashTable<'_>, r: &LegacyHashTable<i32>, ctx: &str) {
    let rust: Vec<(Vec<u8>, i32)> = r.iter().map(|(k, v)| (k.to_vec(), *v)).collect();
    assert_eq!(c_table.dump(), rust, "iteration order: {ctx}");
    assert_eq!(c_table.count() as usize, r.len(), "count: {ctx}");
    assert_eq!(c_table.table_length(), r.bucket_count(), "buckets: {ctx}");
    assert_eq!(c_table.load_limit(), r.load_limit(), "load limit: {ctx}");
}

fn run_hashtable(c: &CApi, seed: u64, signed: bool) {
    let mut rng = SplitMix64::new(seed);
    let min_size = rng
        .pick(&[0_u32, 1, 5, 16, 32, 52, 53, 100])
        .copied()
        .unwrap();
    let mut ct = c.ht_new(min_size, signed).unwrap();
    let mut rt = LegacyHashTable::<i32>::with_min_size(min_size, signed).unwrap();
    compare_tables(&ct, &rt, "new");
    let ops = 600 + rng.index(1200);
    let mut keys: Vec<Vec<u8>> = Vec::new();
    for step in 0..ops {
        let ctx = format!("seed {seed:#x} signed {signed} step {step}");
        let roll = rng.below(10);
        if roll < 6 || keys.is_empty() {
            // Insert predominantly new keys while occasionally re-inserting existing keys to
            // exercise duplicate-key chaining semantics.
            let key = if rng.chance(1, 10) && !keys.is_empty() {
                rng.pick(&keys).cloned().unwrap()
            } else {
                random_key(&mut rng, 10)
            };
            let value = step as i32;
            assert!(ct.insert(&key, value));
            rt.insert(&key, value);
            assert_eq!(ct.hash(&key), rt.hash_of(&key), "hash: {ctx}");
            keys.push(key);
        } else if roll < 8 {
            let key = rng.pick(&keys).cloned().unwrap();
            assert_eq!(ct.remove(&key), rt.remove(&key), "remove: {ctx}");
        } else {
            let key = if rng.chance(1, 2) {
                rng.pick(&keys).cloned().unwrap()
            } else {
                random_key(&mut rng, 10)
            };
            assert_eq!(ct.search(&key), rt.get(&key).copied(), "search: {ctx}");
        }
        if step % 16 == 0 || step + 1 == ops {
            compare_tables(&ct, &rt, &ctx);
        }
    }
    assert!(
        rt.bucket_count() > 97,
        "seed {seed:#x}: the table did not grow"
    );
}

#[test]
fn hashtable_random_sequences() {
    with_c(|c| {
        for seed in 0..40_u64 {
            run_hashtable(c, 0xa11c_e000 + seed, true);
            run_hashtable(c, 0xa11c_e000 + seed, false);
        }
    });
}

#[test]
fn hashtable_create_limits() {
    with_c(|c| {
        assert!(c.ht_new(1 << 30, true).is_some());
        assert!(c.ht_new((1 << 30) + 1, true).is_none());
        assert!(LegacyHashTable::<i32>::with_min_size((1 << 30) + 1, true).is_none());
        let ct = c.ht_new(32, true).unwrap();
        let rt = LegacyHashTable::<i32>::new(true);
        assert_eq!(ct.table_length(), 53);
        compare_tables(&ct, &rt, "default");
    });
}

type W = (i32, usize);

const PATHS: &[&[u8]] = &[
    b"/a",
    b"/b",
    b"/a/b",
    b"/app",
    b"/app/x",
    b"/app2/y",
    b"/\xc3\xa9",
    b"/\xff\x80",
    b"/zookeeper/config",
    b"/",
];

fn random_watcher(rng: &mut SplitMix64) -> W {
    (rng.below(4) as i32, rng.below(3) as usize)
}

fn table_dump(t: &LegacyWatchTable<W>) -> Vec<(Vec<u8>, Vec<W>)> {
    t.iter().map(|(k, v)| (k.to_vec(), v.to_vec())).collect()
}

fn compare_watchers(c: &CApi, r: &LegacyWatchers<W>, ctx: &str) {
    assert_eq!(c.zt_dump(0), table_dump(&r.data), "data table: {ctx}");
    assert_eq!(c.zt_dump(1), table_dump(&r.exist), "exist table: {ctx}");
    assert_eq!(c.zt_dump(2), table_dump(&r.child), "child table: {ctx}");
}

fn run_watchers(c: &CApi, seed: u64, chroot: Option<&[u8]>) {
    let mut rng = SplitMix64::new(seed);
    let signed = c.zt_char_signed();
    c.zt_reset();
    c.zt_set_chroot(chroot);
    let default = random_watcher(&mut rng);
    c.zt_set_default(default.0, default.1);
    let mut r = LegacyWatchers::<W>::new(signed);
    let mut delivered = 0_usize;
    for step in 0..400 {
        let ctx = format!("seed {seed:#x} step {step}");
        let path = *rng.pick(PATHS).unwrap();
        match rng.below(10) {
            0..=4 => {
                let table = rng.below(3) as i32;
                let w = random_watcher(&mut rng);
                c.zt_activate(table, path, w.0, w.1);
                let t = match table {
                    0 => &mut r.data,
                    1 => &mut r.exist,
                    _ => &mut r.child,
                };
                t.insert(path, w);
            }
            5 | 6 => {
                let ty = *rng.pick(&[1, 2, 3, 4, SESSION_EVENT, 0, -2, 5]).unwrap();
                let got = c.zt_event(ty, path);
                let want = r.collect(ty, path, &default);
                let got_w: Vec<W> = got.iter().map(|d| (d.func, d.ctx)).collect();
                delivered += got_w.len();
                assert_eq!(got_w, want, "delivery order: {ctx} type {ty}");
                let want_path = if ty == SESSION_EVENT {
                    path.to_vec()
                } else {
                    strip_c(chroot, path).path.into_owned()
                };
                for d in &got {
                    assert_eq!(d.event_type, ty, "event type: {ctx}");
                    assert_eq!(d.path, want_path, "event path: {ctx}");
                }
            }
            7 => {
                let wtype = rng.below(5) as i32;
                let w = if rng.chance(1, 4) {
                    None
                } else {
                    Some(random_watcher(&mut rng))
                };
                assert_eq!(
                    c.zt_has(path, wtype, w),
                    r.has_watcher(path, wtype, w.as_ref()),
                    "has watcher: {ctx} type {wtype} {w:?}"
                );
            }
            _ => {
                let wtype = rng.below(5) as i32;
                let w = if rng.chance(1, 6) {
                    None
                } else {
                    Some(random_watcher(&mut rng))
                };
                c.zt_remove(path, wtype, w);
                r.remove_watchers(path, wtype, w.as_ref());
            }
        }
        compare_watchers(c, &r, &ctx);
    }
    assert!(delivered > 0, "seed {seed:#x}: no watcher was delivered");
}

#[test]
fn watcher_tables_random_sequences() {
    with_c(|c| {
        for signed in [true, false] {
            c.zt_select(signed);
            for seed in 0..60_u64 {
                run_watchers(c, 0x3a7c_4000 + seed, None);
            }
            for seed in 0..20_u64 {
                run_watchers(c, 0x3a7c_5000 + seed, Some(b"/app"));
            }
            c.zt_set_chroot(None);
        }
    });
}

#[test]
fn watcher_driver_copies_have_both_signs() {
    with_c(|c| {
        assert_eq!(c.zt_char_signed(), c.char_is_signed());
        // Identical high-bit path insertions yield distinct bucket orders under signed vs.
        // unsigned `char` hashing.
        let mut dumps = Vec::new();
        for signed in [true, false] {
            c.zt_select(signed);
            c.zt_reset();
            for i in 0..40_u8 {
                c.zt_activate(1, &[b'/', 0x80 + i, 0xc0 + i], 0, 0);
            }
            dumps.push(c.zt_dump(1));
        }
        assert_ne!(dumps[0], dumps[1]);
    });
}

/// Synthesizes a unique indexed watcher path containing random high-bit bytes (`>= 0x80`)
/// so bucket placement diverges between signed and unsigned `char` hash variants.
fn grown_path(rng: &mut SplitMix64, i: usize) -> Vec<u8> {
    let mut p = format!("/g{i}/").into_bytes();
    p.extend(random_key(rng, 8));
    p
}

/// Populates all three watcher tables across multiple prime resize thresholds using high-bit
/// paths and verifies table state and delivery order across node and session events.
fn run_grown_watchers(c: &CApi, seed: u64, chroot: Option<&[u8]>) {
    let mut rng = SplitMix64::new(seed);
    let signed = c.zt_char_signed();
    c.zt_reset();
    c.zt_set_chroot(chroot);
    let default = random_watcher(&mut rng);
    c.zt_set_default(default.0, default.1);
    let mut r = LegacyWatchers::<W>::new(signed);
    let mut paths: Vec<Vec<u8>> = Vec::new();
    // Assign predominantly distinct watcher contexts so delivery deduplication preserves
    // overall traversal order while still exercising duplicate suppression on reused entries.
    let mut used: Vec<W> = Vec::new();
    for i in 0..260 {
        let path = grown_path(&mut rng, i);
        for _ in 0..1 + rng.index(3) {
            let table = rng.below(3) as i32;
            let w = if rng.chance(1, 8) && !used.is_empty() {
                *rng.pick(&used).unwrap()
            } else {
                (rng.below(4) as i32, 100 + used.len())
            };
            used.push(w);
            c.zt_activate(table, &path, w.0, w.1);
            let t = match table {
                0 => &mut r.data,
                1 => &mut r.exist,
                _ => &mut r.child,
            };
            t.insert(&path, w);
        }
        paths.push(path);
        if i % 32 == 0 {
            compare_watchers(c, &r, &format!("seed {seed:#x} insert {i}"));
        }
    }
    let ctx = format!("seed {seed:#x} signed {signed}");
    compare_watchers(c, &r, &ctx);
    for (name, t) in [("data", &r.data), ("exist", &r.exist), ("child", &r.child)] {
        assert!(
            t.table().bucket_count() > 97,
            "{ctx}: the {name} table did not grow past two primes"
        );
    }
    // Verify watcher removal and delivery order for targeted node events on expanded tables.
    for step in 0..120 {
        let path = rng.pick(&paths).cloned().unwrap();
        let ty = *rng.pick(&[1, 2, 3, 4]).unwrap();
        let got: Vec<W> = c
            .zt_event(ty, &path)
            .iter()
            .map(|d| (d.func, d.ctx))
            .collect();
        assert_eq!(
            got,
            r.collect(ty, &path, &default),
            "{ctx} event {step} type {ty}"
        );
        compare_watchers(c, &r, &format!("{ctx} event {step}"));
    }
    // Session events collect and deliver all remaining watchers in hash table traversal order.
    let got: Vec<W> = c
        .zt_event(SESSION_EVENT, b"")
        .iter()
        .map(|d| (d.func, d.ctx))
        .collect();
    assert!(got.len() > 100, "{ctx}: too few watchers left");
    assert_eq!(
        got,
        r.collect(SESSION_EVENT, b"", &default),
        "{ctx} session"
    );
}

#[test]
fn watcher_tables_grow_with_high_bit_paths_under_both_signs() {
    with_c(|c| {
        for signed in [true, false] {
            c.zt_select(signed);
            for seed in 0..12_u64 {
                run_grown_watchers(c, 0x6e0f_0000 + seed, None);
            }
            run_grown_watchers(c, 0x6e0f_1000, Some(b"/g1"));
            c.zt_set_chroot(None);
        }
    });
}

#[test]
fn watcher_many_paths_grow_the_table() {
    // Insert enough distinct paths to trigger multiple table expansions, exercising bucket
    // chain reversal during rehash.
    with_c(|c| {
        let mut rng = SplitMix64::new(0x6e0e);
        c.zt_reset();
        c.zt_set_default(0, 0);
        let mut r = LegacyWatchers::<W>::new(c.zt_char_signed());
        for i in 0..300 {
            let path = format!("/p{i}/{}", rng.below(1000)).into_bytes();
            let w = random_watcher(&mut rng);
            c.zt_activate(1, &path, w.0, w.1);
            r.exist.insert(&path, w);
            if i % 3 == 0 {
                c.zt_activate(1, &path, 3, 7);
                r.exist.insert(&path, (3, 7));
            }
        }
        compare_watchers(c, &r, "grown");
        let got: Vec<W> = c
            .zt_event(SESSION_EVENT, b"")
            .iter()
            .map(|d| (d.func, d.ctx))
            .collect();
        assert_eq!(got, r.collect(SESSION_EVENT, b"", &(0, 0)));
    });
}
