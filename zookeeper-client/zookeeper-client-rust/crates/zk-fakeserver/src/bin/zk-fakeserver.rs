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

//! Runs one standalone fake server for test harnesses that are not Cargo tests.
//!
//! The C ABI tests use this program as their server when no ZooKeeper server is given. The
//! server listens on an ephemeral port of `127.0.0.1`. The program writes the connect string as
//! one line on standard output. The program stops at the end of file on standard input. Thus the
//! server cannot outlive the harness that holds the write end of that input.

use std::io::{self, ErrorKind, Read, Write};
use std::process::ExitCode;

use zk_fakeserver::FakeServer;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            // The exit status reports the failure if stderr is closed.
            let _ = writeln!(io::stderr().lock(), "zk-fakeserver: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> io::Result<()> {
    let server = FakeServer::start()?;
    {
        let mut out = io::stdout().lock();
        writeln!(out, "{}", server.connect_string())?;
        out.flush()?;
    }
    let mut input = io::stdin().lock();
    let mut sink = [0_u8; 64];
    loop {
        match input.read(&mut sink) {
            Ok(0) => break,
            Ok(_) => {}
            Err(e) if e.kind() == ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    drop(server);
    Ok(())
}
