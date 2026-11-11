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

//! Legacy chained hash table implementation matching C client behavior (`hashtable.c`,
//! `hashtable_itr.c`).
//!
//! Historical C ZooKeeper clients organize active watch registrations within chained hash tables.
//! Because external applications and compatibility suites can observe iteration ordering across
//! watched paths, this implementation faithfully replicates the C hash calculation, bucket
//! progression, collision chaining, and dynamic prime-based table resizing:
//!
//! - Key hashing utilizes the DJB2 algorithm operating over C `unsigned int` accumulators,
//!   respecting target platform C `char` signedness conventions.
//! - A bitwise permutation mixer derived from Java 1.4 hash table implementations post-processes
//!   raw hashes.
//! - Bucket assignment applies modulo arithmetic against a standardized sequence of prime moduli.
//! - Table expansion triggers when entry count exceeds `ceil(capacity * 0.65f)`, computed in
//!   single-precision float.
//! - Entry collisions are resolved via bucket chaining with head-first insertion.
//! - Table resizing iterates prior buckets in ascending sequence, migrating chained elements
//!   head-to-tail and prepending them onto target buckets (reversing intra-bucket collision order
//!   across resizes).
//! - Table traversal visits buckets in numerical order, traversing each bucket chain from head to
//!   tail.
//!
//! Byte strings representing keys terminate at the first embedded null byte, matching C string
//! semantics.

use std::collections::BTreeMap;

/// Predefined prime sequence governing hash table bucket capacities (`hashtable.c`, `primes`).
pub const PRIMES: [u32; 26] = [
    53, 97, 193, 389, 769, 1543, 3079, 6151, 12289, 24593, 49157, 98317, 196613, 393241, 786433,
    1572869, 3145739, 6291469, 12582917, 25165843, 50331653, 100663319, 201326611, 402653189,
    805306457, 1610612741,
];

/// Maximum allowable load factor (0.65f) before triggering table expansion.
pub const MAX_LOAD_FACTOR: f32 = 0.65;

/// Initial capacity requested by the legacy C client when allocating watcher tables
/// (`create_zk_hashtable`).
pub const ZK_TABLE_MIN_SIZE: u32 = 32;

/// Maximum allowable initial capacity accepted by `create_hashtable`.
pub const MAX_MIN_SIZE: u32 = 1 << 30;

/// Target compilation platform C `char` signedness flag.
///
/// Follows target ABI definitions: `char` is signed on x86-64 and Apple Silicon, but unsigned on
/// aarch64 Linux. Configuring this flag enables the hash table to reproduce identical bucket
/// assignments and traversal sequences on the respective host platforms.
pub const TARGET_C_CHAR_SIGNED: bool = std::ffi::c_char::MIN != 0;

/// Extracts the null-terminated prefix of a byte slice, emulating C string termination semantics.
pub fn c_str(bytes: &[u8]) -> &[u8] {
    match bytes.iter().position(|&b| b == 0) {
        Some(end) => bytes.get(..end).unwrap_or(bytes),
        None => bytes,
    }
}

/// Computes the DJB2 string hash conforming to `string_hash_djb2` in `zk_hashtable.c`.
///
/// Accumulates bytes into a 32-bit unsigned accumulator. If `c_char_signed` is asserted,
/// bytes possessing the high bit (`>= 0x80`) undergo sign extension prior to accumulation.
pub fn djb2(key: &[u8], c_char_signed: bool) -> u32 {
    let mut h: u32 = 5381;
    for &b in c_str(key) {
        let c: i32 = if c_char_signed {
            i32::from(i8::from_ne_bytes([b]))
        } else {
            i32::from(b)
        };
        // Convert to 32-bit unsigned integer using wrapping arithmetic matching C unsigned
        // conversions.
        let c = u32::from_ne_bytes(c.to_ne_bytes());
        h = h.wrapping_shl(5).wrapping_add(h).wrapping_add(c);
    }
    h
}

/// Bitwise hash mixer ported from `hashtable.c` (`hash`) to disperse clustered hash keys.
pub fn mix(h: u32) -> u32 {
    let mut i = h;
    i = i.wrapping_add(!i.wrapping_shl(9));
    i ^= i.wrapping_shr(14) | i.wrapping_shl(18);
    i = i.wrapping_add(i.wrapping_shl(4));
    i ^= i.wrapping_shr(10) | i.wrapping_shl(22);
    i
}

