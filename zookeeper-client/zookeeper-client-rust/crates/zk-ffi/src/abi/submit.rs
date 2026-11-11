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

//! Request submission funnel translating C API requests into driver operations.
//!
//! Exposes [`zkrs_submit`], which converts incoming [`zkrs_request`] descriptions into typed
//! [`zk_proto::Request`] commands and dispatches them to the underlying driver runtime.
//! Submissions are nonblocking and never invoke user completion callbacks synchronously on
//! the calling thread. Log messages generated during submission are staged into the caller's
//! outbox. Return statuses (`zkrs_status`) convey validation and pipeline execution stages
//! to C callers, allowing the legacy shim to reproduce historical return codes accurately.
//!
//! Input data payloads and transaction arrays are borrowed directly from FFI parameter records
//! and serialized into protocol packet frames without intermediate heap duplication. ACL vectors
//! are mirrored from C `struct ACL_vector` definitions and copied into owned representations.
//!
//! Buffer and structural defects (such as invalid data buffer lengths, null transaction arrays,
//! or malformed ACL lists) are flagged as [`InputDefect`] properties. These are evaluated during
//! protocol serialization to guarantee deterministic, spec-compliant error ordering.

use std::ffi::c_char;
use std::sync::Arc;

use zk_jute::data::{ACL, Id};
use zk_jute::{JBuffer, JString, JVec, rc};
use zk_proto::{
    InputDefect, Op, PathIn, Request, Route, SecretBytes, SubmitError, Submitted, check_ttl,
};

use zk_driver::Outbox;

use super::entry::{abort_with, ffi_guard, with_handle};
use super::handle::{Driver, Inner, require_driver};
use super::legacy::{CAcl, CAclVector, CZooOp};
use super::multi::multi_ops;
use super::outbox::with_outbox;
use super::raw;
use super::tokens::{CReq, CTokens, CWatcher};
use super::types::{
    ZKRS_AUTH_F_ALLOC_FAILED, ZKRS_DELIVER_DIRECT, ZKRS_F_CREATE2, ZKRS_F_LOCAL, ZKRS_F_REMOVE_ALL,
    ZKRS_K_ACLLIST, ZKRS_K_DATA, ZKRS_K_MULTI, ZKRS_K_STAT, ZKRS_K_STRING, ZKRS_K_STRING_STAT,
    ZKRS_K_STRINGLIST, ZKRS_K_STRINGLIST_STAT, ZKRS_K_VOID, ZKRS_OP_CREATE, ZKRS_OP_DELETE,
    ZKRS_OP_EXISTS, ZKRS_OP_GETACL, ZKRS_OP_GETCHILDREN, ZKRS_OP_GETCHILDREN2, ZKRS_OP_GETCONFIG,
    ZKRS_OP_GETDATA, ZKRS_OP_MULTI, ZKRS_OP_RECONFIG, ZKRS_OP_REMOVE_WATCHES, ZKRS_OP_SETACL,
    ZKRS_OP_SETDATA, ZKRS_OP_SYNC, zkrs_handle, zkrs_outbox, zkrs_request, zkrs_status,
};

/// Determines the completion kind discriminant (`ZKRS_K_*`) corresponding to an operation code and
/// flags.
///
/// Mirrors legacy `zoo_a*` completion dispatch logic. The resolved discriminant is embedded within
/// the request's [`CReq`] token to govern result deserialization upon completion.
pub(crate) fn kind_of(op: u32, flags: u32) -> u32 {
    match op {
        ZKRS_OP_CREATE if flags & ZKRS_F_CREATE2 != 0 => ZKRS_K_STRING_STAT,
        ZKRS_OP_CREATE | ZKRS_OP_SYNC => ZKRS_K_STRING,
        ZKRS_OP_EXISTS | ZKRS_OP_SETDATA => ZKRS_K_STAT,
        ZKRS_OP_GETDATA | ZKRS_OP_GETCONFIG | ZKRS_OP_RECONFIG => ZKRS_K_DATA,
        ZKRS_OP_GETACL => ZKRS_K_ACLLIST,
        ZKRS_OP_GETCHILDREN => ZKRS_K_STRINGLIST,
        ZKRS_OP_GETCHILDREN2 => ZKRS_K_STRINGLIST_STAT,
        ZKRS_OP_MULTI => ZKRS_K_MULTI,
        // DELETE, SETACL, REMOVE_WATCHES, and unrecognized operation codes produce void
        // completions.
        _ => ZKRS_K_VOID,
    }
}

/// Constructs a typed [`zk_proto::Request`] from an incoming FFI request record.
///
/// Deep-copies path strings (using legacy C path semantics), ACL vectors, reconfiguration strings,
/// and watcher specifications. Operation data payloads (for create and setData) and transaction
/// sub-operations (for multi) are borrowed directly during encoding to avoid intermediate copies.
pub(crate) fn build_request(
    h: &Arc<Inner>,
    rq: &zkrs_request,
    token: usize,
    delivery: u32,
) -> Request<CTokens> {
    // ACL vector inspection is restricted to create and setAcl operations. The vector is validated
    // and converted once; defect flags are recorded for deferred reporting during packet
    // serialization.
    let (acl, bad_acl) = match rq.op {
        ZKRS_OP_CREATE => acl_of_vec(rq.acl.cast(), false),
        ZKRS_OP_SETACL => acl_of_vec(rq.acl.cast(), true),
        _ => (JVec::Items(Vec::new()), false),
    };
    let defect = defects_of(rq, token, bad_acl);
    let token = CReq::new(token, kind_of(rq.op, rq.flags), h.host().discard_fn());
    let route = if delivery == ZKRS_DELIVER_DIRECT {
        Route::Direct
    } else {
        Route::Queued
    };
    let op = op_of(rq, acl);
    Request {
        op,
        token,
        route,
        defect,
    }
}

