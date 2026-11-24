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

//! Cross-language ABI layout verification tests asserting equivalence between C probe
//! measurements and Rust compile time layout constants across all build configurations.

#![allow(clippy::disallowed_macros, reason = "tests may assert")]

use zk_ffi_layout::{Fact, OPCODES, Probe, ZKRS_DEFINES, ZOO_ERRORS, compare, probes, rust_facts};

/// Canonical C type identifiers for all public ZooKeeper records mirrored by `zk-ffi`.
const MIRRORS: &[&str] = &[
    "struct Stat",
    "struct String_vector",
    "struct Id",
    "struct ACL",
    "struct ACL_vector",
    "zoo_op_result_t",
    "zoo_op_t",
];

fn probe(config: &str) -> Option<&'static Probe> {
    probes().iter().find(|p| p.config == config)
}

fn has(facts: &[Fact], fact: &str, ty: &str, member: &str) -> bool {
    facts
        .iter()
        .any(|f| f.fact == fact && f.ty == ty && f.member == member)
}

/// Verifies that every selected header configuration compiles and executes cleanly. Missing
/// dependency headers fail the test rather than skipping; `ZK_LAYOUT_CONFIGS` may restrict
/// the active configuration set.
#[test]
fn every_config_builds_and_runs() {
    assert!(!probes().is_empty(), "no configuration selected");
    for p in probes() {
        assert!(
            p.error.is_empty(),
            "the {} probe ({}) did not build. Give the header flags in \
             ZK_LAYOUT_SSL_CFLAGS or ZK_LAYOUT_SASL_CFLAGS, or select the \
             configurations in ZK_LAYOUT_CONFIGS.\n{}",
            p.config,
            p.cflags,
            p.error
        );
        assert!(
            !p.facts.is_empty(),
            "the {} probe printed no facts",
            p.config
        );
    }
}

/// Verifies bidirectional equivalence between C and Rust layout facts and constant values
/// across every active header configuration.
#[test]
fn rust_matches_c_in_every_config() {
    let mut bad = Vec::new();
    for p in probes().iter().filter(|p| p.error.is_empty()) {
        for line in compare(p.facts, rust_facts()) {
            bad.push(format!("{}: {line}", p.config));
        }
    }
    assert!(bad.is_empty(), "layout mismatches:\n{}", bad.join("\n"));
}

/// Verifies that configuration preprocessor defines propagate into the public headers so
/// each profile measures its expected set of conditional declarations.
#[test]
fn the_configs_measure_their_own_types() {
    let tls = |p: &Probe| has(p.facts, "offsetof", "zsock_t", "ssl_sock");
    let sasl = |p: &Probe| has(p.facts, "sizeof", "zoo_sasl_params_t", "-");
    let want = [
        ("plain", false, false),
        ("ssl", true, false),
        ("sasl", false, true),
        ("full", true, true),
    ];
    for (config, t, s) in want {
        let Some(p) = probe(config).filter(|p| p.error.is_empty()) else {
            continue;
        };
        assert_eq!(
            (tls(p), sasl(p)),
            (t, s),
            "{config}: the struct set is wrong"
        );
    }
}

/// Verifies that the Rust fact table encompasses all legacy mirrors, private `zkrs.h`
/// structures, and constant sets, with complete per-field coverage.
#[test]
fn the_rust_table_covers_the_abi() {
    let r = rust_facts();
    for ty in MIRRORS {
        assert!(has(r, "sizeof", ty, "-"), "no Rust mirror of {ty}");
    }
    for (ty, member) in [
        ("zkrs_request", "op"),
        ("zkrs_host_vtable", "resolve"),
        ("zkrs_sockaddr_view", "addr"),
        ("zkrs_item", "type"),
        ("zoo_op_t", "set_op.stat"),
        ("zoo_op_t", "check_op"),
    ] {
        assert!(
            has(r, "offsetof", ty, member),
            "no Rust fact for {ty}.{member}"
        );
    }
    for (ty, member) in [
        ("zkrs_step", "ZKRS_EXIT"),
        (ZKRS_DEFINES, "ZKRS_INIT_SASL_POINT"),
        (ZOO_ERRORS, "ZTHROTTLEDOP"),
        (OPCODES, "CREATE_TTL"),
    ] {
        assert!(has(r, "value", ty, member), "no Rust value {ty} {member}");
    }
    let stat = r
        .iter()
        .filter(|f| f.ty == "struct Stat" && f.fact == "offsetof")
        .count();
    assert_eq!(stat, 11, "struct Stat has 11 fields");
}

