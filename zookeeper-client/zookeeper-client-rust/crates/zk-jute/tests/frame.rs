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

//! Property based and unit verification suites for length prefixed wire frame encoding and
//! incremental decoding.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use proptest::prelude::*;
use zk_jute::{FrameDecoder, FrameError, Pushed, begin_frame, encode_frame, finish_frame};

/// Streams `stream` into a [`FrameDecoder`] using a repeating sequence of chunk sizes `cuts` and
/// collects all assembled frames. Any unconsumed bytes remaining after a frame boundary are
/// resubmitted on the subsequent iteration, mirroring transport driver ingestion loops.
fn decode_chunks(stream: &[u8], cuts: &[usize]) -> (Vec<Vec<u8>>, FrameDecoder) {
    let mut dec = FrameDecoder::new(None);
    let mut frames = Vec::new();
    let mut at = 0;
    let mut cut = cuts.iter().cycle();
    while at < stream.len() {
        let size = cut.next().copied().unwrap_or(1).max(1);
        let end = (at + size).min(stream.len());
        let mut chunk = &stream[at..end];
        while !chunk.is_empty() {
            let (n, result) = dec.push(chunk);
            match result {
                Pushed::Frame(f) => frames.push(f),
                Pushed::NeedMore => assert_eq!(n, chunk.len()),
                Pushed::Error(e) => panic!("unexpected {e}"),
            }
            chunk = &chunk[n..];
        }
        at = end;
    }
    (frames, dec)
}

/// Configures the property test runner, reducing iteration count and disabling file persistence
/// under Miri due to interpretation overhead and filesystem isolation.
fn config() -> ProptestConfig {
    let mut c = ProptestConfig::default();
    if cfg!(miri) {
        c.cases = 4;
        c.failure_persistence = None;
    }
    c
}

proptest! {
    #![proptest_config(config())]

    #[test]
    fn any_split_gives_the_same_frames(
        bodies in prop::collection::vec(prop::collection::vec(any::<u8>(), 1..200), 0..8),
        cuts in prop::collection::vec(1usize..64, 1..16),
    ) {
        let mut stream = Vec::new();
        for b in &bodies {
            encode_frame(b, &mut stream);
        }
        let (whole, dec) = decode_chunks(&stream, &[stream.len().max(1)]);
        prop_assert_eq!(&whole, &bodies);
        prop_assert!(dec.is_idle());
        let (split, dec) = decode_chunks(&stream, &cuts);
        prop_assert_eq!(&split, &bodies);
        prop_assert!(dec.is_idle());
        let (bytewise, _) = decode_chunks(&stream, &[1]);
        prop_assert_eq!(&bytewise, &bodies);
    }

    /// Verifies that direct zero-copy body ingestion via `body_room` and `commit` (combined with
    /// buffer recycling across frames) yields identical decoded payloads to standard `push`
    /// ingestion.
    #[test]
    fn reads_into_the_body_give_the_same_frames(
        bodies in prop::collection::vec(prop::collection::vec(any::<u8>(), 1..300), 0..8),
        cuts in prop::collection::vec(1usize..100, 1..16),
    ) {
        let mut stream = Vec::new();
        for b in &bodies {
            encode_frame(b, &mut stream);
        }
        let mut dec = FrameDecoder::new(None);
        let mut frames = Vec::new();
        let mut at = 0;
        let mut cut = cuts.iter().cycle();
        while at < stream.len() {
            let size = cut.next().copied().unwrap_or(1);
            let end = (at + size).min(stream.len());
            let room = dec.body_room(end - at);
            let result = if room.is_empty() {
                let (n, result) = dec.push(&stream[at..end]);
                at += n;
                result
            } else {
                let n = room.len();
                room.copy_from_slice(&stream[at..at + n]);
                at += n;
                dec.commit(n)
            };
            match result {
                Pushed::Frame(f) => {
                    frames.push(f.clone());
                    dec.recycle(f);
                }
                Pushed::NeedMore => {}
                Pushed::Error(e) => panic!("unexpected {e}"),
            }
        }
        prop_assert_eq!(&frames, &bodies);
        prop_assert!(dec.is_idle());
    }

    #[test]
    fn want_reads_one_frame_at_a_time(
        bodies in prop::collection::vec(prop::collection::vec(any::<u8>(), 1..100), 1..6),
    ) {
        let mut stream = Vec::new();
        for b in &bodies {
            encode_frame(b, &mut stream);
        }
        let mut dec = FrameDecoder::default();
        let mut at = 0;
        let mut frames = Vec::new();
        while at < stream.len() {
            let want = dec.want();
            prop_assert!(want > 0);
            let (n, result) = dec.push(&stream[at..at + want]);
            prop_assert_eq!(n, want);
            if let Pushed::Frame(f) = result {
                frames.push(f);
            }
            at += n;
        }
        prop_assert_eq!(frames, bodies);
    }
}

