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

//! Host virtual method table integration implementing the `zk-driver` [`HostLeaves`] interface.
//!
//! The [`FfiHost`] structure maintains a copy of the C dispatch virtual table alongside an atomic
//! representation of the underlying C handle address (`zhandle_t *`). The `zk-driver` core invokes
//! these host hooks via `Env`. Pure hooks execute synchronously and do not block. Blocking hooks
//! (such as DNS resolution) require an [`Unlocked`] witness token, guaranteeing at compile time
//! that they execute outside the protocol `core` lock and any leaf locks while allowing the outer
//! `io` owner lock to be held.
//!
//! During terminal session teardown, `host` is zeroed atomically immediately prior to returning
//! `ZKRS_RELEASE`. Any subsequent invocation attempting to access host services detects this zeroed
//! state and aborts safely, preventing dangling pointer dereferences into deallocated C structures.

use std::ffi::CStr;
use std::sync::atomic::{AtomicUsize, Ordering};

use zk_driver::{Discarded, HostLeaves, Unlocked};
use zk_proto::{ClientId, HostResolution, HostSpec, LogLevel, ResolveError};

use super::entry::abort_with;
use super::raw;
use super::tokens::{CTokens, CWatcher};
use super::types::zkrs_host_vtable;

/// Host integration context binding a session handle to C virtual method dispatch tables.
#[derive(Debug)]
pub(crate) struct FfiHost {
    vt: zkrs_host_vtable,
    host: AtomicUsize,
}

impl FfiHost {
    /// Instantiates host dispatch state from the supplied vtable and foreign handle pointer.
    pub(crate) fn new(vt: zkrs_host_vtable, host: usize) -> Self {
        Self {
            vt,
            host: AtomicUsize::new(host),
        }
    }

    /// Retrieves the opaque foreign handle pointer, aborting if accessed following terminal
    /// release.
    pub(crate) fn host(&self) -> usize {
        let h = self.host.load(Ordering::Acquire);
        if h == 0 {
            abort_with("zk-ffi: host vtable used after release");
        }
        h
    }

    /// Atomically clears the host handle pointer upon final session teardown.
    ///
    /// Invoked during terminal cleanup after all residual tokens have been extracted from the
    /// driver.
    pub(crate) fn zero(&self) {
        self.host.store(0, Ordering::Release);
    }

    /// Returns true if the host pointer has been zeroed, used primarily in test assertions.
    #[cfg(test)]
    pub(crate) fn is_zero(&self) -> bool {
        self.host.load(Ordering::Acquire) == 0
    }

    /// Retrieves the `discard_token` function pointer for [`super::tokens::CReq`].
    pub(crate) fn discard_fn(&self) -> unsafe extern "C" fn(usize) {
        match self.vt.discard_token {
            Some(f) => f,
            None => abort_with("zk-ffi: missing host vtable entry discard_token"),
        }
    }
}

impl HostLeaves<CTokens> for FfiHost {
    /// nonblocking hook: deallocates an unused request token via `discard_token`; watchers hold no
    /// C allocations.
    fn discard_token(&self, token: Discarded<CTokens>) {
        match token {
            Discarded::Completion(r) | Discarded::Auth(r) => drop(r),
            Discarded::Watcher(_) => {}
        }
    }

