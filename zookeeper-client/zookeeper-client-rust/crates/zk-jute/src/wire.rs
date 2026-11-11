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

//! Low-level Jute binary serialization engine, reader cursors, and wire primitives.
//!
//! This module implements the binary wire format specified by Apache ZooKeeper, compatible
//! with Java's `BinaryOutputArchive` / `BinaryInputArchive` and C's `recordio.c`. All numeric
//! primitives are encoded in network byte order (big-endian). Booleans are serialized as a single
//! byte (0 for false, non-zero for true). Dynamic constructs, including strings, raw buffers, and
//! vectors, are prefixed by a signed 32-bit big-endian length count, where a length of `-1`
//! denotes a `null` value.
//!
//! The decoding routines enforce strict bounds checking, matching C error behaviors while
//! guaranteeing memory safety and resilience against malformed wire payloads.

use core::fmt;

/// POSIX `E2BIG` errno value (7) representing buffer truncation or oversized inputs across target
/// platforms.
const E2BIG: i32 = 7;
/// POSIX `ENOMEM` errno value (12) representing memory allocation exhaustion or negative size
/// violations across target platforms.
const ENOMEM: i32 = 12;
/// POSIX `EINVAL` errno value (22) representing invalid arguments or malformed wire formats across
/// target platforms.
const EINVAL: i32 = 22;

/// Diagnostic errors produced when unmarshalling malformed or truncated Jute binary payloads.
///
/// Decoders never read beyond the bounds of their input slice. Each variant corresponds to a
/// specific failure mode in canonical C `ia_deserialize_*` routines (`recordio.c`), and
/// [`DecodeError::c_rc`] maps the error to the corresponding negative errno code.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DecodeError {
    /// Input buffer was exhausted before all required fields of the record could be fully decoded.
    Truncated {
        /// Number of bytes required to complete unmarshalling the current field.
        need: usize,
        /// Number of unconsumed bytes remaining in the input stream.
        have: usize,
    },
    /// String length prefix on the wire was less than -1, violating the Jute protocol
    /// specification.
    NegativeString(i32),
    /// Buffer length prefix on the wire was less than -1, violating the Jute protocol
    /// specification.
    NegativeBuffer(i32),
    /// Vector item count prefix on the wire was less than -1, violating the Jute protocol
    /// specification.
    NegativeCount(i32),
}

impl DecodeError {
    /// Maps this decoding failure to the corresponding negative errno return code produced by
    /// the canonical C client implementation in `recordio.c`.
    ///
    /// Specifically:
    /// - Buffer exhaustion maps to `-E2BIG`.
    /// - Negative string lengths map to `-EINVAL`.
    /// - Negative buffer or vector lengths map to `-ENOMEM`, reflecting the C client's allocation
    ///   guard failure.
    #[must_use]
    pub const fn c_rc(self) -> i32 {
        match self {
            Self::Truncated { .. } => -E2BIG,
            Self::NegativeString(_) => -EINVAL,
            Self::NegativeBuffer(_) | Self::NegativeCount(_) => -ENOMEM,
        }
    }
}

impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Truncated { need, have } => {
                write!(f, "truncated jute input: need {need} bytes, have {have}")
            }
            Self::NegativeString(len) => write!(f, "negative jute string length {len}"),
            Self::NegativeBuffer(len) => write!(f, "negative jute buffer length {len}"),
            Self::NegativeCount(count) => write!(f, "negative jute vector count {count}"),
        }
    }
}

impl std::error::Error for DecodeError {}

