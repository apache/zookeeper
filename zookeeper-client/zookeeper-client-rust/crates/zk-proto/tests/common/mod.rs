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

//! Shared test fixtures, token definitions, and deterministic RNG doubles for `zk-proto`
//! integration tests.

#![allow(dead_code, missing_docs)]

use zk_proto::Connection;
use zk_proto::compat::Compat;
use zk_proto::config::{Config, DriverMode};
use zk_proto::hooks::{AllLevels, NoDefaultWatcher, Rng, Tokens};
use zk_proto::time::Millis;

/// Trivial integer backed [`Tokens`] implementation for protocol state machine testing.
pub(crate) struct Tk;

impl Tokens for Tk {
    type Completion = u32;
    type Watcher = u32;
    type Auth = u32;
}

/// Deterministic pseudorandom number generator double that unconditionally yields zero.
pub(crate) struct FixedRng;

impl Rng for FixedRng {
    fn on_resolution(&mut self) {}
    fn random(&mut self) -> i64 {
        0
    }
    fn drand48(&mut self) -> f64 {
        0.0
    }
}

/// Instantiates a single threaded [`Connection`] configured with `cfg`, `compat`, and
/// [`FixedRng`].
pub(crate) fn conn_with(cfg: Config, compat: Compat) -> Connection<Tk> {
    Connection::new(
        cfg,
        compat,
        DriverMode::ST,
        Box::new(FixedRng),
        Box::new(AllLevels),
        Box::new(NoDefaultWatcher),
        Millis(0),
    )
}
