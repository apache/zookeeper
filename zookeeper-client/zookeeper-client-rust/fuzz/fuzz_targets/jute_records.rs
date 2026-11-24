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

//! Fuzzes Jute record deserialization and round-trip serialization across all client records.
//!
//! The leading byte selects the target record type. Any successfully decoded value must reencode
//! to the exact byte length consumed by the reader, match `encoded_len()`, and round trip
//! deterministically through `from_bytes`.

#![no_main]
#![forbid(unsafe_code)]

use libfuzzer_sys::fuzz_target;
use zk_jute::{Reader, Record};

fn check<R: Record + PartialEq + core::fmt::Debug>(data: &[u8]) {
    let mut r = Reader::new(data);
    let Ok(x) = R::decode(&mut r) else {
        return;
    };
    let used = data.len() - r.remaining();
    let y = x.to_bytes();
    assert_eq!(
        y.len(),
        x.encoded_len(),
        "encoded_len() did not match serialized byte length"
    );
    assert_eq!(
        y.len(),
        used,
        "serialized byte length did not match bytes consumed by decode"
    );
    let z = R::from_bytes(&y).expect("serialized record must decode");
    assert_eq!(x, z, "record value mutated across encode/decode round-trip");
    assert_eq!(z.to_bytes(), y, "record serialization is non-deterministic");
}

fuzz_target!(|data: &[u8]| {
    let Some((&sel, rest)) = data.split_first() else {
        return;
    };
    let mut checks: Vec<fn(&[u8])> = Vec::new();
    macro_rules! add {
        ($m:ident, $r:ident) => {
            checks.push(check::<zk_jute::$m::$r>);
        };
    }
    zk_fuzz::for_each_record!(add);
    let f = checks[usize::from(sel) % checks.len()];
    f(rest);
});