/// Constructs an operation enum variant from an incoming request descriptor and parsed ACLs.
fn op_of(rq: &zkrs_request, acl: JVec<ACL>) -> Op<CWatcher> {
    let watch = CWatcher::from_request(rq.watch_fn, rq.watch_ctx);
    match rq.op {
        ZKRS_OP_CREATE => Op::Create {
            path: path_of(rq.path),
            data: JBuffer::Null,
            acl,
            mode: rq.mode,
            ttl: rq.ttl,
            create2: rq.flags & ZKRS_F_CREATE2 != 0,
        },
        ZKRS_OP_DELETE => Op::Delete {
            path: path_of(rq.path),
            version: rq.version,
        },
        ZKRS_OP_EXISTS => Op::Exists {
            path: path_of(rq.path),
            watch,
        },
        ZKRS_OP_GETDATA => Op::GetData {
            path: path_of(rq.path),
            watch,
        },
        // GetConfig requests carry no path; configuration queries target the cluster configuration
        // node directly.
        ZKRS_OP_GETCONFIG => Op::GetConfig { watch },
        ZKRS_OP_SETDATA => Op::SetData {
            path: path_of(rq.path),
            data: JBuffer::Null,
            version: rq.version,
        },
        ZKRS_OP_GETACL => Op::GetAcl {
            path: path_of(rq.path),
        },
        ZKRS_OP_SETACL => Op::SetAcl {
            path: path_of(rq.path),
            acl,
            version: rq.version,
        },
        ZKRS_OP_GETCHILDREN | ZKRS_OP_GETCHILDREN2 => Op::GetChildren {
            path: path_of(rq.path),
            watch,
            with_stat: rq.op == ZKRS_OP_GETCHILDREN2,
        },
        ZKRS_OP_SYNC => Op::Sync {
            path: path_of(rq.path),
        },
        ZKRS_OP_RECONFIG => Op::Reconfig {
            joining: JString::from(raw::cstr_opt(rq.joining)),
            leaving: JString::from(raw::cstr_opt(rq.leaving)),
            members: JString::from(raw::cstr_opt(rq.members)),
            version: rq.reconfig_version,
        },
        // Multi-operation transactions borrow sub-operation descriptors during submission dispatch.
        ZKRS_OP_MULTI => Op::Multi { ops: Vec::new() },
        ZKRS_OP_REMOVE_WATCHES => Op::RemoveWatches {
            path: path_of(rq.path),
            wtype: rq.wtype,
            watcher: watch,
            local: rq.flags & ZKRS_F_LOCAL != 0,
            all: rq.flags & ZKRS_F_REMOVE_ALL != 0,
        },
        _ => abort_with("zk-ffi: unknown zkrs_request operation"),
    }
}

/// Reconstructs an optional path from a raw C string pointer, yielding `PathIn::Legacy(None)` if
/// null.
pub(crate) fn path_of(p: *const c_char) -> PathIn {
    PathIn::Legacy(raw::cstr_opt(p))
}

/// Borrows a byte slice reference to the payload data buffer for create or setData operations.
///
/// Returns `None` if the operation does not accept data payloads or if buffer pointers are invalid.
pub(crate) fn data_of(rq: &zkrs_request) -> Option<&[u8]> {
    if !matches!(rq.op, ZKRS_OP_CREATE | ZKRS_OP_SETDATA) {
        return None;
    }
    raw::value_ref(rq.data, rq.datalen).ok().flatten()
}

/// Dispatches a constructed protocol request to the driver alongside borrowed input buffers.
///
/// Multi-operation transactions borrow sub-operation arrays, while data mutations borrow value
/// buffers. Both inputs are borrowed exclusively for the duration of packet encoding within this
/// function.
fn submit_borrowed(
    d: &Driver,
    req: Request<CTokens>,
    rq: &zkrs_request,
    ob: &mut Outbox<CTokens>,
) -> Result<Submitted<CTokens>, SubmitError<CTokens>> {
    if rq.op == ZKRS_OP_MULTI {
        d.submit_multi_ref(req, &multi_ops(rq), ob)
    } else {
        d.submit_with_data(req, data_of(rq), ob)
    }
}

/// Reconstructs a strongly typed [`JVec<ACL>`] from a raw legacy `struct ACL_vector` pointer.
///
/// If `v` is null, returns an empty vector (which is flagged as a defect only when `null_is_bad` is
/// set, such as in setAcl operations). Negative vector counts are converted to null vectors.
pub(crate) fn acl_of_vec(v: *const CAclVector, null_is_bad: bool) -> (JVec<ACL>, bool) {
    if v.is_null() {
        return (JVec::Items(Vec::new()), null_is_bad);
    }
    let v = raw::read_struct(v);
    if v.count < 0 {
        return (JVec::Null, false);
    }
    match raw::array(v.data.cast_const(), v.count) {
        Ok(a) => (JVec::Items(a.iter().map(acl_entry).collect()), false),
        Err(_) => (JVec::Items(Vec::new()), true),
    }
}

/// Converts a legacy [`CAcl`] C mirror struct into a typed protocol [`ACL`] record.
fn acl_entry(e: &CAcl) -> ACL {
    ACL {
        perms: e.perms,
        id: Id {
            scheme: JString::from(raw::cstr_opt(e.id.scheme)),
            id: JString::from(raw::cstr_opt(e.id.id)),
        },
    }
}

/// Evaluates whether a raw byte buffer descriptor violates memory validity constraints.
fn value_defect(p: *const c_char, len: i32) -> bool {
    if len < -1 {
        return true;
    }
    match usize::try_from(len) {
        Ok(n) if n > 0 => raw::bytes(p.cast::<u8>(), n).is_err(),
        _ => false,
    }
}

/// Maps a protocol return code to its corresponding FFI pipeline failure status (`zkrs_status`).
///
/// Unrecognized or generic codes map to `ZKRS_LATE` (serialization/dispatch stage failure).
fn status_of_code(code: i32) -> zkrs_status {
    match code {
        rc::ZBADARGUMENTS => zkrs_status::ZKRS_EARLY_BADARGS,
        rc::ZINVALIDSTATE => zkrs_status::ZKRS_EARLY_STATE,
        rc::ZUNIMPLEMENTED => zkrs_status::ZKRS_UNIMPLEMENTED,
        rc::ZNOWATCHER => zkrs_status::ZKRS_NOWATCHER,
        rc::ZSYSTEMERROR => zkrs_status::ZKRS_SYSTEM,
        _ => zkrs_status::ZKRS_LATE,
    }
}

