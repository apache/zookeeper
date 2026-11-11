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

//! Network transport abstractions, nonblocking socket wrappers, poll event multiplexing,
//! connectivity probing, and TLS transport integration.
//!
//! The submodules provide safe network abstractions implemented without `unsafe` blocks.
//! The [`poll::Poll`] trait provides an extensible abstraction boundary supporting
//! platform-specific event readiness mechanisms, test fixtures, and FFI integration points.

pub mod poll;
pub mod probe;
pub mod sock;
pub mod tls;
pub mod waker;

pub use poll::{Poll, PollEvents, RustixPoll, map_revents};
pub use probe::{ProbeCtx, ProbeEnd, probe};
pub use sock::{Opened, TcpSock};
pub use waker::{WakeRx, Waker};
