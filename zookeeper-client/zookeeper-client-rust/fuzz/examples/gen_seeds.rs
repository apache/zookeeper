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

//! Generates the initial seed corpus for each fuzz target.
//!
//! Seeds are synthesized from `zk-jute` binary fixtures, `zk-testkit` TLS certificates and keys,
//! and compact session simulation programs. Seed files are written to `corpus/<target>/` (which
//! is ignored by git) rather than committed to the repository. Run once prior to fuzzing:
//!
//! ```text
//! cargo +nightly run --example gen_seeds
//! ```
//!
//! Generating the TLS corpora requires the `openssl` CLI used by `zk-testkit`.

use std::fs;
use std::path::{Path, PathBuf};

use zk_fuzz::sim::{Cmd, Options, Profile, connect_response, connected_prefix};
use zk_fuzz::{Enc, framed};
use zk_jute::Record;
use zk_jute::proto::MultiHeader;

/// Returns the root directory of the fuzz crate.
fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

/// Writes seed buffers for `target` into `corpus/<target>/seed-NNN`.
fn write(target: &str, seeds: &[Vec<u8>]) {
    let dir = root().join("corpus").join(target);
    fs::create_dir_all(&dir).expect("failed to create corpus directory");
    for (i, s) in seeds.iter().enumerate() {
        fs::write(dir.join(format!("seed-{i:03}")), s).expect("failed to write seed file");
    }
    println!("{target}: {} seeds", seeds.len());
}

/// Loads length-prefixed (`[i32 length][bytes]`) entries from a `zk-jute` binary fixture.
fn fixture(name: &str) -> Vec<Vec<u8>> {
    let path = root()
        .join("../crates/zk-jute/tests/fixtures")
        .join(format!("{name}.bin"));
    let data = fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let mut out = Vec::new();
    let mut rest = data.as_slice();
    while rest.len() >= 4 {
        let n = u32::from_be_bytes(rest[..4].try_into().expect("four-byte length")) as usize;
        let Some(entry) = rest.get(4..4 + n) else {
            break;
        };
        out.push(entry.to_vec());
        rest = &rest[4 + n..];
    }
    out
}

/// Returns the list of Jute record names in `for_each_record!` declaration order.
fn records() -> Vec<String> {
    let mut names = Vec::new();
    macro_rules! name {
        ($m:ident, $r:ident) => {
            names.push(format!("{}.{}", stringify!($m), stringify!($r)));
        };
    }
    zk_fuzz::for_each_record!(name);
    names
}

fn jute_records() -> Vec<Vec<u8>> {
    let mut seeds = Vec::new();
    for (i, name) in records().iter().enumerate() {
        for entry in fixture(name).into_iter().take(2) {
            let mut s = vec![u8::try_from(i).expect("fewer than 256 records")];
            s.extend_from_slice(&entry);
            seeds.push(s);
        }
    }
    seeds
}

fn frame_decoder() -> Vec<Vec<u8>> {
    let mut stream = framed(b"hello");
    stream.extend_from_slice(&framed(&[]));
    stream.extend_from_slice(&framed(&[7u8; 40]));
    let mut seeds = Vec::new();
    for (max, plan) in [
        (0u8, vec![0x0c, 0x01]),
        (2, vec![0x03, 0x10, 0x22]),
        (3, vec![0x40]),
    ] {
        let mut e = Enc::new();
        e.u8(max);
        if max == 3 {
            e.u16(64);
        }
        e.u8(u8::try_from(plan.len() - 1).expect("a short plan"))
            .raw(&plan)
            .raw(&stream);
        seeds.push(e.finish());
    }
    seeds
}

fn profiles() -> [Profile; 3] {
    [Profile::LegacySt, Profile::LegacyMt, Profile::Native]
}

fn connect_responses() -> Vec<Vec<u8>> {
    let mut seeds = Vec::new();
    for p in profiles() {
        for ro in [None, Some(false), Some(true)] {
            let mut o = Options::plain(p);
            o.allow_read_only = ro == Some(true);
            let mut e = Enc::new();
            o.write(&mut e);
            e.lp(&connect_response(0, 30_000, 0x1234, 16, &[9u8; 16], ro));
            seeds.push(e.finish());
        }
    }
    seeds
}

/// Encodes a multi-operation response payload containing one entry per operation type in `types`.
fn multi_body(types: &[i32]) -> Vec<u8> {
    let mut out = Vec::new();
    for &t in types {
        MultiHeader {
            r#type: t,
            done: false,
            err: 0,
        }
        .encode(&mut out);
        match t {
            1 => out.extend_from_slice(&[0, 0, 0, 2, b'/', b'a']),
            -1 => out.extend_from_slice(&(-101i32).to_be_bytes()),
            _ => {}
        }
    }
    zk_jute::multi::encode_end(&mut out);
    out
}

