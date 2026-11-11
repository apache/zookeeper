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

//! Token types bridging asynchronous driver lifecycles to raw C pointer representations.
//!
//! Completion tokens wrap the raw addresses of legacy C `struct zks_req` control records. Rust
//! manages their lifecycle from operation submission in [`crate::abi::submit::zkrs_submit`] until
//! one of two terminal transitions: either the C draining loop accepts the token from an outbox
//! `RESULT` item, or the token drops internally, triggering the host vtable's `discard_token`
//! callback to free C-allocated memory and notify waiting threads. Linear type semantics (`!Clone`,
//! `!Copy`) enforce strictly-once delivery guarantees.
//!
//! Watcher tokens encapsulate caller-provided `(watcher_fn, context)` callback pairs. Because they
//! carry pure function pointer and context references without owning C heap allocations,
//! [`CWatcher`] implements `Copy` and `Clone`.

use zk_proto::Tokens;

use super::raw;
use super::types::{ZKRS_K_STRING_STAT, zkrs_watcher};

/// Type family implementing the protocol token configuration trait for C FFI bindings.
#[derive(Debug)]
pub(crate) struct CTokens;

impl Tokens for CTokens {
    type Completion = CReq;
    type Watcher = CWatcher;
    type Auth = CReq;
}

/// RAII wrapper managing ownership and cleanup of a C `struct zks_req` memory pointer.
///
/// Dropping a [`CReq`] invokes the host vtable's `discard_token` callback, deallocating the
/// underlying C structure and aborting any synchronous waiters with `ZCLOSING`. To transfer
/// ownership back to C upon completion dispatch, call [`CReq::into_raw`], which suppresses
/// destructor execution.
///
/// An address of zero represents an allocation failure in the C shim layer; such tokens are
/// marked as input defects and dropped silently without invoking discard callbacks.
#[derive(Debug)]
pub(crate) struct CReq {
    addr: usize,
    kind: u8,
    discard: unsafe extern "C" fn(usize),
}

impl CReq {
    /// Constructs a new request token wrapping a C allocation address and result kind descriptor.
    ///
    /// Stores the operation's completion discriminant (`ZKRS_K_*`) to guide response formatting,
    /// alongside a function pointer to the host vtable's token discard routine.
    pub(crate) fn new(addr: usize, kind: u32, discard: unsafe extern "C" fn(usize)) -> Self {
        let kind = u8::try_from(kind)
            .ok()
            .filter(|k| u32::from(*k) <= ZKRS_K_STRING_STAT)
            .unwrap_or(u8::MAX);
        Self {
            addr,
            kind,
            discard,
        }
    }

    /// Indicates whether this token wraps a null address resulting from an allocation failure.
    #[cfg(test)]
    pub(crate) fn is_null(&self) -> bool {
        self.addr == 0
    }

    /// Returns the operation completion kind discriminant (`ZKRS_K_*`).
    pub(crate) fn kind(&self) -> u32 {
        u32::from(self.kind)
    }

    /// Relinquishes ownership of the raw token address to C without triggering the discard
    /// callback.
    pub(crate) fn into_raw(self) -> usize {
        let a = self.addr;
        std::mem::forget(self);
        a
    }
}

impl Drop for CReq {
    fn drop(&mut self) {
        if self.addr != 0 {
            raw::vt_discard_token_fn(self.discard, self.addr);
        }
    }
}

/// Encapsulates a C watcher callback function pointer and associated user context pointer.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct CWatcher {
    /// Raw address of the legacy C watcher callback function.
    pub(crate) f: usize,
    /// Opaque pointer to user defined context data passed to the watcher callback.
    pub(crate) ctx: usize,
}

impl CWatcher {
    /// Instantiates a watcher pair from request parameters; returns `None` if the function pointer
    /// is null.
    pub(crate) fn from_request(f: usize, ctx: usize) -> Option<Self> {
        (f != 0).then_some(Self { f, ctx })
    }

    /// Constructs a [`zkrs_watcher`] FFI view struct representing this callback pair.
    pub(crate) fn view(self) -> zkrs_watcher {
        zkrs_watcher {
            r#fn: self.f,
            ctx: self.ctx,
        }
    }
}

impl From<zkrs_watcher> for CWatcher {
    fn from(w: zkrs_watcher) -> Self {
        Self {
            f: w.r#fn,
            ctx: w.ctx,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::standin;
    use super::super::types::ZKRS_K_DATA;
    use super::*;

    #[test]
    fn drop_discards_and_into_raw_does_not() {
        let _rec = standin::recorder();
        let a = CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token);
        let b = CReq::new(0x2000, ZKRS_K_DATA, standin::discard_token);
        let z = CReq::new(0, 99, standin::discard_token);
        assert!(z.is_null());
        assert_eq!(z.kind(), u32::from(u8::MAX));
        drop(z);
        assert_eq!(a.kind(), ZKRS_K_DATA);
        drop(a);
        assert_eq!(b.into_raw(), 0x2000);
        assert_eq!(standin::discarded(), vec![0x1000]);
    }

    #[test]
    fn watcher_zero_sets_no_watch() {
        assert_eq!(CWatcher::from_request(0, 9), None);
        let w = CWatcher::from_request(5, 9);
        assert_eq!(
            w.map(CWatcher::view),
            Some(zkrs_watcher { r#fn: 5, ctx: 9 })
        );
    }
}
