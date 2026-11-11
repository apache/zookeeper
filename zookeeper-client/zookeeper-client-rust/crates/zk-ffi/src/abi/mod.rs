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

//! Architecture and module organization of the private C ABI bridging layer.
//!
//! The C ABI boundary is organized into distinct concentric architectural layers, progressing from
//! raw Foreign Function Interface bindings inward toward safe driver abstractions:
//!
//! - [`types`]: Public `repr(C)` structures, opaque pointer definitions, and status codes exposed
//!   via `zkrs.h` to the C shim.
//! - [`legacy`]: Exact `repr(C)` structural mirrors of legacy ZooKeeper C client data types (such
//!   as `Stat`, `Id`, and `ACL`).
//! - [`raw`]: The audited unsafe isolation boundary encapsulating all pointer dereferences, raw
//!   memory writes, and foreign vtable invocations. Outside of synthetic test stand-ins, this
//!   module constitutes the sole module where `unsafe_code` is permitted (with individual export
//!   symbols annotated specifically for `#[unsafe(no_mangle)]`).
//! - [`entry`]: FFI boundary guards (`ffi_guard` and `with_handle`) providing panic capture, error
//!   translation, and handle lifetime verification for all exported entry points.
//! - Export submodules: Concrete operational entry points including [`handle`] lifecycle
//!   management, [`outbox`] event dispatch, asynchronous request [`submit`] pathways, atomic
//!   [`multi`] transactions, session [`accessors`], [`results`] memory reclamation, and [`sasl`]
//!   authentication.
//! - `tls`: Transport Layer Security context initialization and configuration (active only in
//!   TLS-enabled builds).
//! - Driver adapters: Internal integration modules converting driver-level primitives to ABI
//!   representations, specifically [`tokens`], [`host`] connection string parsing, and [`logging`]
//!   integration.
//!
//! Because `cbindgen` requires `pub` visibility to discover and generate C symbol declarations,
//! this module explicitly permits `unreachable_pub`.

#![allow(unreachable_pub, reason = "cbindgen exports only pub items")]

pub mod types;

pub(crate) mod legacy;
#[allow(unsafe_code, reason = "the audited raw boundary")]
pub(crate) mod raw;

pub(crate) mod entry;

pub(crate) mod host;
pub(crate) mod logging;
pub(crate) mod tokens;

pub mod accessors;
pub mod handle;
pub mod multi;
pub mod outbox;
pub mod registry;
pub mod results;
pub mod sasl;
pub mod submit;
#[cfg(feature = "tls")]
pub(crate) mod tls;

#[cfg(test)]
pub(crate) mod standin;

#[cfg(all(test, loom))]
mod loom_models;
