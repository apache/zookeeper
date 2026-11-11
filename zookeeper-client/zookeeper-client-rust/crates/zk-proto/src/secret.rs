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

//! Secure memory handling for sensitive credential and session byte buffers.
//!
//! Authentication credentials, SASL exchange tokens, and ZooKeeper session passwords
//! are encapsulated within [`SecretBytes`]. This container wraps underlying memory in
//! zeroizing storage, ensuring sensitive cryptographic material is securely erased
//! upon disposal. The wrapper intentionally foregoes `Clone` to guarantee single-ownership
//! semantics and deterministic zeroization, while its `Debug` implementation redacts all
//! raw byte contents and reports only buffer length.

use core::fmt;

use zeroize::Zeroizing;

/// Sensitive byte buffer that is securely scrubbed from memory upon drop.
///
/// To prevent inadvertent proliferation of sensitive credentials in memory,
/// this type intentionally omits `Clone`. Explicit duplication via [`SecretBytes::duplicate`]
/// is required when multiple cryptographic or protocol layers need separate ownership.
pub struct SecretBytes(Zeroizing<Vec<u8>>);

impl SecretBytes {
    /// Constructs a secret buffer by taking ownership of an existing byte vector.
    #[must_use]
    pub fn new(bytes: Vec<u8>) -> Self {
        Self(Zeroizing::new(bytes))
    }

    /// Constructs a secret buffer by cloning the provided byte slice into zeroizing memory.
    #[must_use]
    pub fn from_slice(bytes: &[u8]) -> Self {
        Self::new(bytes.to_vec())
    }

    /// Creates an empty secret buffer with zero allocated capacity.
    #[must_use]
    pub fn empty() -> Self {
        Self::new(Vec::new())
    }

    /// Provides immutable access to the underlying secret byte slice.
    #[must_use]
    pub fn expose(&self) -> &[u8] {
        &self.0
    }

    /// Returns the length of the secret buffer in bytes.
    #[must_use]
    pub fn len(&self) -> usize {
        self.0.len()
    }

    /// Returns `true` if the secret buffer contains no bytes.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Creates an independent duplicate of the secret buffer.
    ///
    /// Both copies independently manage their allocated memory and securely zeroize
    /// their respective buffers when dropped.
    #[must_use]
    pub fn duplicate(&self) -> Self {
        Self::from_slice(&self.0)
    }
}

impl fmt::Debug for SecretBytes {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "SecretBytes({} bytes)", self.0.len())
    }
}

impl From<Vec<u8>> for SecretBytes {
    fn from(bytes: Vec<u8>) -> Self {
        Self::new(bytes)
    }
}