#[test]
fn push_stops_at_the_end_of_a_frame() {
    let mut stream = Vec::new();
    encode_frame(b"ab", &mut stream);
    encode_frame(b"cde", &mut stream);
    let mut dec = FrameDecoder::new(None);
    assert_eq!(dec.push(&stream), (6, Pushed::Frame(b"ab".to_vec())));
    assert!(dec.is_idle());
    assert_eq!(dec.push(&stream[6..]), (7, Pushed::Frame(b"cde".to_vec())));
    assert_eq!(dec.push(&[]), (0, Pushed::NeedMore));
}

#[test]
fn zero_length_is_an_error() {
    let mut dec = FrameDecoder::new(None);
    let (n, r) = dec.push(&[0, 0, 0, 0, 1, 2]);
    assert_eq!((n, r), (4, Pushed::Error(FrameError::Empty)));
    // The failed state persists across subsequent push calls until explicitly reset.
    assert_eq!(dec.push(&[1, 2]), (0, Pushed::Error(FrameError::Empty)));
    assert_eq!(dec.want(), 0);
    dec.reset();
    assert!(dec.is_idle());
    assert_eq!(dec.want(), 4);
    assert_eq!(dec.push(&[0, 0, 0, 1, 9]), (5, Pushed::Frame(vec![9])));
}

#[test]
fn negative_length_is_an_error_and_can_stall() {
    let mut dec = FrameDecoder::new(None);
    let (n, r) = dec.push(&(-5i32).to_be_bytes());
    assert_eq!((n, r), (4, Pushed::Error(FrameError::Negative(-5))));
    dec.stall();
    assert!(dec.is_stalled());
    assert_eq!(dec.want(), 0);
    assert_eq!(dec.push(&[0, 0, 0, 1, 7]), (0, Pushed::NeedMore));
    dec.reset();
    assert_eq!(dec.push(&[0, 0, 0, 1, 7]), (5, Pushed::Frame(vec![7])));
}

#[test]
fn length_above_the_maximum_is_an_error() {
    let mut dec = FrameDecoder::new(Some(10));
    assert_eq!(dec.push(&[0, 0, 0, 10]).1, Pushed::NeedMore);
    dec.reset();
    assert_eq!(
        dec.push(&[0, 0, 0, 11]),
        (4, Pushed::Error(FrameError::TooLarge(11)))
    );
    let mut dec = FrameDecoder::new(None);
    assert_eq!(dec.push(&i32::MAX.to_be_bytes()), (4, Pushed::NeedMore));
}

#[test]
fn want_tracks_header_and_body() {
    let mut dec = FrameDecoder::new(None);
    assert_eq!(dec.want(), 4);
    assert_eq!(dec.push(&[0, 0]), (2, Pushed::NeedMore));
    assert_eq!(dec.want(), 2);
    assert!(!dec.is_idle());
    assert_eq!(dec.push(&[0, 5]), (2, Pushed::NeedMore));
    assert_eq!(dec.want(), 5);
    assert_eq!(dec.push(&[1, 2]), (2, Pushed::NeedMore));
    assert_eq!(dec.want(), 3);
}

#[test]
fn hostile_length_does_not_reserve_memory() {
    // An untrusted peer declaring a 2 GiB frame while transmitting only a small payload incurs
    // memory allocation proportional strictly to the delivered bytes.
    let mut dec = FrameDecoder::new(None);
    let mut input = i32::MAX.to_be_bytes().to_vec();
    input.extend_from_slice(&[0x55; 100]);
    assert_eq!(dec.push(&input), (104, Pushed::NeedMore));
    assert_eq!(dec.want(), 0x7fff_ffff - 100);
    let chunk = vec![0u8; 256 * 1024];
    for _ in 0..4 {
        assert_eq!(dec.push(&chunk), (chunk.len(), Pushed::NeedMore));
    }
    assert_eq!(dec.want(), 0x7fff_ffff - 100 - 4 * chunk.len());
}

#[test]
fn encoders_write_the_length_prefix() {
    let mut out = vec![0xaa];
    encode_frame(b"xyz", &mut out);
    assert_eq!(out, [0xaa, 0, 0, 0, 3, b'x', b'y', b'z']);

    let mut out = vec![0xbb];
    let start = begin_frame(&mut out);
    out.extend_from_slice(b"hello");
    finish_frame(&mut out, start);
    assert_eq!(out, [0xbb, 0, 0, 0, 5, b'h', b'e', b'l', b'l', b'o']);

    let mut dec = FrameDecoder::new(None);
    assert_eq!(dec.push(&out[1..]), (9, Pushed::Frame(b"hello".to_vec())));
}