/// Maps a submission failure error into an FFI status, safely transferring request ownership back
/// to C.
///
/// To prevent double-free bugs, the completion token is extracted via `into_raw()` without
/// dropping, as C retains ownership of the request memory upon receiving a non-OK status.
pub(crate) fn status_of(e: SubmitError<CTokens>) -> zkrs_status {
    let SubmitError { code, token, .. } = e;
    // Ownership of the request structure remains with C; avoid running token drop destructors.
    let _ = token.into_raw();
    status_of_code(code)
}

/// Evaluates and combines input defect flags across request parameters, buffers, and tokens.
pub(crate) fn defects_of(rq: &zkrs_request, token: usize, bad_acl: bool) -> InputDefect {
    let reads_value = matches!(rq.op, ZKRS_OP_CREATE | ZKRS_OP_SETDATA);
    // Multi-operations requiring non-zero lengths with null array pointers violate API
    // preconditions.
    let ops_missing =
        rq.op == ZKRS_OP_MULTI && raw::array_len(rq.ops.cast::<CZooOp>(), rq.ops_len).is_err();
    InputDefect {
        bad_value_buffer: reads_value && value_defect(rq.data, rq.datalen),
        bad_acl,
        marshal_oom: token == 0 || ops_missing,
    }
}

/// Executes early validation checks for authentication requests prior to acquiring driver locks.
///
/// Validates session lifecycle state and credential buffer descriptors according to legacy C
/// semantics.
fn auth_early(state: i32, cert: *const c_char, cert_len: i32) -> Result<(), zkrs_status> {
    if state <= 0 {
        return Err(zkrs_status::ZKRS_EARLY_STATE);
    }
    if !cert.is_null() && cert_len < 0 {
        return Err(zkrs_status::ZKRS_SYSTEM);
    }
    Ok(())
}

/// Securely copies authentication credential bytes into an isolated [`SecretBytes`] container.
///
/// Allocates exact buffer capacity to prevent residual secret fragments in uninitialized heap
/// memory.
fn auth_credential(cert: *const c_char, cert_len: i32) -> Result<SecretBytes, zkrs_status> {
    let n = match usize::try_from(cert_len) {
        Ok(n) if n > 0 && !cert.is_null() => n,
        Ok(_) => return Ok(SecretBytes::empty()),
        Err(_) if cert.is_null() => return Ok(SecretBytes::empty()),
        Err(_) => return Err(zkrs_status::ZKRS_SYSTEM),
    };
    let src = raw::bytes(cert.cast::<u8>(), n).map_err(|_| zkrs_status::ZKRS_SYSTEM)?;
    let mut v = Vec::new();
    v.try_reserve_exact(n)
        .map_err(|_| zkrs_status::ZKRS_SYSTEM)?;
    v.extend_from_slice(src);
    Ok(SecretBytes::new(v))
}

/// Submits an asynchronous ZooKeeper operation to the runtime driver.
///
/// On `ZKRS_OK`, ownership of the token is transferred to the driver for exactly-once completion
/// delivery. For local-only watch removals (`ZKRS_OK_LOCAL`), the token is returned to C for
/// immediate completion. On any failure status, token ownership is relinquished back to C for
/// deallocation.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_submit(
    h: *const zkrs_handle,
    rq: *const zkrs_request,
    token: usize,
    delivery: u32,
    ob: *mut zkrs_outbox,
) -> zkrs_status {
    with_handle(h, |i, _| {
        let d = require_driver(i);
        let rq: zkrs_request = raw::read_struct(rq);
        let req = build_request(i, &rq, token, delivery);
        with_outbox(ob, i, |o| match submit_borrowed(d, req, &rq, o) {
            Ok(Submitted::Queued { .. }) => zkrs_status::ZKRS_OK,
            // Local watch removal completions are handled directly in C; return token without
            // driver retention.
            Ok(Submitted::Local(t)) => {
                let _ = t.into_raw();
                zkrs_status::ZKRS_OK_LOCAL
            }
            Err(e) => status_of(e),
        })
    })
}

/// Registers an authentication identity with the ZooKeeper session (`zoo_add_auth`).
///
/// Performs state validation, copies credential secrets into protected memory, and dispatches the
/// authentication command to the session driver with optional asynchronous completion tracking.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_add_auth(
    h: *const zkrs_handle,
    scheme: *const c_char,
    cert: *const c_char,
    cert_len: i32,
    token: usize,
    flags: u32,
    ob: *mut zkrs_outbox,
) -> zkrs_status {
    with_handle(h, |i, _| {
        let d = require_driver(i);
        let Some(scheme) = raw::cstr_opt(scheme) else {
            abort_with("zk-ffi: zkrs_add_auth with a NULL scheme");
        };
        // Re-verify session state under driver lock to guard against concurrent disconnection.
        if let Err(st) = auth_early(d.state(), cert, cert_len) {
            return st;
        }
        let cred = match auth_credential(cert, cert_len) {
            Ok(c) => c,
            Err(st) => return st,
        };
        // The caller encountered an allocation failure when staging the completion descriptor.
        if flags & ZKRS_AUTH_F_ALLOC_FAILED != 0 {
            return zkrs_status::ZKRS_SYSTEM;
        }
        let done = (token != 0).then(|| CReq::new(token, ZKRS_K_VOID, i.host().discard_fn()));
        with_outbox(ob, i, |_| {
            match d.add_auth(JString::Bytes(scheme), cred, done) {
                Ok(()) => zkrs_status::ZKRS_OK,
                Err(e) => {
                    // C deallocates completion records on non-OK statuses; extract token without
                    // destruction.
                    let _ = e.done.map(CReq::into_raw);
                    status_of_code(e.code)
                }
            }
        })
    })
}

/// Performs lightweight preliminary validation checks for `zoo_add_auth` without acquiring driver
/// locks.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_add_auth_check(
    h: *const zkrs_handle,
    cert: *const c_char,
    cert_len: i32,
) -> zkrs_status {
    with_handle(h, |i, _| {
        let d = require_driver(i);
        match auth_early(d.state(), cert, cert_len) {
            Ok(()) => zkrs_status::ZKRS_OK,
            Err(st) => st,
        }
    })
}

