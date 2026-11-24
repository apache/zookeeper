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

//! Session state inspection, server ensemble mutation, and network endpoint accessors.
//!
//! This module implements C ABI export functions querying and modifying session-level properties.
//! State inspections retrieve connection and timeout attributes directly from the underlying
//! `Driver` instance (specifically via `state`, `recv_timeout`, `peer_addr`, and `read_conn`).
//! Dynamic connection mutations invoke `Driver::with_conn` or `Driver::set_servers` across both
//! multithreaded (`_mt`) and single threaded (`_st`) client runtimes. These routines operate
//! synchronously without triggering asynchronous user completion callbacks.

use std::ffi::{c_char, c_int};

use zk_driver::host::log_record;
use zk_driver::{Item, Step};
use zk_proto::rc::{ZBADARGUMENTS, ZOK};
use zk_proto::{LogSite, State};

use super::entry::with_handle;
use super::handle::{require_driver, step_out};
use super::outbox::with_outbox;
use super::raw;
use super::types::{zkrs_handle, zkrs_outbox, zkrs_sockaddr_view, zkrs_step};

/// Queries the current session connection state (`zoo_state`), returning legacy integer constants
/// (such as `0` for closed, `5` for read-only, `7` for TLS connecting, `999` for not connected,
/// `-112` for expired, and `-113` for auth failure).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_state(h: *const zkrs_handle) -> i32 {
    with_handle(h, |i, _| i.driver().map_or(0, |d| d.state()))
}

/// Retrieves the effective session timeout duration in milliseconds (`zoo_recv_timeout`),
/// reflecting either the cluster-negotiated timeout or the client's initial requested timeout prior
/// to handshake completion.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_recv_timeout(h: *const zkrs_handle) -> i32 {
    with_handle(h, |i, _| i.driver().map_or(0, |d| d.recv_timeout()))
}

/// Updates the active ZooKeeper ensemble host list dynamically (`zoo_set_servers`), writing the
/// legacy return code to `*rc`.
///
/// If `hosts` is null or empty, the operation aborts with `ZBADARGUMENTS` and logs a
/// `SetServersEmpty` diagnostic event. In accordance with the specification for empty server list
/// rejection, an empty string leaves the existing ensemble configuration and active connection
/// undisturbed (diverging from legacy C behavior which accepted empty strings and stranded
/// handles). When DNS resolution of new hosts requires blocking `getaddrinfo` invocations, the
/// function suspends at a log-flush barrier and returns `ZKRS_MORE`.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_set_servers(
    h: *const zkrs_handle,
    hosts: *const c_char,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| {
        // Diverge from legacy C behavior that allowed empty strings and stranded handles by
        // treating empty input identically to null.
        let hosts = raw::cstr_opt(hosts).filter(|l| !l.is_empty());
        let host = i.host();
        let mut code: i32 = ZOK;
        let step = with_outbox(ob, i, |o| match hosts {
            // Validate null or empty ensemble specifications prior to driver submission, preserving
            // legacy error precedence.
            None => {
                if let Some(rec) = log_record(&**host, LogSite::SetServersEmpty, Vec::new()) {
                    o.push(Item::Log(rec));
                }
                Step::Done(ZBADARGUMENTS)
            }
            Some(list) => require_driver(i).set_servers(&list, o, &mut code),
        });
        step_out(step, rc)
    })
}

/// Configures the minimum delay interval in milliseconds before initiating re-resolution of
/// ensemble hostnames.
///
/// Dispatches to `Connection::set_resolve_delay` via the driver's connection handle. Delay values
/// strictly less than -1 are rejected with `ZBADARGUMENTS` alongside a `ResolveDelayInvalid` log
/// entry, which is routed either through the multithreaded I/O worker thread or during the
/// subsequent single threaded event loop iteration.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_set_resolution_delay(h: *const zkrs_handle, delay_ms: i32) -> i32 {
    with_handle(h, |i, _| {
        // If the session has already been closed and the connection dismantled, perform parameter
        // validation only.
        let fallback = if delay_ms < -1 { ZBADARGUMENTS } else { ZOK };
        i.driver()
            .and_then(|d| d.with_conn(|c, _| c.set_resolve_delay(delay_ms)))
            .unwrap_or(fallback)
    })
}

/// Forces the client connection to cycle immediately to the next configured server in the
/// ensemble.
///
/// Invokes `Connection::cycle_next_server` within the driver connection context. Reconfiguration
/// log entries are emitted asynchronously by the multithreaded I/O thread upon wakeup, or
/// processed during the next single threaded driver execution step.
#[allow(
    unused_variables,
    reason = "ob is for the reconfig log lines; see the open issue"
)]
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_cycle_next_server(h: *const zkrs_handle, ob: *mut zkrs_outbox) {
    with_handle(h, |i, _| {
        if let Some(d) = i.driver() {
            let _ = d.with_conn(|c, _| c.cycle_next_server());
        }
    })
}