/// Zero-copy cursor over a contiguous byte slice providing bounds-checked Jute primitive decoding.
///
/// The reader guarantees that operations will not read beyond the bounded input slice. It permits
/// unconsumed trailing bytes upon completion, accommodating ZooKeeper protocol scenarios where
/// clients decode a backward-compatible subset of response fields (such as inspecting a
/// `Create2Response` payload using `CreateResponse` decoding rules).
#[derive(Debug, Clone)]
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    /// Initializes a new decoding cursor over the provided byte slice at position zero.
    #[must_use]
    pub const fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    /// Returns the cumulative byte offset consumed by the reader from the start of the buffer.
    #[must_use]
    pub const fn position(&self) -> usize {
        self.pos
    }

    /// Returns the number of unconsumed bytes remaining in the buffer.
    #[must_use]
    pub const fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }

    /// Returns `true` if all bytes in the underlying buffer have been consumed.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.remaining() == 0
    }

    /// Returns a subslice of all remaining unconsumed bytes without advancing the cursor.
    #[must_use]
    pub fn rest(&self) -> &'a [u8] {
        self.buf.get(self.pos..).unwrap_or_default()
    }

    /// Advances the cursor by `n` bytes and returns the consumed byte slice.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if fewer than `n` bytes remain. On failure,
    /// the reader position remains unchanged.
    pub fn read_bytes(&mut self, n: usize) -> Result<&'a [u8], DecodeError> {
        let have = self.remaining();
        let bytes = self
            .pos
            .checked_add(n)
            .and_then(|end| self.buf.get(self.pos..end))
            .ok_or(DecodeError::Truncated { need: n, have })?;
        self.pos = self.pos.saturating_add(n);
        Ok(bytes)
    }

    fn read_array<const N: usize>(&mut self) -> Result<[u8; N], DecodeError> {
        let have = self.remaining();
        let bytes = self.read_bytes(N)?;
        <[u8; N]>::try_from(bytes).map_err(|_| DecodeError::Truncated { need: N, have })
    }

    /// Reads a single unsigned byte from the input stream.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if the input stream has been fully consumed.
    pub fn read_u8(&mut self) -> Result<u8, DecodeError> {
        let [b] = self.read_array::<1>()?;
        Ok(b)
    }

    /// Reads a 32-bit signed integer in big-endian network byte order.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if fewer than four bytes remain in the stream.
    pub fn read_i32(&mut self) -> Result<i32, DecodeError> {
        self.read_array::<4>().map(i32::from_be_bytes)
    }

    /// Reads a 64-bit signed integer in big-endian network byte order.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if fewer than eight bytes remain in the stream.
    pub fn read_i64(&mut self) -> Result<i64, DecodeError> {
        self.read_array::<8>().map(i64::from_be_bytes)
    }

    /// Reads a boolean flag encoded as a single byte, interpreting any non-zero value as `true`.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError::Truncated`] if the input stream has been fully consumed.
    pub fn read_bool(&mut self) -> Result<bool, DecodeError> {
        self.read_u8().map(|b| b != 0)
    }

    /// Decodes a 32-bit length prefix followed by the corresponding byte slice, returning `None`
    /// for length -1 (null).
    fn read_len_prefixed(
        &mut self,
        negative: fn(i32) -> DecodeError,
    ) -> Result<Option<&'a [u8]>, DecodeError> {
        let len = self.read_i32()?;
        if len == -1 {
            return Ok(None);
        }
        // Mirror C client deserialization ordering: evaluate buffer availability prior to sign
        // validation.
        match usize::try_from(len) {
            Ok(n) => self.read_bytes(n).map(Some),
            Err(_) => Err(negative(len)),
        }
    }
}

/// Core serialization and deserialization contract for ZooKeeper Jute entities.
///
/// Implemented by all generated protocol structures as well as primitive wire types (`i32`, `i64`,
/// `bool`) and nullable wrapper types ([`JString`], [`JBuffer`], [`JVec`]). This common interface
/// enables uniform recursive serialization across composite records.
pub trait Record: Sized {
    /// Lower bound on the encoded wire size in bytes.
    ///
    /// Used by collection deserializers to compute conservative pre-allocation capacities
    /// before parsing individual element payloads, preventing out-of-memory denial of service.
    const MIN_ENCODED_LEN: usize = 0;

    /// Serializes the binary Jute representation of `self` into the destination buffer `out`.
    ///
    /// Serialization is infallible; collections exceeding `i32::MAX` items are clamped to preserve
    /// valid protocol framing.
    fn encode(&self, out: &mut Vec<u8>);

    /// Deserializes an instance from the provided reader cursor.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError`] if the byte stream is malformed or truncated.
    fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError>;

    /// Returns the exact number of bytes required to serialize this record on the wire.
    fn encoded_len(&self) -> usize;

