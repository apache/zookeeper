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

//! Fuzzes SASL negotiation frames and mechanism callbacks on an active connection.
//!
//! Forces `opts.sasl = true` so that following the initial `ConnectResponse`, inbound server
//! frames are routed through the SASL response decoder until authentication completes or fails.
//! The command stream also scripts mechanism step outcomes via `PlanSasl`.

#![no_main]
#![forbid(unsafe_code)]

use libfuzzer_sys::fuzz_target;
use zk_fuzz::Cursor;
use zk_fuzz::sim::{Options, Sim};

fuzz_target!(|data: &[u8]| {
    let mut c = Cursor::new(data);
    let mut opts = Options::read(&mut c);
    opts.sasl = true;
    let mut sim = Sim::new(opts);
    sim.settle();
    sim.push_connect_response(30_000, 0x5a51, false);
    sim.settle();
    sim.run_commands(&mut c);
    sim.finish();
});