/// Retrieves the network socket address of the currently connected or targeted server,
/// writing an address view with family 0 if no server is currently selected.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_current_server(h: *const zkrs_handle, out: *mut zkrs_sockaddr_view) {
    with_handle(h, |i, _| {
        let cur = i
            .driver()
            .and_then(|d| d.read_conn(|c| c.current_server()))
            .flatten();
        raw::write_out(out, zkrs_sockaddr_view::from_addr(cur));
    })
}

/// Queries the remote peer socket address of an active connection.
///
/// Writes the active peer address into `*out` and returns 0 only when the session is currently in
/// the `CONNECTED` state (3) and the underlying socket `getpeername` succeeds; otherwise writes
/// nothing and returns -1.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_peer_addr(h: *const zkrs_handle, out: *mut zkrs_sockaddr_view) -> i32 {
    with_handle(h, |i, _| {
        let peer = i
            .driver()
            .filter(|d| d.state() == State::Connected.code())
            .and_then(|d| d.peer_addr());
        match peer {
            Some(a) => {
                raw::write_out(out, zkrs_sockaddr_view::from_addr(Some(a)));
                0
            }
            None => -1,
        }
    })
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use super::super::handle::test_support::{empty_outbox, init_args};
    use super::super::handle::{zkrs_handle_new, zkrs_handle_release, zkrs_resume};
    use super::super::outbox::{zkrs_outbox_finish, zkrs_outbox_next};
    use super::super::standin;
    use super::super::types::{
        ZKRS_AF_INET, ZKRS_FLAVOR_MT, ZKRS_FLAVOR_ST, zkrs_init_args, zkrs_item, zkrs_item_type,
    };
    use super::*;

    /// Test port allocated for simulated server endpoints where no real listener binds.
    const PORT: u16 = 22650;

    /// Exhausts all pending items from the outbox, finalizes the drain cycle, and collects item
    /// type discriminators.
    fn drain(ob: &mut zkrs_outbox) -> Vec<u32> {
        let mut types = Vec::new();
        let mut it = zkrs_item::empty();
        while zkrs_outbox_next(ob, &raw mut it) == 1 {
            types.push(it.r#type);
        }
        zkrs_outbox_finish(ob);
        types
    }

    /// Iteratively drains outbox items and resumes driver execution until stepping leaves the
    /// `ZKRS_MORE` state.
    fn drive(
        h: *const zkrs_handle,
        ob: &mut zkrs_outbox,
        mut step: zkrs_step,
        rc: &mut c_int,
    ) -> (zkrs_step, Vec<u32>) {
        let mut types = drain(ob);
        while step == zkrs_step::ZKRS_MORE {
            step = zkrs_resume(h, ob, rc);
            types.extend(drain(ob));
        }
        (step, types)
    }

    fn resolve_to(port: u16) {
        let a: SocketAddr = ([127, 0, 0, 1], port).into();
        standin::set_resolve(vec![zkrs_sockaddr_view::from_addr(Some(a))], vec![], 0, 0);
    }

    /// Instantiates a fully initialized multithreaded test handle using the synthetic test vtable
    /// without background threads.
    fn ready_handle() -> *const zkrs_handle {
        ready_flavor(ZKRS_FLAVOR_MT)
    }

    /// Instantiates a test handle configured with the specified client flavor and runs
    /// initialization through completion.
    fn ready_flavor(flavor: u32) -> *const zkrs_handle {
        resolve_to(PORT);
        let a = zkrs_init_args {
            flavor,
            recv_timeout: 10_000,
            ..init_args(c"127.0.0.1:22650")
        };
        let vt = standin::vtable();
        let mut out: *mut zkrs_handle = std::ptr::null_mut();
        let mut ob = empty_outbox();
        let mut rc: c_int = -1;
        let step = zkrs_handle_new(
            &raw const a,
            &raw const vt,
            0x55,
            &raw mut out,
            &raw mut ob,
            &raw mut rc,
        );
        let (step, _) = drive(out, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, 0));
        assert!(!out.is_null());
        out
    }

    #[test]
    fn a_ready_handle_reports_state_timeout_and_endpoints() {
        let _rec = crate::abi::standin::recorder();
        let h = ready_handle();
        let st = zkrs_state(h);
        assert!(st == 999 || st == 1, "state {st}");
        assert_eq!(zkrs_recv_timeout(h), 10_000);
        let mut v = zkrs_sockaddr_view::none();
        zkrs_current_server(h, &raw mut v);
        // In the absence of an active I/O worker thread, connection selection has not occurred; the
        // endpoint view is either unpopulated or identifies the single configured target server.
        assert!(
            v.family == 0 || (v.family, v.port) == (ZKRS_AF_INET, PORT),
            "{v:?}"
        );
        let mut p = zkrs_sockaddr_view::none();
        assert_eq!(zkrs_peer_addr(h, &raw mut p), -1);
        assert_eq!(p.family, 0);
        zkrs_handle_release(h);
    }

    #[test]
    fn resolution_delay_and_cycle_follow_the_legacy_rules() {
        let _rec = crate::abi::standin::recorder();
        let h = ready_handle();
        assert_eq!(zkrs_set_resolution_delay(h, -2), ZBADARGUMENTS);
        assert_eq!(zkrs_set_resolution_delay(h, -1), ZOK);
        assert_eq!(zkrs_set_resolution_delay(h, 0), ZOK);
        let mut ob = empty_outbox();
        zkrs_cycle_next_server(h, &raw mut ob);
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    /// Verifies that null and empty server lists yield `ZBADARGUMENTS` alongside a structured log
    /// event.
    #[test]
    fn set_servers_checks_null_and_bad_lists() {
        let _rec = crate::abi::standin::recorder();
        let h = ready_handle();
        let mut ob = empty_outbox();
        let mut rc: c_int = 0;

        let step = zkrs_set_servers(h, std::ptr::null(), &raw mut ob, &raw mut rc);
        let (step, types) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZBADARGUMENTS));
        assert_eq!(types, vec![zkrs_item_type::ZKRS_ITEM_LOG as u32]);

        // Confirm that an empty host list leaves the existing connection state and ensemble
        // configuration unchanged.
        let before = zkrs_state(h);
        let step = zkrs_set_servers(h, c"".as_ptr(), &raw mut ob, &raw mut rc);
        let (step, types) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZBADARGUMENTS));
        assert_eq!(types, vec![zkrs_item_type::ZKRS_ITEM_LOG as u32]);
        assert_eq!(zkrs_state(h), before);

        let step = zkrs_set_servers(h, c"127.0.0.1".as_ptr(), &raw mut ob, &raw mut rc);
        let (step, _) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZBADARGUMENTS));

        resolve_to(22651);
        let step = zkrs_set_servers(h, c"127.0.0.1:22651".as_ptr(), &raw mut ob, &raw mut rc);
        let (step, _) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZOK));
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    #[test]
    fn an_st_handle_reports_state_timeout_and_endpoints() {
        let _rec = crate::abi::standin::recorder();
        let h = ready_flavor(ZKRS_FLAVOR_ST);
        // Remains in ZOO_NOTCONNECTED_STATE until the initial `zookeeper_interest` evaluation.
        assert_eq!(zkrs_state(h), 999);
        assert_eq!(zkrs_recv_timeout(h), 10_000);
        let mut p = zkrs_sockaddr_view::none();
        assert_eq!(zkrs_peer_addr(h, &raw mut p), -1);
        assert_eq!(p.family, 0);
        assert_eq!(zkrs_set_resolution_delay(h, -2), ZBADARGUMENTS);
        assert_eq!(zkrs_set_resolution_delay(h, 0), ZOK);
        zkrs_handle_release(h);
    }

    #[test]
    fn st_set_servers_checks_the_list_and_resumes_its_stage() {
        let _rec = crate::abi::standin::recorder();
        let h = ready_flavor(ZKRS_FLAVOR_ST);
        let mut ob = empty_outbox();
        let mut rc: c_int = 0;

        let step = zkrs_set_servers(h, std::ptr::null(), &raw mut ob, &raw mut rc);
        let (step, types) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZBADARGUMENTS));
        assert_eq!(types, vec![zkrs_item_type::ZKRS_ITEM_LOG as u32]);

        // Validate that execution barriers encountered during ensemble updates resume properly via
        // `zkrs_resume`.
        resolve_to(22651);
        let step = zkrs_set_servers(h, c"127.0.0.1:22651".as_ptr(), &raw mut ob, &raw mut rc);
        let (step, _) = drive(h, &mut ob, step, &mut rc);
        assert_eq!((step, rc), (zkrs_step::ZKRS_DONE, ZOK));
        assert!(ob.r#impl.is_null());
        assert_eq!(zkrs_state(h), 999);
        zkrs_handle_release(h);
    }
}