    /// Serializes this record into a newly allocated byte vector sized exactly to
    /// [`Record::encoded_len`].
    fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.encoded_len());
        self.encode(&mut out);
        out
    }

    /// Deserializes a record instance from the leading bytes of `buf`, ignoring any unparsed
    /// trailing bytes.
    ///
    /// # Errors
    ///
    /// Returns [`DecodeError`] if the input buffer cannot be parsed into a valid record.
    fn from_bytes(buf: &[u8]) -> Result<Self, DecodeError> {
        Self::decode(&mut Reader::new(buf))
    }
}

impl Record for i32 {
    const MIN_ENCODED_LEN: usize = 4;

    fn encode(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.to_be_bytes());
    }

    fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError> {
        r.read_i32()
    }

    fn encoded_len(&self) -> usize {
        4
    }
}

impl Record for i64 {
    const MIN_ENCODED_LEN: usize = 8;

    fn encode(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.to_be_bytes());
    }

    fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError> {
        r.read_i64()
    }

    fn encoded_len(&self) -> usize {
        8
    }
}

impl Record for bool {
    const MIN_ENCODED_LEN: usize = 1;

    fn encode(&self, out: &mut Vec<u8>) {
        out.push(u8::from(*self));
    }

    fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError> {
        r.read_bool()
    }

    fn encoded_len(&self) -> usize {
        1
    }
}

/// Maximum element count representable by a 32-bit signed Jute length prefix (`i32::MAX`).
const MAX_WIRE_LEN: usize = i32::MAX as usize;

/// Clamps a collection length to `i32::MAX` and returns both the signed wire count and clamped
/// length.
fn wire_len(len: usize) -> (i32, usize) {
    let n = len.min(MAX_WIRE_LEN);
    (i32::try_from(n).unwrap_or(i32::MAX), n)
}

/// Encodes an optional byte slice as a 32-bit big-endian length prefix followed by the payload, or
/// `-1` for `None`.
fn encode_bytes(bytes: Option<&[u8]>, out: &mut Vec<u8>) {
    match bytes {
        None => out.extend_from_slice(&(-1i32).to_be_bytes()),
        Some(b) => {
            let (len, n) = wire_len(b.len());
            out.extend_from_slice(&len.to_be_bytes());
            out.extend_from_slice(b.get(..n).unwrap_or(b));
        }
    }
}

fn bytes_encoded_len(bytes: Option<&[u8]>) -> usize {
    bytes.map_or(4, |b| wire_len(b.len()).1.saturating_add(4))
}