    /// nonblocking hook: samples the default session watcher callback pair.
    fn snapshot_default_watcher(&self) -> Option<CWatcher> {
        let w = raw::vt_snapshot_default_watcher(&self.vt, self.host());
        CWatcher::from_request(w.r#fn, w.ctx)
    }

    /// nonblocking hook: records negotiated session credentials into legacy client storage.
    fn store_client_id(&self, id: &ClientId) {
        raw::vt_store_client_id(&self.vt, self.host(), id.session_id, &id.passwd);
    }

    /// nonblocking hook: queries whether logging is enabled for the specified severity level.
    fn log_enabled(&self, level: LogLevel) -> bool {
        let code = u32::try_from(level.code()).unwrap_or(0);
        raw::vt_log_enabled(&self.vt, code)
    }

    /// nonblocking hook: queries whether deterministic server connection permutation is disabled.
    fn conn_permute_disabled(&self) -> bool {
        raw::vt_conn_permute_disabled(&self.vt)
    }

    /// nonblocking hook: samples a pseudo-random integer via the C runtime.
    fn rng_random(&self) -> i64 {
        raw::vt_rng_random(&self.vt)
    }

    /// nonblocking hook: samples a pseudo-random floating-point value via the C runtime.
    fn rng_drand48(&self) -> f64 {
        raw::vt_rng_drand48(&self.vt)
    }

    /// Blocking hook: reseeds pseudo-random number generator state without the `core` lock.
    fn rng_reseed(&self, proof: &Unlocked) {
        raw::vt_rng_reseed(&self.vt, proof);
    }

    /// Blocking hook: performs hostname and service port resolution via `getaddrinfo`.
    ///
    /// Formats `spec.host` and `spec.service` as NULL-terminated C strings in stack-owned buffers
    /// (falling back to heap allocation for oversized inputs) and delegates to [`raw::vt_resolve`].
    /// Maps C error codes `(errno, gai_rc)` into `ResolveError` and records skipped address
    /// families in `HostResolution`.
    fn resolve(&self, proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        let mut name_buf = [0u8; HOST_INLINE];
        let mut service_buf = [0u8; SERVICE_INLINE];
        let mut name_heap = None;
        let mut service_heap = None;
        let mut digits = [0u8; PORT_DIGITS];
        let (name, service) = match spec {
            HostSpec::Legacy(h) => (h.host.as_slice(), h.service.as_slice()),
            HostSpec::Native(h) => (h.host.as_bytes(), port_text(h.port, &mut digits)),
        };
        let mut out = raw::HostAddrs::default();
        // `c_text` appends a terminating NULL byte, ensuring valid C string construction.
        let (errno, gai_rc) = match (
            c_text(&mut name_buf, &mut name_heap, name),
            c_text(&mut service_buf, &mut service_heap, service),
        ) {
            (Some(n), Some(p)) => raw::vt_resolve(&self.vt, proof, n, p, &mut out),
            _ => (libc::EINVAL, 0),
        };
        let raw::HostAddrs {
            addrs,
            skipped_families,
        } = out;
        // Map successful resolution (errno 0 and gai_rc 0) to Ok, defaulting ambiguous non-zero
        // status to EINVAL.
        let result = if errno == 0 && gai_rc == 0 {
            Ok(addrs)
        } else {
            Err(ResolveError {
                gai_rc,
                errno: if errno == 0 { libc::EINVAL } else { errno },
            })
        };
        HostResolution {
            host,
            result,
            skipped_families,
        }
    }
}

/// Inline stack buffer capacity for hostnames. Standard DNS names do not exceed 253 octets,
/// avoiding heap allocation on the common resolution path.
const HOST_INLINE: usize = 256;

/// Inline stack buffer capacity for service names or decimal port strings.
const SERVICE_INLINE: usize = 32;

/// Maximum decimal digit count for an unsigned 16-bit port number.
const PORT_DIGITS: usize = 5;

/// Copies `b` with a trailing NULL byte into `buf` (or into a newly allocated buffer stored in
/// `heap` when `buf` is insufficient) and returns a borrowed [`CStr`] truncated at the first NULL.
fn c_text<'a>(buf: &'a mut [u8], heap: &'a mut Option<Box<[u8]>>, b: &[u8]) -> Option<&'a CStr> {
    let text: &[u8] = match buf.get_mut(..=b.len()) {
        Some(dst) => {
            if let Some((nul, body)) = dst.split_last_mut() {
                body.copy_from_slice(b);
                *nul = 0;
            }
            dst
        }
        None => heap.insert(raw::c_bytes(b)),
    };
    CStr::from_bytes_until_nul(text).ok()
}

/// Formats an unsigned 16-bit port number as decimal ASCII into `digits` and returns the active
/// slice.
fn port_text(port: u16, digits: &mut [u8; PORT_DIGITS]) -> &[u8] {
    let mut at = digits.len();
    let mut v = port;
    while at > 0 {
        at = at.saturating_sub(1);
        if let Some(d) = digits.get_mut(at) {
            *d = b'0'.saturating_add(u8::try_from(v % 10).unwrap_or(0));
        }
        v /= 10;
        if v == 0 {
            break;
        }
    }
    digits.get(at..).unwrap_or(&[])
}

#[cfg(test)]
mod tests {
    use super::super::standin;
    use super::super::types::zkrs_sockaddr_view;
    use super::*;

    #[test]
    fn pure_leaves_reach_the_vtable() {
        let _rec = standin::recorder();
        let h = FfiHost::new(standin::vtable(), 0x55);
        assert_eq!(h.host(), 0x55);
        assert!(h.log_enabled(LogLevel::Error));
        assert!(!h.log_enabled(LogLevel::Debug));
        assert_eq!(h.rng_random(), standin::RANDOM);
        assert_eq!(
            h.snapshot_default_watcher(),
            Some(CWatcher { f: 0x10, ctx: 0x55 })
        );
        h.store_client_id(&ClientId {
            session_id: 7,
            passwd: [1; 16],
        });
        assert_eq!(standin::client_ids(), vec![(0x55, 7, [1; 16])]);
    }

    fn legacy(host: &[u8], service: &[u8]) -> HostSpec {
        HostSpec::Legacy(zk_proto::legacy::connect::HostSpec {
            host: host.to_vec(),
            service: service.to_vec(),
            port: 0,
        })
    }

    fn view(a: &str) -> zkrs_sockaddr_view {
        zkrs_sockaddr_view::from_addr(a.parse().ok())
    }