fn multi_responses() -> Vec<Vec<u8>> {
    let mut seeds = Vec::new();
    for p in profiles() {
        let mut e = Enc::new();
        Options::plain(p).write(&mut e);
        // Two delete operations on "/a" followed by the multi-response payload.
        e.u8(2).u8(0);
        for _ in 0..2 {
            e.u8(1).u8(1).i32(-1);
        }
        e.i64(5).i32(0).lp(&multi_body(&[2, 2]));
        seeds.push(e.finish());
        let mut e = Enc::new();
        Options::plain(p).write(&mut e);
        // Create operation on "/a" with empty data and ACLs, followed by a failing version check.
        e.u8(2).u8(0);
        e.u8(0).u8(0).u8(1).u8(0).u8(0).u8(0).i32(0);
        e.u8(3).u8(1).i32(0);
        e.i64(6).i32(-2).lp(&multi_body(&[1, -1]));
        seeds.push(e.finish());
    }
    seeds
}

/// Builds a baseline session script: establish handshake, issue `GetData`, receive reply, close.
fn session(o: &Options) -> Vec<u8> {
    let mut e = Enc::new();
    connected_prefix(o, &mut e);
    // Queued GetData request for "/a" without watch or injected input defect.
    e.u8(Cmd::Submit as u8).u8(3).u8(1).u8(0).u8(0).u8(0);
    let body = fixture("proto.GetDataResponse")
        .into_iter()
        .next()
        .unwrap_or_default();
    e.u8(Cmd::Reply as u8).u8(0).i64(7).u8(0).lp(&body);
    e.u8(Cmd::Tick as u8).u16(1000);
    e.u8(Cmd::TickInterest as u8);
    e.u8(Cmd::Close as u8);
    e.finish()
}

fn session_streams() -> Vec<Vec<u8>> {
    let mut seeds = Vec::new();
    for p in profiles() {
        let mut o = Options::plain(p);
        seeds.push(session(&o));
        o.tls = true;
        o.chroot = Some(b"/app".to_vec());
        seeds.push(session(&o));
        o.manual_tx = true;
        o.servers = 3;
        seeds.push(session(&o));
    }
    seeds
}

fn sasl_frames() -> Vec<Vec<u8>> {
    let token = fixture("proto.SetSASLResponse")
        .into_iter()
        .next()
        .unwrap_or_default();
    let mut seeds = Vec::new();
    for p in profiles() {
        let mut e = Enc::new();
        Options::plain(p).write(&mut e);
        e.u8(Cmd::PlanSasl as u8).u8(2).u8(1).lp(b"token").u8(0);
        e.u8(Cmd::Reply as u8).u8(0).i64(1).u8(0).lp(&token);
        e.u8(Cmd::PlanSasl as u8).u8(2).u8(0).u8(1);
        e.u8(Cmd::Reply as u8).u8(0).i64(2).u8(0).lp(&token);
        seeds.push(e.finish());
    }
    seeds
}

fn connect_strings() -> Vec<Vec<u8>> {
    [
        "127.0.0.1:23700",
        "127.0.0.1:23700,127.0.0.1:23701/app",
        "[::1]:23702,localhost:23703/a/b",
        "zk1,zk2:23704/",
        " host:23705 , other ",
        "host:abc",
        "/only/root",
    ]
    .iter()
    .map(|s| s.as_bytes().to_vec())
    .collect()
}

fn zpaths() -> Vec<Vec<u8>> {
    let mut seeds = Vec::new();
    for (mode, root, path) in [
        (0u8, "", "/"),
        (0, "/app", "/a/b"),
        (1, "/app", "/a/b/"),
        (2, "/x/y", "/x/y/z"),
        (0, "/", "/zookeeper/config"),
        (3, "/app", "/a/./b"),
    ] {
        let mut e = Enc::new();
        e.u8(mode).lp(root.as_bytes()).raw(path.as_bytes());
        seeds.push(e.finish());
    }
    seeds
}

fn sasl_digest_md5() -> Vec<Vec<u8>> {
    let ch1 = concat!(
        r#"realm="zk-sasl-md5",nonce="OA6MG9tEQGm2hh","#,
        r#"qop="auth",charset=utf-8,algorithm=md5-sess"#
    )
    .as_bytes();
    let mut seeds = Vec::new();
    for (user, pass) in [("super", "test"), ("bob", "p\u{e4}ss")] {
        // Explicit mismatched rspauth token.
        let mut e = Enc::new();
        e.lp(user.as_bytes())
            .lp(pass.as_bytes())
            .u8(0)
            .lp(ch1)
            .u8(0)
            .raw(b"rspauth=0123456789abcdef0123456789abcdef");
        seeds.push(e.finish());
        // Mode 2 instructs the harness to synthesize the matching rspauth token.
        let mut e = Enc::new();
        e.lp(user.as_bytes())
            .lp(pass.as_bytes())
            .u8(0)
            .lp(ch1)
            .u8(2);
        seeds.push(e.finish());
    }
    seeds
}