/// Generates a null-preserving wrapper type for variable-length binary payloads with standard
/// conversions.
macro_rules! byte_wrapper {
    ($(#[$doc:meta])* $name:ident, $negative:path) => {
        $(#[$doc])*
        #[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Default)]
        pub enum $name {
            /// Protocol `null` representation, encoded on the wire with a length prefix of `-1`.
            #[default]
            Null,
            /// Valid byte sequence, which may be empty (0 bytes).
            Bytes(Vec<u8>),
        }

        impl $name {
            /// Returns `true` if this instance represents a protocol `null` value.
            #[must_use]
            pub const fn is_null(&self) -> bool {
                matches!(self, Self::Null)
            }

            /// Returns a reference to the underlying byte slice, or `None` if `null`.
            #[must_use]
            pub fn as_bytes(&self) -> Option<&[u8]> {
                match self {
                    Self::Null => None,
                    Self::Bytes(b) => Some(b),
                }
            }

            /// Consumes the wrapper and returns the owned byte vector, or `None` if `null`.
            #[must_use]
            pub fn into_bytes(self) -> Option<Vec<u8>> {
                match self {
                    Self::Null => None,
                    Self::Bytes(b) => Some(b),
                }
            }

            /// Returns the byte length of the payload, or 0 if `null`.
            #[must_use]
            pub fn len(&self) -> usize {
                self.as_bytes().map_or(0, <[u8]>::len)
            }

            /// Returns `true` if the instance is either `null` or contains zero bytes.
            #[must_use]
            pub fn is_empty(&self) -> bool {
                self.len() == 0
            }
        }

        impl From<Vec<u8>> for $name {
            fn from(b: Vec<u8>) -> Self {
                Self::Bytes(b)
            }
        }

        impl From<&[u8]> for $name {
            fn from(b: &[u8]) -> Self {
                Self::Bytes(b.to_vec())
            }
        }

        impl From<Option<Vec<u8>>> for $name {
            fn from(b: Option<Vec<u8>>) -> Self {
                b.map_or(Self::Null, Self::Bytes)
            }
        }

        impl Record for $name {
            const MIN_ENCODED_LEN: usize = 4;

            fn encode(&self, out: &mut Vec<u8>) {
                encode_bytes(self.as_bytes(), out);
            }

            fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError> {
                Ok(r.read_len_prefixed($negative)?
                    .map_or(Self::Null, |b| Self::Bytes(b.to_vec())))
            }

            fn encoded_len(&self) -> usize {
                bytes_encoded_len(self.as_bytes())
            }
        }
    };
}

byte_wrapper!(
    /// Jute `ustring` wire type: a length-prefixed, null-preserving UTF-8 string.
    ///
    /// Following the reference C implementation, decoding does not eagerly validate UTF-8
    /// correctness. A wire length of `-1` maps to [`JString::Null`]. While the C unmarshaller
    /// returns `-EINVAL` on `-1`, standard ZooKeeper servers never transmit `null` for fields
    /// observed by client decoders.
    JString,
    DecodeError::NegativeString
);

byte_wrapper!(
    /// Jute `buffer` wire type: an arbitrary length-prefixed raw byte array that can represent
    /// `null`.
    JBuffer,
    DecodeError::NegativeBuffer
);

impl JString {
    /// Attempts to interpret the payload as a UTF-8 string slice, returning `None` if `null` or
    /// invalid UTF-8.
    #[must_use]
    pub fn as_str(&self) -> Option<&str> {
        self.as_bytes().and_then(|b| core::str::from_utf8(b).ok())
    }
}

impl From<&str> for JString {
    fn from(s: &str) -> Self {
        Self::Bytes(s.as_bytes().to_vec())
    }
}

impl From<String> for JString {
    fn from(s: String) -> Self {
        Self::Bytes(s.into_bytes())
    }
}

impl From<Option<&str>> for JString {
    fn from(s: Option<&str>) -> Self {
        s.map_or(Self::Null, Self::from)
    }
}

/// Jute `vector<T>` wire type: a counted sequence of records supporting explicit `null` state.
///
/// A wire count of `-1` corresponds to [`JVec::Null`], semantically distinguished from an empty
/// vector (count 0). In the C client shim, this maps directly to `count == -1` and `data == NULL`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Default)]
pub enum JVec<T> {
    /// Protocol `null` collection, encoded on the wire with an element count of `-1`.
    #[default]
    Null,
    /// Instantiated collection containing zero or more records.
    Items(Vec<T>),
}

impl<T> JVec<T> {
    /// Returns `true` if this instance represents a protocol `null` collection.
    #[must_use]
    pub const fn is_null(&self) -> bool {
        matches!(self, Self::Null)
    }

    /// Returns a slice view of the contained elements, or `None` if `null`.
    #[must_use]
    pub fn as_slice(&self) -> Option<&[T]> {
        match self {
            Self::Null => None,
            Self::Items(v) => Some(v),
        }
    }

    /// Consumes the wrapper and returns the inner vector, or `None` if `null`.
    #[must_use]
    pub fn into_vec(self) -> Option<Vec<T>> {
        match self {
            Self::Null => None,
            Self::Items(v) => Some(v),
        }
    }

    /// Returns the number of elements in the collection, or 0 if `null`.
    #[must_use]
    pub fn len(&self) -> usize {
        self.as_slice().map_or(0, <[T]>::len)
    }

    /// Returns `true` if the collection is either `null` or contains zero elements.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Returns an iterator over references to the contained elements.
    pub fn iter(&self) -> core::slice::Iter<'_, T> {
        self.as_slice().unwrap_or_default().iter()
    }
}

impl<T> From<Vec<T>> for JVec<T> {
    fn from(v: Vec<T>) -> Self {
        Self::Items(v)
    }
}

impl<T> From<Option<Vec<T>>> for JVec<T> {
    fn from(v: Option<Vec<T>>) -> Self {
        v.map_or(Self::Null, Self::Items)
    }
}

impl<'a, T> IntoIterator for &'a JVec<T> {
    type Item = &'a T;
    type IntoIter = core::slice::Iter<'a, T>;

    fn into_iter(self) -> Self::IntoIter {
        self.iter()
    }
}

/// Ceiling on initial memory allocation (1 MiB) reserved when unmarshalling dynamic vectors.
///
/// Collections exceeding this initial threshold expand progressively as elements are parsed.
const PREALLOC_BYTES: usize = 1 << 20;

/// Calculates a defensive allocation capacity for an incoming vector of `count` elements.
///
/// Untrusted network input can declare arbitrary element counts up to `i32::MAX`. To prevent
/// memory exhaustion attacks, the pre-allocation is bounded by:
/// 1. The declared element count `count`.
/// 2. The maximum number of elements physically capable of fitting in the remaining unparsed bytes
///    (`fit`).
/// 3. A static memory allocation budget ([`PREALLOC_BYTES`]) divided by `size_of::<T>()`.
fn prealloc_len<T: Record>(count: usize, remaining: usize) -> usize {
    let fit = remaining
        .checked_div(T::MIN_ENCODED_LEN.max(1))
        .unwrap_or(0);
    let budget = PREALLOC_BYTES
        .checked_div(core::mem::size_of::<T>().max(1))
        .unwrap_or(0);
    count.min(fit).min(budget)
}

impl<T: Record> Record for JVec<T> {
    const MIN_ENCODED_LEN: usize = 4;

    fn encode(&self, out: &mut Vec<u8>) {
        match self {
            Self::Null => out.extend_from_slice(&(-1i32).to_be_bytes()),
            Self::Items(v) => {
                let (count, n) = wire_len(v.len());
                out.extend_from_slice(&count.to_be_bytes());
                for item in v.iter().take(n) {
                    item.encode(out);
                }
            }
        }
    }

    fn decode(r: &mut Reader<'_>) -> Result<Self, DecodeError> {
        let count = r.read_i32()?;
        if count == -1 {
            return Ok(Self::Null);
        }
        let n = usize::try_from(count).map_err(|_| DecodeError::NegativeCount(count))?;
        let mut items = Vec::with_capacity(prealloc_len::<T>(n, r.remaining()));
        for _ in 0..n {
            items.push(T::decode(r)?);
        }
        Ok(Self::Items(items))
    }

    fn encoded_len(&self) -> usize {
        match self {
            Self::Null => 4,
            Self::Items(v) => {
                let (_, n) = wire_len(v.len());
                v.iter()
                    .take(n)
                    .fold(4usize, |acc, item| acc.saturating_add(item.encoded_len()))
            }
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]
mod tests {
    use super::*;

    #[test]
    fn hostile_count_reserves_only_what_the_input_can_fill() {
        // Defensive allocation: a malicious header of i32::MAX with 5 remaining bytes permits at
        // most 1 string.
        let n = usize::try_from(i32::MAX).unwrap();
        assert_eq!(prealloc_len::<JString>(n, 5), 5 / JString::MIN_ENCODED_LEN);
        assert_eq!(prealloc_len::<i64>(n, 7), 0);
        assert_eq!(prealloc_len::<i32>(3, 400), 3);
        let mut bytes = i32::MAX.to_be_bytes().to_vec();
        bytes.extend_from_slice(&[0, 0, 0, 1, b'x']);
        let mut r = Reader::new(&bytes);
        assert_eq!(r.read_i32().unwrap(), i32::MAX);
        let items: Vec<JString> = Vec::with_capacity(prealloc_len::<JString>(n, r.remaining()));
        assert!(items.capacity() <= 1);
    }

    #[test]
    fn reservation_stays_inside_the_byte_budget() {
        // Guarantee allocation bounds stay strictly within the defensive byte budget regardless of
        // declared wire count.
        let n = usize::try_from(i32::MAX).unwrap();
        for remaining in [usize::MAX, isize::MAX as usize, 1 << 30] {
            let cap = prealloc_len::<JString>(n, remaining);
            assert!(cap * core::mem::size_of::<JString>() <= PREALLOC_BYTES);
            let cap = prealloc_len::<bool>(n, remaining);
            assert!(cap * core::mem::size_of::<bool>() <= PREALLOC_BYTES);
        }
    }
}