/// Calculates the maximum entry count threshold permitted for a given prime capacity.
///
/// Preserves legacy single-precision floating-point arithmetic and `ceil` ceiling rounding.
pub fn load_limit_for(size: u32) -> u32 {
    #[allow(clippy::as_conversions, clippy::cast_precision_loss)]
    let product = (size as f32) * MAX_LOAD_FACTOR;
    let limit = f64::from(product).ceil();
    // Capacity values remain within valid u32 range across all prime table entries.
    #[allow(
        clippy::as_conversions,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    let limit = limit as u32;
    limit
}

/// Selects the initial prime table index whose capacity strictly exceeds `min_size`.
fn initial_prime_index(min_size: u32) -> usize {
    PRIMES.iter().position(|&p| p > min_size).unwrap_or(0)
}

#[derive(Debug, Clone)]
struct Entry<V> {
    key: Vec<u8>,
    hash: u32,
    value: V,
}

/// High-fidelity port of the legacy C chained hash table, keyed by byte slices.
///
/// Permits duplicate keys, mimicking the underlying C data structure. In practice,
/// the C client executes existence lookups prior to insertion to prevent duplicates.
///
/// Buckets are stored in a sparse ordered map (`BTreeMap`), scaling memory consumption
/// proportionally with entry count rather than table prime capacity. Collision chains
/// maintain head-first insertion ordering.
#[derive(Debug, Clone)]
pub struct LegacyHashTable<V> {
    buckets: BTreeMap<u32, Vec<Entry<V>>>,
    table_length: u32,
    entry_count: u32,
    load_limit: u32,
    prime_index: usize,
    c_char_signed: bool,
}

impl<V> LegacyHashTable<V> {
    /// Constructs a hash table initialized to the smallest prime exceeding `min_size`
    /// (`create_hashtable`).
    ///
    /// Returns `None` if `min_size` exceeds `MAX_MIN_SIZE`, matching C `NULL` error returns.
    pub fn with_min_size(min_size: u32, c_char_signed: bool) -> Option<Self> {
        if min_size > MAX_MIN_SIZE {
            return None;
        }
        let prime_index = initial_prime_index(min_size);
        let table_length = PRIMES.get(prime_index).copied().unwrap_or(53);
        Some(Self {
            buckets: BTreeMap::new(),
            table_length,
            entry_count: 0,
            load_limit: load_limit_for(table_length),
            prime_index,
            c_char_signed,
        })
    }

    /// Constructs a hash table preallocated with default watcher table capacity (53 buckets).
    pub fn new(c_char_signed: bool) -> Self {
        let prime_index = initial_prime_index(ZK_TABLE_MIN_SIZE);
        let table_length = PRIMES.get(prime_index).copied().unwrap_or(53);
        Self {
            buckets: BTreeMap::new(),
            table_length,
            entry_count: 0,
            load_limit: load_limit_for(table_length),
            prime_index,
            c_char_signed,
        }
    }

    /// Constructs a watcher table using the target platform's default C `char` signedness.
    pub fn new_native() -> Self {
        Self::new(TARGET_C_CHAR_SIGNED)
    }

    /// Indicates whether key hashing treats bytes as signed C `char` values.
    pub fn c_char_signed(&self) -> bool {
        self.c_char_signed
    }

    /// Returns the total count of key-value entries currently held in the table
    /// (`hashtable_count`).
    pub fn len(&self) -> usize {
        usize::try_from(self.entry_count).unwrap_or(usize::MAX)
    }

    /// Indicates whether the table contains zero entries.
    pub fn is_empty(&self) -> bool {
        self.entry_count == 0
    }

    /// Returns the active prime bucket capacity of the table (`tablelength`).
    pub fn bucket_count(&self) -> u32 {
        self.table_length
    }

    /// Returns the active entry threshold that triggers table resizing (`loadlimit`).
    pub fn load_limit(&self) -> u32 {
        self.load_limit
    }

    /// Computes the mixed DJB2 hash for the specified key using the table's signedness rule.
    pub fn hash_of(&self, key: &[u8]) -> u32 {
        mix(djb2(key, self.c_char_signed))
    }

    /// Computes the target bucket index for a key under the current prime bucket capacity.
    pub fn bucket_of(&self, key: &[u8]) -> u32 {
        index_for(self.table_length, self.hash_of(key))
    }