/// Reads all files in `dir` matching any extension in `exts` (up to 8 KiB per file).
fn tls_files(dir: &Path, exts: &[&str]) -> Vec<Vec<u8>> {
    let mut paths: Vec<PathBuf> = fs::read_dir(dir)
        .map(|r| r.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    paths.sort();
    paths
        .into_iter()
        .filter(|p| {
            p.extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| exts.contains(&e))
        })
        .filter_map(|p| fs::read(p).ok())
        .filter(|b| b.len() <= 8192)
        .collect()
}

fn tls_trust(dir: &Path) -> Vec<Vec<u8>> {
    let mut seeds = tls_files(dir, &["pem", "crt"]);
    seeds.extend(tls_files(&dir.join("trust"), &["pem", "crt"]));
    seeds.truncate(48);
    seeds
}

fn tls_keys(dir: &Path) -> Vec<Vec<u8>> {
    let mut images = tls_files(dir, &["key"]);
    images.extend(tls_files(&dir.join("keys"), &["pem", "key"]));
    images.truncate(48);
    images
        .into_iter()
        .map(|img| {
            let mut e = Enc::new();
            e.lp(b"zk-test-pass").raw(&img);
            e.finish()
        })
        .collect()
}

fn tls_stream() -> Vec<Vec<u8>> {
    let mut seeds = vec![vec![0, 3]];
    for mode in [1u8, 2] {
        let mut e = Enc::new();
        // Server write, flush to client, client read, client write, drain client ciphertext,
        // corrupt stream, server write, deliver, read, and close.
        e.u8(mode)
            .u8(0)
            .lp(b"hello")
            .u8(3)
            .u8(6)
            .u8(7)
            .lp(b"ping")
            .u8(8);
        e.u8(0)
            .lp(&[1u8; 300])
            .u8(2)
            .u16(100)
            .u8(4)
            .u16(3)
            .u8(1)
            .u8(3)
            .u8(6)
            .u8(9)
            .u8(3);
        seeds.push(e.finish());
    }
    seeds
}

fn ffi_writers() -> Vec<Vec<u8>> {
    let bodies: [(u8, Vec<u8>); 9] = [
        (0, Vec::new()),
        (1, fixture("proto.ExistsResponse").swap_remove(0)),
        (2, fixture("proto.GetDataResponse").swap_remove(0)),
        (3, fixture("proto.GetChildrenResponse").swap_remove(0)),
        (4, fixture("proto.GetChildren2Response").swap_remove(0)),
        (5, fixture("proto.GetACLResponse").swap_remove(0)),
        (6, fixture("proto.CreateResponse").swap_remove(0)),
        (7, multi_body(&[1, 5, -1, 2])),
        (8, fixture("proto.Create2Response").swap_remove(0)),
    ];
    let mut seeds = Vec::new();
    for (kind, body) in bodies {
        for nulls in [0u16, 0x0aaa] {
            let mut e = Enc::new();
            e.u8(kind).u8(0).i32(0).u16(nulls).u8(4).lp(&body);
            // Async multi-operation slots: string slot, stat slot, and void slot.
            e.u8(3)
                .u8(0)
                .u8(10)
                .u8(0)
                .u8(1)
                .u8(30)
                .u8(0)
                .u8(2)
                .u8(0)
                .u8(0);
            // Sync multi-operation slots, sync destination buffers, and watcher event path.
            e.u8(2).u8(0).u8(3).u8(0).u8(1).u8(20).u8(0);
            e.u8(0).u16(12).u8(6).lp(b"/watched/path");
            seeds.push(e.finish());
        }
    }
    seeds
}

fn main() {
    write("jute_records", &jute_records());
    write("frame_decoder", &frame_decoder());
    write("connect_response", &connect_responses());
    write("multi_response", &multi_responses());
    write("session_stream", &session_streams());
    write("sasl_frames", &sasl_frames());
    write("connect_string", &connect_strings());
    write("zpath", &zpaths());
    write("sasl_digest_md5", &sasl_digest_md5());
    write("ffi_writers", &ffi_writers());
    write("tls_stream", &tls_stream());
    let dir = zk_testkit::fixtures::tls_dir();
    write("tls_trust", &tls_trust(dir));
    write("tls_keys", &tls_keys(dir));
}