/// Validates time-to-live (TTL) node mode and expiration bounds independently of session state.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_validate_ttl(mode: i32, ttl: i64) -> zkrs_status {
    ffi_guard(|| match check_ttl(mode, ttl) {
        Ok(()) => zkrs_status::ZKRS_OK,
        Err(_) => zkrs_status::ZKRS_EARLY_BADARGS,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::handle::test_support::{empty_outbox, new_handle};
    use crate::abi::handle::zkrs_handle_release;
    use crate::abi::host::FfiHost;
    use crate::abi::legacy::CId;
    use crate::abi::multi::test_support::{TestOp, ops_ptr};
    use crate::abi::standin;
    use crate::abi::types::{
        ZKRS_DELIVER_QUEUED, ZKRS_F_RAW_PATH, ZKRS_FLAVOR_MT, ZKRS_FLAVOR_ST, zkrs_item,
    };
    use zk_driver::outbox::Flavor;
    use zk_driver::{
        Env, HostLeaves, Init, InitArgs, InitStep, Outbox, Polled, Shared, StdHostLeaves,
    };
    use zk_proto::{Compat, Config, DriverMode, MultiOpRef, Stage};

    /// Constructs a skeleton request populated with legacy default values.
    fn req(op: u32) -> zkrs_request {
        zkrs_request {
            op,
            flags: 0,
            path: std::ptr::null(),
            data: std::ptr::null(),
            datalen: -1,
            acl: std::ptr::null(),
            mode: 0,
            ttl: -1,
            version: -1,
            reconfig_version: -1,
            joining: std::ptr::null(),
            leaving: std::ptr::null(),
            members: std::ptr::null(),
            watch_fn: 0,
            watch_ctx: 0,
            wtype: 0,
            ops: std::ptr::null(),
            ops_len: 0,
            count: 0,
        }
    }

    /// Helper returning a raw opaque pointer to an ACL vector struct.
    fn vec_ptr(v: &CAclVector) -> *const std::ffi::c_void {
        std::ptr::from_ref(v).cast()
    }

    /// Helper constructing a legacy ACL entry struct.
    fn c_acl(perms: i32, scheme: *const c_char, id: *const c_char) -> CAcl {
        CAcl {
            perms,
            id: CId {
                scheme: scheme.cast_mut(),
                id: id.cast_mut(),
            },
        }
    }

    /// Constructs a request instance using a temporary uninitialized handle for structural
    /// assertions.
    fn built(rq: &zkrs_request, delivery: u32) -> (Op<CWatcher>, Route, InputDefect, u32) {
        let h = new_handle();
        let r = with_handle(h, |i, _| {
            let req = build_request(i, rq, 7, delivery);
            let kind = req.token.kind();
            assert_eq!(req.token.into_raw(), 7);
            (req.op, req.route, req.defect, kind)
        });
        zkrs_handle_release(h);
        r
    }

    fn legacy(p: &std::ffi::CStr) -> PathIn {
        PathIn::Legacy(Some(p.to_bytes().to_vec()))
    }

    /// Instantiates a test handle backed by an active multithreaded runtime driver.
    fn ready_handle() -> *const zkrs_handle {
        ready_flavor(ZKRS_FLAVOR_MT)
    }

    /// Instantiates a test handle backed by a runtime driver configured for the requested execution
    /// flavor.
    #[allow(clippy::expect_used, reason = "a failed setup ends the test")]
    fn ready_flavor(flavor: u32) -> *const zkrs_handle {
        let host = Arc::new(FfiHost::new(standin::vtable(), 0x55));
        let inner = Arc::new(Inner::new(host, flavor));
        let leaves: Arc<dyn HostLeaves<CTokens>> = Arc::new(StdHostLeaves::new());
        let (mode, fl) = if flavor == ZKRS_FLAVOR_ST {
            (DriverMode::ST, Flavor::St)
        } else {
            (DriverMode::MT, Flavor::Mt)
        };
        let args = InitArgs {
            cfg: Config::legacy(10_000),
            host_string: b"127.0.0.1:22640".to_vec(),
            compat: Compat::LEGACY_C,
            mode,
            flavor: fl,
        };
        let mut ob = Outbox::new();
        let mut init = Init::new(args, Env::new(leaves), &mut ob).expect("init");
        let mut proof = zk_driver::Unlocked::new_at_entry();
        loop {
            let mut ob = Outbox::new();
            match init.step(&mut proof, &mut ob) {
                InitStep::Ready => break,
                InitStep::More => {}
                _ => return std::ptr::null(),
            }
        }
        let driver = if flavor == ZKRS_FLAVOR_ST {
            Driver::St(Arc::new(Polled::from_init(init)))
        } else {
            let mut ob = Outbox::new();
            Driver::Mt(Shared::from_init(init, &mut ob).expect("shared"))
        };
        assert!(inner.install(driver).is_ok());
        raw::handle_into_raw(inner)
    }

    /// Submits a request to handle `h` using a transient empty outbox.
    fn submit(h: *const zkrs_handle, rq: &zkrs_request, token: usize) -> zkrs_status {
        let mut ob = empty_outbox();
        let st = zkrs_submit(h, rq, token, ZKRS_DELIVER_QUEUED, &mut ob);
        assert!(ob.r#impl.is_null(), "the mt submit fills no outbox");
        st
    }

    fn add_auth(
        h: *const zkrs_handle,
        cert: *const c_char,
        len: i32,
        token: usize,
        flags: u32,
    ) -> zkrs_status {
        let mut ob = empty_outbox();
        let st = zkrs_add_auth(h, c"digest".as_ptr(), cert, len, token, flags, &mut ob);
        assert!(ob.r#impl.is_null(), "the mt add_auth fills no outbox");
        st
    }

    #[test]
    fn ttl_stage_follows_the_mode() {
        // Ephemeral and persistent TTL modes require strictly positive TTL values within 40-bit
        // limits.
        assert_eq!(zkrs_validate_ttl(0, -1), zkrs_status::ZKRS_OK);
        assert_eq!(zkrs_validate_ttl(0, 5), zkrs_status::ZKRS_EARLY_BADARGS);
        assert_eq!(zkrs_validate_ttl(5, 5), zkrs_status::ZKRS_OK);
        assert_eq!(zkrs_validate_ttl(5, 0), zkrs_status::ZKRS_EARLY_BADARGS);
    }

    #[test]
    fn kinds_follow_the_legacy_completions() {
        assert_eq!(kind_of(ZKRS_OP_CREATE, 0), ZKRS_K_STRING);
        assert_eq!(kind_of(ZKRS_OP_CREATE, ZKRS_F_CREATE2), ZKRS_K_STRING_STAT);
        assert_eq!(kind_of(ZKRS_OP_GETCHILDREN2, 0), ZKRS_K_STRINGLIST_STAT);
        assert_eq!(kind_of(ZKRS_OP_MULTI, 0), ZKRS_K_MULTI);
    }

    #[test]
    fn create_copies_every_input() {
        let _rec = standin::recorder();
        let mut acl = [c_acl(31, c"world".as_ptr(), std::ptr::null())];
        let v = CAclVector {
            count: 1,
            data: acl.as_mut_ptr(),
        };
        let mut rq = req(ZKRS_OP_CREATE);
        rq.path = c"/a".as_ptr();
        rq.data = c"xyz".as_ptr();
        rq.datalen = 2;
        rq.acl = vec_ptr(&v);
        rq.mode = 5;
        rq.ttl = 100;
        rq.flags = ZKRS_F_CREATE2;
        let (op, route, defect, kind) = built(&rq, ZKRS_DELIVER_DIRECT);
        // Operation payloads are borrowed during submission rather than duplicated in the op
        // variant.
        assert_eq!(data_of(&rq), Some(&b"xy"[..]));
        let want = Op::Create {
            path: legacy(c"/a"),
            data: JBuffer::Null,
            acl: JVec::Items(vec![ACL {
                perms: 31,
                id: Id {
                    scheme: JString::from("world"),
                    id: JString::Null,
                },
            }]),
            mode: 5,
            ttl: 100,
            create2: true,
        };
        assert_eq!(op, want);
        assert_eq!(route, Route::Direct);
        assert_eq!(defect, InputDefect::default());
        assert_eq!(kind, ZKRS_K_STRING_STAT);
        assert!(standin::discarded().is_empty());
    }

    #[test]
    fn value_buffers_follow_the_legacy_lengths() {
        let _rec = standin::recorder();
        let mut rq = req(ZKRS_OP_SETDATA);
        rq.path = c"/a".as_ptr();
        rq.version = 3;
        // Negative buffer lengths represent null buffers, whereas zero indicates empty buffers.
        let (op, _, defect, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::SetData {
            path: legacy(c"/a"),
            data: JBuffer::Null,
            version: 3,
        };
        assert_eq!(
            (op, defect, kind),
            (want.clone(), InputDefect::default(), ZKRS_K_STAT)
        );
        assert_eq!(data_of(&rq), None);
        rq.datalen = 0;
        let (op, _, _, _) = built(&rq, ZKRS_DELIVER_QUEUED);
        assert_eq!(op, want);
        assert_eq!(data_of(&rq), Some(&b""[..]));
        rq.data = c"abc".as_ptr();
        rq.datalen = 3;
        assert_eq!(data_of(&rq), Some(&b"abc"[..]));
        // Payload buffers are evaluated exclusively for create and setData operations.
        rq.op = ZKRS_OP_SETACL;
        assert_eq!(data_of(&rq), None);
        rq.op = ZKRS_OP_SETDATA;
        rq.data = std::ptr::null();
        // Null pointers accompanied by positive lengths or negative lengths below -1 represent
        // input defects.
        for len in [4, -2] {
            rq.datalen = len;
            let (op, _, defect, _) = built(&rq, ZKRS_DELIVER_QUEUED);
            assert!(defect.bad_value_buffer, "{len}");
            assert!(!defect.bad_acl && !defect.marshal_oom, "{len}");
            assert_eq!(data_of(&rq), None, "{len}");
            let want = Op::SetData {
                path: legacy(c"/a"),
                data: JBuffer::Null,
                version: 3,
            };
            assert_eq!(op, want);
        }
    }

    #[test]
    fn reads_copy_the_watcher() {
        let _rec = standin::recorder();
        let mut rq = req(ZKRS_OP_GETDATA);
        rq.path = c"/w".as_ptr();
        rq.watch_fn = 0x100;
        rq.watch_ctx = 0x200;
        let w = Some(CWatcher {
            f: 0x100,
            ctx: 0x200,
        });
        let cases = [
            (
                ZKRS_OP_GETDATA,
                Op::GetData {
                    path: legacy(c"/w"),
                    watch: w,
                },
                ZKRS_K_DATA,
            ),
            (
                ZKRS_OP_EXISTS,
                Op::Exists {
                    path: legacy(c"/w"),
                    watch: w,
                },
                ZKRS_K_STAT,
            ),
            (
                ZKRS_OP_GETCHILDREN,
                Op::GetChildren {
                    path: legacy(c"/w"),
                    watch: w,
                    with_stat: false,
                },
                ZKRS_K_STRINGLIST,
            ),
            (
                ZKRS_OP_GETCHILDREN2,
                Op::GetChildren {
                    path: legacy(c"/w"),
                    watch: w,
                    with_stat: true,
                },
                ZKRS_K_STRINGLIST_STAT,
            ),
            (ZKRS_OP_GETCONFIG, Op::GetConfig { watch: w }, ZKRS_K_DATA),
        ];
        for (code, want, want_kind) in cases {
            rq.op = code;
            if code == ZKRS_OP_GETCONFIG {
                rq.flags = ZKRS_F_RAW_PATH;
            }
            let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
            assert_eq!((op, kind), (want, want_kind), "{code}");
        }
        // A null watcher callback pointer indicates that no watcher should be registered.
        rq.op = ZKRS_OP_EXISTS;
        rq.watch_fn = 0;
        let (op, _, _, _) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::Exists {
            path: legacy(c"/w"),
            watch: None,
        };
        assert_eq!(op, want);
    }

    #[test]
    fn writes_and_admin_ops_copy_their_fields() {
        let _rec = standin::recorder();
        let mut rq = req(ZKRS_OP_DELETE);
        rq.path = c"/d".as_ptr();
        rq.version = 9;
        let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::Delete {
            path: legacy(c"/d"),
            version: 9,
        };
        assert_eq!((op, kind), (want, ZKRS_K_VOID));

        rq.op = ZKRS_OP_GETACL;
        let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::GetAcl {
            path: legacy(c"/d"),
        };
        assert_eq!((op, kind), (want, ZKRS_K_ACLLIST));

        // Negative counts are interpreted as null ACL vectors in legacy Jute serialization.
        let neg = CAclVector {
            count: -1,
            data: std::ptr::null_mut(),
        };
        rq.op = ZKRS_OP_SETACL;
        rq.acl = vec_ptr(&neg);
        let (op, _, defect, _) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::SetAcl {
            path: legacy(c"/d"),
            acl: JVec::Null,
            version: 9,
        };
        assert_eq!((op, defect), (want, InputDefect::default()));
        rq.acl = std::ptr::null();

        rq.op = ZKRS_OP_SYNC;
        let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::Sync {
            path: legacy(c"/d"),
        };
        assert_eq!((op, kind), (want, ZKRS_K_STRING));

        let mut rq = req(ZKRS_OP_RECONFIG);
        rq.joining = c"server.1=a:1:2".as_ptr();
        rq.members = c"".as_ptr();
        rq.reconfig_version = 0x1_0000_0001;
        let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::Reconfig {
            joining: JString::from("server.1=a:1:2"),
            leaving: JString::Null,
            members: JString::from(""),
            version: 0x1_0000_0001,
        };
        assert_eq!((op, kind), (want, ZKRS_K_DATA));
    }

    #[test]
    fn remove_watches_and_multi_copy_their_flags() {
        let _rec = standin::recorder();
        let mut rq = req(ZKRS_OP_REMOVE_WATCHES);
        rq.path = c"/r".as_ptr();
        rq.wtype = 2;
        rq.flags = ZKRS_F_LOCAL | ZKRS_F_REMOVE_ALL;
        let (op, _, _, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        let want = Op::RemoveWatches {
            path: legacy(c"/r"),
            wtype: 2,
            watcher: None,
            local: true,
            all: true,
        };
        assert_eq!((op, kind), (want, ZKRS_K_VOID));

        let ops = [TestOp::check(c"/m", 4)];
        let mut rq = req(ZKRS_OP_MULTI);
        rq.ops = ops_ptr(&ops);
        rq.ops_len = 1;
        rq.count = 1;
        let (op, _, defect, kind) = built(&rq, ZKRS_DELIVER_QUEUED);
        // Multi operations do not store sub-op vectors; descriptors are borrowed from the FFI
        // request.
        let want = Op::Multi { ops: Vec::new() };
        assert_eq!(
            (op, defect, kind),
            (want, InputDefect::default(), ZKRS_K_MULTI)
        );
        let sub = [MultiOpRef::Check {
            path: Some(b"/m".as_slice()),
            version: 4,
            defect: InputDefect::default(),
        }];
        assert_eq!(multi_ops(&rq), sub);
    }

    #[test]
    fn acl_vectors_follow_the_legacy_rules() {
        let empty = (JVec::Items(Vec::new()), false);
        // A null vector is treated as empty; only setAcl operations consider a null vector
        // defective.
        assert_eq!(acl_of_vec(std::ptr::null(), false), empty);
        assert_eq!(
            acl_of_vec(std::ptr::null(), true),
            (JVec::Items(Vec::new()), true)
        );
        // Negative counts denote a Jute null vector, while zero denotes an empty vector.
        let mut v = CAclVector {
            count: -1,
            data: std::ptr::null_mut(),
        };
        assert_eq!(acl_of_vec(&v, true), (JVec::Null, false));
        v.count = 0;
        assert_eq!(acl_of_vec(&v, true), empty);
        // A null data pointer accompanied by a positive element count is flagged as a defect.
        v.count = 2;
        assert_eq!(acl_of_vec(&v, false), (JVec::Items(Vec::new()), true));

        // Validates preservation of element ordering and null-string handling across 20 entries.
        let null = std::ptr::null();
        let mut many: Vec<CAcl> = (0..20)
            .map(|i| {
                let scheme = if i == 3 { null } else { c"digest".as_ptr() };
                let id = if i == 4 { null } else { c"u:p".as_ptr() };
                c_acl(i, scheme, id)
            })
            .collect();
        let v = CAclVector {
            count: 20,
            data: many.as_mut_ptr(),
        };
        let want: Vec<ACL> = (0..20)
            .map(|i| ACL {
                perms: i,
                id: Id {
                    scheme: if i == 3 {
                        JString::Null
                    } else {
                        JString::from("digest")
                    },
                    id: if i == 4 {
                        JString::Null
                    } else {
                        JString::from("u:p")
                    },
                },
            })
            .collect();
        assert_eq!(acl_of_vec(&v, true), (JVec::Items(want), false));
    }

    #[test]
    fn only_create_and_set_acl_read_the_acl_vector() {
        let _rec = standin::recorder();
        let bad = CAclVector {
            count: 2,
            data: std::ptr::null_mut(),
        };
        for (code, null_bad) in [(ZKRS_OP_CREATE, false), (ZKRS_OP_SETACL, true)] {
            let mut rq = req(code);
            rq.path = c"/a".as_ptr();
            let (_, _, defect, _) = built(&rq, ZKRS_DELIVER_QUEUED);
            assert_eq!(defect.bad_acl, null_bad, "{code}");
            rq.acl = vec_ptr(&bad);
            let (_, _, defect, _) = built(&rq, ZKRS_DELIVER_QUEUED);
            assert!(defect.bad_acl, "{code}");
        }
        // Non-ACL operations ignore supplied ACL vector pointers entirely.
        let mut rq = req(ZKRS_OP_GETACL);
        rq.path = c"/a".as_ptr();
        rq.acl = vec_ptr(&bad);
        let (_, _, defect, _) = built(&rq, ZKRS_DELIVER_QUEUED);
        assert_eq!(defect, InputDefect::default());
    }

    #[test]
    fn defects_come_from_the_buffers_the_acl_and_the_token() {
        let rq = req(ZKRS_OP_CREATE);
        assert_eq!(defects_of(&rq, 1, false), InputDefect::default());
        assert!(defects_of(&rq, 0, false).marshal_oom);
        assert!(defects_of(&rq, 1, true).bad_acl);

        // Malformed data buffer lengths are only evaluated for create and setData operations.
        let mut v = req(ZKRS_OP_CREATE);
        v.datalen = 3;
        assert!(defects_of(&v, 1, false).bad_value_buffer);
        v.datalen = -5;
        assert!(defects_of(&v, 1, false).bad_value_buffer);
        v.op = ZKRS_OP_DELETE;
        assert!(!defects_of(&v, 1, false).bad_value_buffer);

        // Multi-operation requests with positive counts but null operation arrays represent
        // defects.
        let mut m = req(ZKRS_OP_MULTI);
        m.ops_len = 1;
        assert!(defects_of(&m, 1, false).marshal_oom);
        m.ops_len = 0;
        assert_eq!(defects_of(&m, 1, false), InputDefect::default());
    }

    #[test]
    fn status_of_gives_the_token_back() {
        let _rec = standin::recorder();
        let cases = [
            (rc::ZBADARGUMENTS, zkrs_status::ZKRS_EARLY_BADARGS),
            (rc::ZINVALIDSTATE, zkrs_status::ZKRS_EARLY_STATE),
            (rc::ZUNIMPLEMENTED, zkrs_status::ZKRS_UNIMPLEMENTED),
            (rc::ZNOWATCHER, zkrs_status::ZKRS_NOWATCHER),
            (rc::ZSYSTEMERROR, zkrs_status::ZKRS_SYSTEM),
            (rc::ZMARSHALLINGERROR, zkrs_status::ZKRS_LATE),
            (rc::ZCLOSING, zkrs_status::ZKRS_LATE),
            (rc::ZTHROTTLEDOP, zkrs_status::ZKRS_LATE),
            (rc::ZOK, zkrs_status::ZKRS_LATE),
        ];
        for (code, want) in cases {
            let e = SubmitError {
                stage: Stage::Validate,
                code,
                token: CReq::new(9, ZKRS_K_VOID, standin::discard_token),
            };
            assert_eq!(status_of(e), want, "{code}");
        }
        assert!(standin::discarded().is_empty());
    }

    #[test]
    fn auth_stages_follow_the_legacy_order() {
        let cert = c"pw".as_ptr();
        let null = std::ptr::null();
        assert_eq!(
            auth_early(-112, cert, -1),
            Err(zkrs_status::ZKRS_EARLY_STATE)
        );
        assert_eq!(auth_early(0, cert, 2), Err(zkrs_status::ZKRS_EARLY_STATE));
        assert_eq!(auth_early(3, cert, -1), Err(zkrs_status::ZKRS_SYSTEM));
        assert_eq!(auth_early(3, null, -1), Ok(()));
        assert_eq!(auth_early(3, cert, 0), Ok(()));
        assert_eq!(auth_early(3, null, 5), Ok(()));

        // Extract exposed byte slices for comparison since SecretBytes intentionally omits
        // PartialEq.
        let bytes = |c, n| auth_credential(c, n).map(|s| s.expose().to_vec());
        assert_eq!(bytes(null, 5), Ok(Vec::new()));
        assert_eq!(bytes(null, -1), Ok(Vec::new()));
        assert_eq!(bytes(cert, 0), Ok(Vec::new()));
        assert_eq!(bytes(cert, -1), Err(zkrs_status::ZKRS_SYSTEM));
        assert_eq!(bytes(cert, 2), Ok(b"pw".to_vec()));
    }

    #[test]
    fn submit_retains_only_on_ok() {
        let _rec = standin::recorder();
        let h = ready_handle();
        assert!(!h.is_null());
        let mut rq = req(ZKRS_OP_CREATE);
        rq.path = c"/a".as_ptr();
        assert_eq!(submit(h, &rq, 11,), zkrs_status::ZKRS_OK);
        // Malformed paths are rejected during early validation.
        rq.path = c"a".as_ptr();
        assert_eq!(submit(h, &rq, 12), zkrs_status::ZKRS_EARLY_BADARGS);
        // Data buffer and ACL defects are deferred to packet serialization.
        rq.path = c"/a".as_ptr();
        rq.datalen = 4;
        assert_eq!(submit(h, &rq, 13), zkrs_status::ZKRS_LATE);
        rq.datalen = -1;
        let bad = CAclVector {
            count: 2,
            data: std::ptr::null_mut(),
        };
        rq.acl = vec_ptr(&bad);
        assert_eq!(submit(h, &rq, 14), zkrs_status::ZKRS_LATE);
        rq.acl = std::ptr::null();
        assert_eq!(submit(h, &rq, 0), zkrs_status::ZKRS_LATE);
        // TTL bounds validation takes precedence over subsequent checks.
        rq.path = c"a".as_ptr();
        rq.ttl = 7;
        assert_eq!(submit(h, &rq, 16), zkrs_status::ZKRS_EARLY_BADARGS);
        assert!(standin::discarded().is_empty());
        // Finalizing the handle releases the driver, discarding retained completion tokens.
        zkrs_handle_release(h);
        assert_eq!(standin::discarded(), vec![11]);
    }

    #[test]
    fn submit_maps_the_multi_and_watch_stages() {
        let _rec = standin::recorder();
        let h = ready_handle();
        assert!(!h.is_null());
        let ops = [TestOp::unset(99)];
        let mut rq = req(ZKRS_OP_MULTI);
        rq.ops = ops_ptr(&ops);
        rq.ops_len = 1;
        rq.count = 1;
        assert_eq!(submit(h, &rq, 21), zkrs_status::ZKRS_UNIMPLEMENTED);
        // Null sub-operation array pointers fail at serialization stage.
        rq.ops = std::ptr::null();
        assert_eq!(submit(h, &rq, 22), zkrs_status::ZKRS_LATE);
        // Empty transaction batches are permitted by protocol specification.
        rq.ops_len = 0;
        rq.count = 0;
        assert_eq!(submit(h, &rq, 23), zkrs_status::ZKRS_OK);
        // Sub-operations borrow memory strictly for the duration of submission; caller buffers
        // remain valid.
        let path = c"/s".to_owned();
        let data = vec![7u8; 8];
        let ops = [TestOp::set(&path, data.as_ptr().cast(), 8, -1)];
        rq.ops = ops_ptr(&ops);
        rq.ops_len = 1;
        rq.count = 1;
        assert_eq!(submit(h, &rq, 27), zkrs_status::ZKRS_OK);
        // Invalid sub-operation buffer lengths trigger serialization defects.
        let bad = [TestOp::set(&path, data.as_ptr().cast(), -2, -1)];
        rq.ops = ops_ptr(&bad);
        assert_eq!(submit(h, &rq, 28), zkrs_status::ZKRS_LATE);
        drop((path, data));

        // Unregistered watchers trigger immediate error status during local check.
        let mut rw = req(ZKRS_OP_REMOVE_WATCHES);
        rw.path = c"/w".as_ptr();
        rw.wtype = 1;
        rw.watch_fn = 0x300;
        assert_eq!(submit(h, &rw, 24), zkrs_status::ZKRS_NOWATCHER);
        // Null watcher callbacks match any active watcher; local removals complete on the C side.
        rw.watch_fn = 0;
        rw.flags = ZKRS_F_LOCAL;
        assert_eq!(submit(h, &rw, 25), zkrs_status::ZKRS_OK_LOCAL);
        rw.flags = ZKRS_F_REMOVE_ALL;
        assert_eq!(submit(h, &rw, 26), zkrs_status::ZKRS_OK);
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
        let mut d = standin::discarded();
        d.sort_unstable();
        assert_eq!(d, vec![23, 26, 27]);
    }

    /// Verifies that negative credential lengths and allocation failures return `ZKRS_SYSTEM`.
    #[test]
    fn add_auth_checks_and_retains() {
        let _rec = standin::recorder();
        let h = ready_handle();
        assert!(!h.is_null());
        let cert = c"pw".as_ptr();
        assert_eq!(zkrs_add_auth_check(h, cert, 2), zkrs_status::ZKRS_OK);
        assert_eq!(zkrs_add_auth_check(h, cert, -1), zkrs_status::ZKRS_SYSTEM);
        assert_eq!(
            zkrs_add_auth_check(h, std::ptr::null(), -1),
            zkrs_status::ZKRS_OK
        );
        assert_eq!(add_auth(h, cert, -1, 31, 0), zkrs_status::ZKRS_SYSTEM);
        assert_eq!(
            add_auth(h, cert, 2, 0, ZKRS_AUTH_F_ALLOC_FAILED),
            zkrs_status::ZKRS_SYSTEM
        );
        assert_eq!(add_auth(h, cert, 2, 32, 0), zkrs_status::ZKRS_OK);
        // A null credential pointer accompanied by positive length represents an empty credential.
        assert_eq!(
            add_auth(h, std::ptr::null(), 5, 33, 0),
            zkrs_status::ZKRS_OK
        );
        // A token value of zero indicates that no completion callback was requested.
        assert_eq!(add_auth(h, cert, 2, 0, 0), zkrs_status::ZKRS_OK);
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
        let mut d = standin::discarded();
        d.sort_unstable();
        assert_eq!(d, vec![32, 33]);
    }

    /// Submits a request on a single threaded handle, draining and returning queued outbox items.
    fn st_submit(
        h: *const zkrs_handle,
        rq: &zkrs_request,
        token: usize,
    ) -> (zkrs_status, Vec<u32>) {
        let mut ob = empty_outbox();
        let st = zkrs_submit(h, rq, token, ZKRS_DELIVER_QUEUED, &mut ob);
        let mut types = Vec::new();
        let mut it = zkrs_item::empty();
        while crate::abi::outbox::zkrs_outbox_next(&mut ob, &raw mut it) == 1 {
            types.push(it.r#type);
        }
        crate::abi::outbox::zkrs_outbox_finish(&mut ob);
        assert!(ob.r#impl.is_null());
        (st, types)
    }

    #[test]
    fn st_submit_queues_with_no_socket() {
        let _rec = standin::recorder();
        let h = ready_flavor(ZKRS_FLAVOR_ST);
        assert!(!h.is_null());
        let st = with_handle(h, |i, _| matches!(i.driver(), Some(Driver::St(_))));
        assert!(st);
        let mut rq = req(ZKRS_OP_EXISTS);
        rq.path = c"/a".as_ptr();
        // single threaded handles without active sockets queue operations without emitting
        // transport items.
        assert_eq!(st_submit(h, &rq, 41), (zkrs_status::ZKRS_OK, vec![]));
        rq.path = c"a".as_ptr();
        assert_eq!(
            st_submit(h, &rq, 42),
            (zkrs_status::ZKRS_EARLY_BADARGS, vec![])
        );
        rq.path = c"/a".as_ptr();
        assert_eq!(st_submit(h, &rq, 0).0, zkrs_status::ZKRS_LATE);
        assert!(standin::discarded().is_empty());
        // Releasing the handle destroys the driver, triggering discard callbacks for retained
        // tokens.
        zkrs_handle_release(h);
        assert_eq!(standin::discarded(), vec![41]);
    }

    #[test]
    fn st_add_auth_checks_and_retains() {
        let _rec = standin::recorder();
        let h = ready_flavor(ZKRS_FLAVOR_ST);
        assert!(!h.is_null());
        let cert = c"pw".as_ptr();
        assert_eq!(zkrs_add_auth_check(h, cert, 2), zkrs_status::ZKRS_OK);
        let mut ob = empty_outbox();
        let st = zkrs_add_auth(h, c"digest".as_ptr(), cert, 2, 51, 0, &mut ob);
        crate::abi::outbox::zkrs_outbox_finish(&mut ob);
        assert_eq!(st, zkrs_status::ZKRS_OK);
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
        assert_eq!(standin::discarded(), vec![51]);
    }
}