    /// Expands the hash table to the next prime capacity in the sequence (`hashtable_expand`).
    ///
    /// Preserves C bucket re-chaining behavior: previous buckets are traversed in numerical order,
    /// and elements are migrated from the head of their old chain to the head of the new chain.
    fn expand(&mut self) {
        let Some(next_index) = self.prime_index.checked_add(1) else {
            return;
        };
        let Some(&new_size) = PRIMES.get(next_index) else {
            return;
        };
        self.prime_index = next_index;
        let old = std::mem::take(&mut self.buckets);
        // Traverse existing buckets in ascending order, prepending entries onto new chains.
        for chain in old.into_values() {
            for entry in chain {
                let index = index_for(new_size, entry.hash);
                self.buckets.entry(index).or_default().insert(0, entry);
            }
        }
        self.table_length = new_size;
        self.load_limit = load_limit_for(new_size);
    }

    /// Inserts a key-value entry into the table matching `hashtable_insert`.
    ///
    /// If the updated entry count exceeds the current load limit, the table expands prior
    /// to insertion. New entries are prepended to the head of the designated bucket chain.
    pub fn insert(&mut self, key: &[u8], value: V) {
        self.entry_count = self.entry_count.saturating_add(1);
        if self.entry_count > self.load_limit {
            self.expand();
        }
        let key = c_str(key);
        let hash = self.hash_of(key);
        let index = index_for(self.table_length, hash);
        self.buckets.entry(index).or_default().insert(
            0,
            Entry {
                key: key.to_vec(),
                hash,
                value,
            },
        );
    }

    /// Locates the bucket index and chain offset of the first entry matching `key`.
    fn locate(&self, key: &[u8]) -> Option<(u32, usize)> {
        let key = c_str(key);
        let hash = self.hash_of(key);
        let index = index_for(self.table_length, hash);
        let chain = self.buckets.get(&index)?;
        let pos = chain
            .iter()
            .position(|e| e.hash == hash && e.key.as_slice() == key)?;
        Some((index, pos))
    }

    /// Retrieves an immutable reference to the value associated with `key` (`hashtable_search`).
    pub fn get(&self, key: &[u8]) -> Option<&V> {
        let (index, pos) = self.locate(key)?;
        self.buckets
            .get(&index)
            .and_then(|c| c.get(pos))
            .map(|e| &e.value)
    }

    /// Retrieves a mutable reference to the value associated with `key`.
    pub fn get_mut(&mut self, key: &[u8]) -> Option<&mut V> {
        let (index, pos) = self.locate(key)?;
        self.buckets
            .get_mut(&index)
            .and_then(|c| c.get_mut(pos))
            .map(|e| &mut e.value)
    }

    /// Checks whether an entry exists for the specified key.
    pub fn contains_key(&self, key: &[u8]) -> bool {
        self.locate(key).is_some()
    }

    /// Removes the first entry matching `key` and returns its value (`hashtable_remove`).
    pub fn remove(&mut self, key: &[u8]) -> Option<V> {
        let (index, pos) = self.locate(key)?;
        let chain = self.buckets.get_mut(&index)?;
        if pos >= chain.len() {
            return None;
        }
        let entry = chain.remove(pos);
        if chain.is_empty() {
            self.buckets.remove(&index);
        }
        self.entry_count = self.entry_count.saturating_sub(1);
        Some(entry.value)
    }

    /// Clears all entries from the table while preserving allocated prime bucket capacity.
    pub fn clear(&mut self) {
        self.buckets.clear();
        self.entry_count = 0;
    }

    /// Iterates across table entries in legacy C traversal order (ascending buckets, head-to-tail
    /// chains).
    pub fn iter(&self) -> impl Iterator<Item = (&[u8], &V)> + '_ {
        self.buckets
            .values()
            .flat_map(|chain| chain.iter().map(|e| (e.key.as_slice(), &e.value)))
    }

    /// Iterates across table keys in legacy C traversal order.
    pub fn keys(&self) -> impl Iterator<Item = &[u8]> + '_ {
        self.iter().map(|(k, _)| k)
    }

    /// Iterates mutably across table entries in legacy C traversal order.
    pub fn iter_mut(&mut self) -> impl Iterator<Item = (&[u8], &mut V)> + '_ {
        self.buckets
            .values_mut()
            .flat_map(|chain| chain.iter_mut().map(|e| (e.key.as_slice(), &mut e.value)))
    }
}

/// Computes the bucket array index for a given hash value matching `indexFor` in
/// `hashtable_private.h`.
fn index_for(table_length: u32, hash: u32) -> u32 {
    hash.checked_rem(table_length).unwrap_or(0)
}