    #[test]
    fn resolve_keeps_the_order_and_the_skipped_families() {
        let _rec = standin::recorder();
        let h = FfiHost::new(standin::vtable(), 0x55);
        let v4 = view("10.0.0.1:2181");
        let v6 = view("[::1]:2181");
        standin::set_resolve(vec![v4, v6, v4], vec![1, 17], 0, 0);
        let r = h.resolve(&Unlocked::new_at_entry(), 3, &legacy(b"zk1", b"2181"));
        let want: Vec<std::net::SocketAddr> =
            [v4, v6, v4].iter().filter_map(|v| v.to_addr()).collect();
        assert_eq!(r.host, 3);
        assert_eq!(r.result, Ok(want));
        assert_eq!(r.skipped_families, vec![1, 17]);
        assert_eq!(
            standin::resolved(),
            vec![(b"zk1".to_vec(), b"2181".to_vec())]
        );
    }

    #[test]
    fn resolve_gives_each_error() {
        let _rec = standin::recorder();
        let h = FfiHost::new(standin::vtable(), 0x55);
        let u = Unlocked::new_at_entry();
        let spec = legacy(b"nohost", b"2181");
        // Simulate getaddrinfo failure accompanied by system errno mapping.
        standin::set_resolve(Vec::new(), vec![2], libc::ENOENT, -2);
        let r = h.resolve(&u, 0, &spec);
        let e = ResolveError {
            gai_rc: -2,
            errno: libc::ENOENT,
        };
        assert_eq!((r.result, r.skipped_families), (Err(e), vec![2]));
        // Confirm that resolution failure in the absence of errno maps to standard EINVAL.
        standin::set_resolve(Vec::new(), Vec::new(), 0, -8);
        let e = ResolveError {
            gai_rc: -8,
            errno: libc::EINVAL,
        };
        assert_eq!(h.resolve(&u, 0, &spec).result, Err(e));
        // Resolution failure occurring prior to getaddrinfo invocation preserves system errno
        // directly.
        standin::set_resolve(Vec::new(), Vec::new(), libc::ENOSYS, 0);
        let e = ResolveError {
            gai_rc: 0,
            errno: libc::ENOSYS,
        };
        assert_eq!(h.resolve(&u, 0, &spec).result, Err(e));
        // Resolution returning zero addresses without error is treated as an empty success.
        standin::set_resolve(Vec::new(), Vec::new(), 0, 0);
        assert_eq!(h.resolve(&u, 0, &spec).result, Ok(Vec::new()));
    }

    #[test]
    fn resolve_passes_a_native_port_as_text() {
        let _rec = standin::recorder();
        let h = FfiHost::new(standin::vtable(), 0x55);
        let spec = HostSpec::Native(zk_proto::legacy::connect::NativeHost {
            host: "zk2".into(),
            port: 2182,
        });
        let _ = h.resolve(&Unlocked::new_at_entry(), 0, &spec);
        assert_eq!(
            standin::resolved(),
            vec![(b"zk2".to_vec(), b"2182".to_vec())]
        );
        h.zero();
        assert!(h.is_zero());
    }

    fn text(len: usize, fill: &[u8]) -> (Vec<u8>, bool) {
        let mut buf = [0u8; HOST_INLINE];
        let mut heap = None;
        let b: Vec<u8> = fill.iter().copied().cycle().take(len).collect();
        let got = c_text(&mut buf, &mut heap, &b).map(|c| c.to_bytes_with_nul().to_vec());
        (got.unwrap_or_default(), heap.is_some())
    }

    #[test]
    fn c_text_ends_short_and_long_texts_with_a_nul() {
        assert_eq!(text(3, b"zk1"), (b"zk1\0".to_vec(), false));
        assert_eq!(text(0, b"x"), (b"\0".to_vec(), false));
        let mut want = vec![b'a'; HOST_INLINE];
        want.push(0);
        assert_eq!(text(HOST_INLINE, b"a"), (want, true));
        let mut want = vec![b'b'; HOST_INLINE - 1];
        want.push(0);
        assert_eq!(text(HOST_INLINE - 1, b"b"), (want, false));
        // Embedded NULL bytes terminate the resulting C string slice at the first NULL boundary.
        assert_eq!(text(3, b"a\0b"), (b"a\0".to_vec(), false));
    }

    #[test]
    fn port_text_gives_the_decimal_digits() {
        let mut d = [0u8; PORT_DIGITS];
        assert_eq!(port_text(0, &mut d), b"0");
        assert_eq!(port_text(7, &mut d), b"7");
        assert_eq!(port_text(2181, &mut d), b"2181");
        assert_eq!(port_text(65535, &mut d), b"65535");
    }

    #[test]
    fn resolve_passes_a_long_legacy_service_from_the_heap() {
        let _rec = standin::recorder();
        let h = FfiHost::new(standin::vtable(), 0x55);
        let service = vec![b's'; SERVICE_INLINE + 3];
        let _ = h.resolve(&Unlocked::new_at_entry(), 0, &legacy(b"zk3", &service));
        assert_eq!(standin::resolved(), vec![(b"zk3".to_vec(), service)]);
    }
}