#[test]
fn errors_display() {
    assert_eq!(FrameError::Empty.to_string(), "zero frame length");
    assert_eq!(
        FrameError::Negative(-1).to_string(),
        "negative frame length -1"
    );
}

/// Verifies that a recycled frame buffer is reused as the backing allocation for the next frame
/// and truncated to the new payload length upon completion.
#[test]
fn a_recycled_body_serves_the_next_frame() {
    let mut wire = Vec::new();
    encode_frame(&[1u8; 1000], &mut wire);
    let mut dec = FrameDecoder::new(None);
    let (_, Pushed::Frame(first)) = dec.push(&wire) else {
        panic!("no first frame");
    };
    let (at, cap) = (first.as_ptr(), first.capacity());
    dec.recycle(first);
    let mut wire = Vec::new();
    encode_frame(b"ab", &mut wire);
    let (n, Pushed::Frame(second)) = dec.push(&wire) else {
        panic!("no second frame");
    };
    assert_eq!(n, wire.len());
    assert_eq!(second, b"ab");
    assert_eq!((second.as_ptr(), second.capacity()), (at, cap));
}

/// Verifies that large recycled buffers within the retention threshold remain pooled across
/// successive large frames.
#[test]
fn a_large_body_stays_for_a_large_frame() {
    const MIB: usize = 1024 * 1024;
    let mut dec = FrameDecoder::new(None);
    dec.recycle(vec![0u8; MIB]);
    let mut wire = Vec::new();
    encode_frame(&vec![3u8; MIB], &mut wire);
    let (_, Pushed::Frame(f)) = dec.push(&wire) else {
        panic!("no frame");
    };
    assert_eq!(f.capacity(), MIB);
    assert!(f.iter().all(|b| *b == 3));
}

/// Verifies that `recycle` and `reset` discard buffers that violate retention invariants:
/// allocations exceeding 4 MiB, under-utilized large buffers, buffers offered mid-frame, and
/// buffers cleared during decoder reset.
#[test]
fn the_decoder_drops_a_body_that_it_must_not_keep() {
    const MIB: usize = 1024 * 1024;
    let mut wire = Vec::new();
    encode_frame(b"ab", &mut wire);
    let small = |dec: &mut FrameDecoder, input: &[u8]| match dec.push(input) {
        (_, Pushed::Frame(f)) => {
            assert_eq!(f, b"ab");
            f.capacity()
        }
        other => panic!("no frame: {other:?}"),
    };

    let mut dec = FrameDecoder::new(None);
    dec.recycle(vec![0u8; 8 * MIB]);
    assert!(small(&mut dec, &wire) < MIB);

    let mut dec = FrameDecoder::new(None);
    let mut sparse = Vec::with_capacity(MIB);
    sparse.extend_from_slice(&[1u8; 10]);
    dec.recycle(sparse);
    assert!(small(&mut dec, &wire) < MIB);

    let mut dec = FrameDecoder::new(None);
    assert_eq!(dec.push(&wire[..5]), (5, Pushed::NeedMore));
    dec.recycle(vec![0u8; MIB]);
    assert!(small(&mut dec, &wire[5..]) < MIB);

    let mut dec = FrameDecoder::new(None);
    dec.recycle(vec![0u8; MIB]);
    dec.reset();
    assert!(small(&mut dec, &wire) < MIB);
}

/// Verifies that `body_room` exposes an empty slice outside the payload ingestion phase and that
/// `commit` clamps committed byte counts to the most recent active reservation window.
#[test]
fn commit_takes_only_the_last_room() {
    let mut dec = FrameDecoder::new(None);
    assert!(dec.body_room(100).is_empty());
    assert_eq!(dec.commit(5), Pushed::NeedMore);
    assert!(dec.is_idle());

    assert_eq!(dec.push(&[0, 0, 0, 20]), (4, Pushed::NeedMore));
    let room = dec.body_room(8);
    assert_eq!(room.len(), 8);
    room.fill(1);
    assert_eq!(dec.commit(100), Pushed::NeedMore);
    assert_eq!(dec.want(), 12);
    assert_eq!(dec.commit(100), Pushed::NeedMore);
    assert_eq!(dec.want(), 12);
    let room = dec.body_room(100);
    assert_eq!(room.len(), 12);
    room.fill(2);
    let mut body = vec![1u8; 8];
    body.extend_from_slice(&[2u8; 12]);
    assert_eq!(dec.commit(12), Pushed::Frame(body));
    assert!(dec.is_idle());

    let mut dec = FrameDecoder::new(None);
    assert_eq!(
        dec.push(&[0x80, 0, 0, 0]).1,
        Pushed::Error(FrameError::Negative(i32::MIN))
    );
    assert!(dec.body_room(10).is_empty());
    assert_eq!(
        dec.commit(10),
        Pushed::Error(FrameError::Negative(i32::MIN))
    );
}