/// Verifies that `struct Stat` occupies 72 bytes on 64-bit target architectures.
#[cfg(target_pointer_width = "64")]
#[test]
fn stat_is_72_bytes_on_64_bit_targets() {
    for p in probes().iter().filter(|p| p.error.is_empty()) {
        let size = p
            .facts
            .iter()
            .find(|f| f.fact == "sizeof" && f.ty == "struct Stat")
            .map(|f| f.value);
        assert_eq!(size, Some(72), "{}", p.config);
    }
}

/// Verifies that `compare` diagnoses every class of layout or constant mismatch while
/// ignoring public C structures that `zk-ffi` does not mirror.
#[test]
fn compare_reports_each_kind_of_difference() {
    let c = [
        Fact::new("sizeof", "struct Id", "-", 16),
        Fact::new("offsetof", "struct Id", "scheme", 0),
        Fact::new("offsetof", "struct Id", "id", 8),
        Fact::new("offsetof", "struct Stat", "dataLength", 52),
        Fact::new("sizeof", "struct Stat", "-", 72),
        Fact::new("sizeof", "struct buffer", "-", 16),
        Fact::new("sizeof", "zkrs_new", "-", 8),
        Fact::new("value", OPCODES, "GETDATA", 4),
        Fact::new("value", ZOO_ERRORS, "ZOK", 0),
        Fact::new("sizeof", ZOO_ERRORS, "-", 4),
    ];
    let rust = [
        Fact::new("sizeof", "struct Id", "-", 16),
        Fact::new("offsetof", "struct Id", "scheme", 0),
        Fact::new("offsetof", "struct Id", "id", 4),
        Fact::new("offsetof", "struct Stat", "data_length", 52),
        Fact::new("offsetof", "struct Stat", "extra", 60),
        Fact::new("sizeof", "struct Stat", "-", 72),
        Fact::new("value", OPCODES, "GET_DATA", 4),
        Fact::new("value", OPCODES, "MULTI_READ", 22),
        Fact::new("value", ZOO_ERRORS, "ZOK", 0),
        Fact::new("value", ZOO_ERRORS, "ZNEW", -200),
        Fact::new("sizeof", "zkrs_gone", "-", 8),
    ];
    let mut got = compare(&c, &rust);
    got.sort();
    let mut want = vec![
        "offsetof struct Id id: C 8, Rust 4".to_owned(),
        "C zkrs_new layout has no Rust definition".to_owned(),
        "Rust offsetof struct Stat extra = 60 has no C counterpart".to_owned(),
        "Rust value enum ZOO_ERRORS ZNEW = -200 has no C counterpart".to_owned(),
        "Rust zkrs_gone layout has no C definition".to_owned(),
    ];
    want.sort();
    assert_eq!(got, want);
    let short = [Fact::new("value", ZOO_ERRORS, "ZOK", 0)];
    assert_eq!(
        compare(&[Fact::new("value", ZOO_ERRORS, "ZNONODE", -101)], &short),
        vec![
            "C value enum ZOO_ERRORS ZNONODE = -101 has no Rust counterpart".to_owned(),
            "Rust value enum ZOO_ERRORS ZOK = 0 has no C counterpart".to_owned(),
        ]
    );
}
