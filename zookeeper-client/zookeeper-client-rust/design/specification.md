<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->


# Rust ZooKeeper client specification

## Overview

This specification details the architecture, protocol semantics, compatibility requirements, and implementation design of a native Rust ZooKeeper client. The codebase delivers two artifacts from a unified Rust core:
1. Binary- and source-compatible drop-in replacements for the legacy `libzookeeper_mt` and `libzookeeper_st` C libraries, allowing existing C and C++ applications to relink or load dynamically without code modifications or behavioural divergence.
2. A native Rust client library (`apache-zookeeper`) exposing an idiomatic asynchronous and synchronous API.

The design objective is achieving memory safety while maintaining functional equivalence with the C client. Because existing applications depend on years of observable client behavior, the C client implementation serves as the definitive reference for wire format, request sequencing, session lifecycles, timer thresholds, watch semantics, return codes, `errno` mappings, callback ordering, and logging formats. However, known defects, undefined behavior, memory leaks, and deadlocks in the C client are excluded from this specification.

Where this document does not specify an intentional bug fix or design departure, the Rust implementation faithfully replicates the C client's semantics while preventing accidental deviations from historical edge cases.

Departures from the legacy C client are restricted to:
- Comprehensive bug fixes addressing data integrity hazards, resource exhaustion, and infinite reconnection loops.
- Defined, fail-safe error handling in place of crashes, memory corruption, and deadlocks.
- Modernized TLS powered by `rustls`, rejecting obsolete ciphers and insecure certificate patterns previously tolerated by OpenSSL.
- Standard linkage and build system adjustments inherent to Rust toolchains.

The existing C client remains intact in the ZooKeeper source tree as a supported build option.

### System Architecture

The Rust client's architecture decouples protocol logic from transport I/O and runtime scheduling. Protocol processing resides in a deterministic state machine that operates without ambient I/O, threads, or direct system clock access. The state machine consumes incoming network buffers, client requests, and discrete millisecond timestamps, producing outbound byte streams, pending application notifications, and next-wakeup deadlines. It centrally manages request queuing, transaction identifiers (xids), session state transitions, keepalive timers, host list rebalancing, watcher registries, authentication handshakes, and graceful connection teardown.

Surrounding the protocol core is an I/O driver abstraction responsible for socket management, TLS handshakes, polling, and timer management. Four distinct driver implementations support the client's deployment models:
- A multithreaded driver providing POSIX thread execution loops for `libzookeeper_mt`.
- A single threaded event loop driver for `libzookeeper_st`.
- A background worker thread driver for the synchronous/threaded native Rust client.
- An asynchronous driver based on `tokio` for native async Rust applications.

A thin C compatibility shim wraps the C-targeted drivers to present the standard `zookeeper.h` ABI, while the native Rust API builds directly atop the driver layer. A session-level compatibility configuration object parameterizes the protocol core, isolating legacy C idiosyncrasies from native Rust sessions.

The codebase is organized as a Cargo workspace located at `zookeeper-client/zookeeper-client-rust`, comprising the following crates:
- `zk-jute`: Data record definitions and serialization codecs generated from the canonical `zookeeper.jute` schema using an updated Jute compiler.
- `zk-proto`: The deterministic protocol state machine, depending solely on `zk-jute`.
- `zk-driver`: Network transport drivers, socket abstractions, and `rustls` integration.
- `zk-client`: The native public Rust API, distributed as the `apache-zookeeper` package.
- `zk-recipes`: Higher-level distributed coordination recipes (locks, leader election, tree cache) packaged as `apache-zookeeper-recipes`.
- `zk-ffi`: The foreign function interface bridge between the C shim and Rust drivers, compiled exclusively into the C compatibility libraries.

To maintain strict modular isolation, `zk-ffi` depends on `zk-driver` rather than `zk-client`, ensuring C libraries remain entirely decoupled from native client APIs.

### Memory Safety

All shipped crates within the workspace enforce `#![forbid(unsafe_code)]`, with the sole exception of `zk-ffi`. Within `zk-ffi`, pointer dereferencing and foreign memory operations are confined to a single rigorously audited module (`abi::raw`). Across the rest of the FFI crate, C pointers are represented as opaque integer identifiers, eliminating unintentional pointer dereferences. Third party dependencies containing `unsafe` blocks, such as `rustls` and its cryptographic providers, are restricted to a vetted and pinned supply chain whitelist.

C code is retained exclusively where required by external ABI contracts or system libraries:
1. Exported C entry points, public structure definitions, and application callback dispatchers, whose layouts and calling conventions are dictated by the legacy C ABI.
2. Cyrus SASL integration, where public types reference `libsasl2` structures. This logic is isolated in two hardened C compilation units.

The C compatibility shim encompasses approximately 5,000 lines of code, migrating all stateful protocol management, buffer handling, and connection orchestration into safe Rust.

### Callback Execution and Stack Safety

Application defined code never executes with a Rust stack frame active on any thread. This constraint encompasses completion callbacks, watcher notifications, authentication callbacks, custom logging hooks, and Cyrus SASL callbacks.

Rust code never invokes foreign application functions directly. Instead, transitions in the protocol state machine yield an ordered queue of pending notifications, which the C shim drains and dispatches only after the Rust execution frame has completely unwound. When protocol execution requires intermediate results that could be influenced by a callback, such as handle closure from within a watcher, the Rust state machine suspends execution at a barrier, returns control to C to invoke the callback, and resumes upon a subsequent driving call. In the multithreaded library, worker threads run as C loops that drive Rust step functions. No Rust-managed threads exist within the C compatibility layer.

This execution boundary guarantees that:
- Re-entrant client invocations, including synchronous operations or `zookeeper_close()` called from within a watcher or completion callback, preserve historical C semantics without lock inversion or stack corruption.
- C++ exceptions or `longjmp` invocations originating inside application callbacks unwind safely without traversing Rust frames.
- POSIX thread cancellation is managed cleanly at defined C cancellation points without corrupting Rust runtime state.

### Document Organization

This specification details the design from the innermost protocol abstractions outward:
1. **The Protocol Core**: Data serialization, state machine design, timer mechanics, host management, and connection lifecycles.
2. **Drivers and I/O**: Sockets, polling engines, execution loops, and TLS abstractions across platforms.
3. **The C Drop-in Libraries**: The ABI shim, handle lifecycle, FFI boundary, memory management, and compatibility shims.
4. **Security**: TLS engine architecture, certificate verification pipelines, Cyrus SASL integration, and credential zeroization.
5. **The Native Rust API**: High-level builder patterns, execution runtimes, asynchronous watches, transactions, and distributed coordination recipes.
6. **Differences from the C Client**: Bug fixes, safety enhancements, behavioral adjustments, and preserved legacy quirks.
7. **Build and Packaging**: Build system integration (Autotools, CMake, Maven, Cargo), symbol visibility, and packaging configurations.
8. **Verification and Delivery**: Conformance testing, fuzzing, property-based testing, performance budgets, and migration sequencing.

## The protocol core

The protocol core is encapsulated within two crates, `zk-jute` and `zk-proto`. These components are strictly isolated from runtime mechanisms such as transport sockets, thread management, monotonic system clocks, TLS engines, Cyrus SASL bindings, and foreign C memory allocators.

### Wire records

Protocol data records are modeled as plain data structures implementing a shared `Record` trait providing `encode`, `decode`, and `encoded_len`. Nullability is represented directly via the wrapper types `JString`, `JBuffer`, and `JVec`, which model optional presence without requiring explicit schemas of nullable fields. A length prefix of `-1` on the wire denotes a null value. Wire decoders process input slices without validating UTF-8 encoding and tolerate trailing data, preserving compatibility with C routines such as `zoo_acreate`, which parses server `Create2Response` payloads into standard `CreateResponse` records. Decode errors map directly to the corresponding POSIX errno constants produced by the C client: `-E2BIG` for buffer truncation, `-EINVAL` for negative string lengths, and `-ENOMEM` for container lengths below `-1`. Preserving these specific error codes is necessary because invalid SASL responses surface decode errors directly through `zookeeper_process`.

Stream framing is handled by `FrameDecoder`, which reconstitutes protocol frames incrementally as bytes arrive. Dynamic buffer allocations scale strictly with inbound byte delivery, preventing malformed frame headers from triggering immediate, oversized memory allocations. The decoder's `want` interface allows the single threaded driver to ingest exactly one frame per invocation of `zookeeper_process`, matching historical C behavior. A frame length of zero triggers a connection teardown returning `ZCONNECTIONLOSS`. In the legacy C client, a negative frame length induces an allocation failure loop that spins until the receive timer fires `ZOPERATIONTIMEOUT`. The Rust drop-in core produces the identical timeout return code while suspending decoder polling and clearing read interest, eliminating busy waiting. The native client handles negative frame lengths by disconnecting immediately and enforcing an upper bound on frame sizes.

Connection handshakes utilize a fixed layout codec. A `ConnectRequest` comprises a 45-byte payload inside a 49-byte frame, byte-identical to the C client's representation. A `ConnectResponse` occupies 37 bytes, or 36 bytes when communicating with older servers that omit read-only flags. Any alternative frame size results in connection teardown with `ZCONNECTIONLOSS`. Multi-transaction responses decode dynamically according to the individual operation type declared in each sub-response header, contrasting with the C client's reliance on local operation tracking. This difference is critical for container creation requests, where server responses include a `Stat` structure that the legacy C client does not consume.

### The state machine and driver interaction

Session lifecycle and protocol state are governed by `Connection<T>`, an isolated state machine instantiated per session. `Connection<T>` serves as the authoritative owner of all mutable session data, eliminating race conditions present in the C client between application threads updating host lists and I/O worker threads traversing them. Process-global variables in the C client, specifically the transaction identifier (xid) counter and cached read-write server addresses, are encapsulated within individual session instances.

The generic parameter `T` defines opaque token types managed by the state machine without internal inspection. Request completion tokens enter the core exactly once and exit upon completion or teardown. Watcher registrations require only cloning and equality comparison. Within the C drop-in libraries, tokens are represented as integer-cast pointers, and watchers are tracked as function-pointer and context pairs using C deduplication semantics. In the native client, tokens map to asynchronous result channels and watcher identifiers. All internal arithmetic uses checked or wrapping operations to match C wrapping behaviors without triggering runtime panics.

The state machine advances deterministically based on explicit millisecond timestamps provided by the driver. Configuration includes injected hooks for random number generation (`Rng`), log filtering (`LogFilter`), and default watcher inspection (`DefaultWatcher`). Because these hooks execute under the driver's internal session lock, their implementations must not block or invoke user application code.

```rust
impl<T: Tokens> Connection<T> {
    pub fn new(cfg: Config, compat: Compat, mode: DriverMode, rng: Box<dyn Rng>,
               log: Box<dyn LogFilter>, default_watcher: Box<dyn DefaultWatcher<T::Watcher>>,
               now: Millis) -> Self;

    // Driver iteration cycle, mapping to zookeeper_interest
    pub fn begin_interest(&mut self, now: Millis) -> Result<(), i32>;
    pub fn poll_action(&mut self, now: Millis) -> Option<Action>;
    pub fn finish_interest(&mut self, now: Millis) -> InterestOutcome;

    // Transport I/O operations
    pub fn handle_read(&mut self, bytes: &[u8], now: Millis) -> ReadOutcome;
    pub fn poll_transmit(&mut self, out: &mut Vec<u8>, max: usize) -> usize;
    pub fn handle_transmitted(&mut self, n: usize, now: Millis);

    // Application requests
    pub fn submit(&mut self, req: Request<T>, now: Millis)
        -> Result<Submitted<T>, SubmitError<T>>;

    // Outbound notifications and continuations
    pub fn poll_output(&mut self) -> Option<Output<T>>;
    pub fn resume(&mut self, work: Stopped, now: Millis);
}
```

The core provides corresponding `handle_*` methods for reporting action outcomes, managing administrative commands (`add_auth`, `set_servers`, `begin_close`, `queue_close_op`, `destroy`), and querying session state.

Drivers execute an iteration by invoking `begin_interest`, draining all pending actions via `poll_action` without holding locks, and concluding with `finish_interest`. When the session enters an unrecoverable state, `begin_interest` returns `ZINVALIDSTATE` and emits deadline warnings as required. `poll_action` yields operations (`Resolve`, `Connect`, `ProbeRw`, `CloseTransport`) in C-compatible order: DNS resolution precedes connection initiation, followed by internal timer evaluation which may trigger probes or closures. Transport closure actions strictly precede subsequent connection attempts. `finish_interest` calculates the active interest mask, file descriptor, and timeout values. If the legacy C client would return prior to updating caller variables, the shim leaves the output variables unmodified. When a connection timeout or read-write transition triggers socket closure within the current step, the driver reports no active descriptor, whereas the legacy client returned the recently closed descriptor.

Drivers also query `poll_action` following `handle_read`, after SASL steps, and following connection teardowns. This ensures SASL negotiations and transport shutdowns execute within the driving iteration that received the triggering frame.

Write interest calculation varies by driver mode. The single threaded driver reproduces C interest semantics precisely: write interest is asserted when queued frames exist during connected or SASL states, when a TCP connection is pending, or when an unwritten `ConnectRequest` remains queued. Native and multithreaded drivers assert write interest only when outbound bytes are available via `poll_transmit`.

Inbound frames are processed sequentially by `handle_read`. Parsing halts at execution barriers, during in-flight SASL operations, or when the frame decoder stalls. If called when no transport exists, `handle_read` consumes nothing and yields `ZINVALIDSTATE`. Outbound frames are serialized into driver buffers via `poll_transmit`, which pins referenced frames until acknowledged by `handle_transmitted`.

Application-bound events are retrieved in strict sequence via `poll_output`. These outputs include:
- `Completion`: Operation results carrying tokens, routed either to an asynchronous completion queue or directly to synchronous waiter threads.
- `Watch` and `Session`: Watcher notifications ordered according to registration and event dispatch rules.
- `Auth`: Status updates for `zoo_add_auth` invocations.
- `ClientId`: Notifications of session adoption.
- `Log`: Structured log entries ordered synchronously with corresponding callbacks.
- `DrainPoint`: Synchronization points signaling the single threaded driver to drain pending completion queues.

Log entries preceding callback dispatch (such as watcher invocation notices and chroot mismatch errors) are transported within output records as `delivery_logs`, ensuring thread affinity and chronological ordering match the C client. In drop-in mode, watch events are emitted even when watcher lists are empty to preserve delivery-phase log messages.

When connection teardown runs application callbacks, subsequent teardown steps must observe any state modifications made by those callbacks. The core accomplishes this by suspending execution at barrier outputs, returning a `Stopped` continuation handle that the driver subsequently passes to `resume`. Barriers include direct completions, pending authentication completions, `AuthLockpoint` synchronization hooks, and single threaded session dispatches. Each suspended operation maintains an isolated continuation tied to the initiating call. In the multithreaded library, concurrent invocations of `zookeeper_close` proceed independently without deadlocking against active I/O steps. While a teardown remains suspended and unabandoned, foreign driving calls return `ZINVALIDSTATE` without draining queues, preventing re-entrant state corruption.

### Session establishment and states

The public `state()` query returns values matching the C client's representation, while internal logic is driven by explicit phases: idle, TCP connecting, TLS handshaking, TLS configuration failure, associating, SASL negotiation, and established. The external state starts at 999. Recoverable teardowns transition to state 0 (including during reconnection delays), whereas session callbacks during teardown observe state 3 or 5, reflecting the legacy behavior where state 0 is assigned only after failing active queues.

Active TCP connection attempts report state 1 (or state 7 under TLS). Immediate connections skip intermediate reporting. Without TLS, the state transitions to 2 under a single lock acquisition; with TLS, the state remains at 0 or 999 throughout the handshake, matching C's inline blocking handshake behavior.

Connection endpoint selection adheres to historical precedence: pending reconnect delays are cleared, logging a connection delay notice and resetting keepalive timestamps. When ready, the client connects to a known read-write server if established, or advances through the resolved host list. An exhausted, unresolvable list triggers connection teardown with `ZSYSTEMERROR`.

Once the transport connects, the core enqueues a `ConnectRequest` as a priority frame and transitions to state 2. The session ID sent is either the established ID (if previously connected to a read-write server or supplied at handle initialization) or 0. The timeout field reflects the negotiated session duration after initial establishment.

Upon receiving a `ConnectResponse`, the core verifies session continuity. If an established non-zero session ID changes unexpectedly upon reconnecting to a read-write server, the state transitions to -112, triggering teardown with `ZSESSIONEXPIRED` and `ESTALE`. Sessions observed solely through read-only servers permit silent ID reassignment, preserving C compatibility. Valid responses adopt negotiated timeouts, session credentials, and initiate SASL handshakes or finalize session establishment.

Finalizing session establishment transitions the state to 5 or 3, clears reconnection flags, enqueues `SetWatches` frames, clears authentication lockpoints, queues credentials, and delivers the connected session event. Because frame insertion prepends to the pending send queue, the physical wire order delivers authentication packets in reverse registration order, followed by `SetWatches`, and finally queued client requests.

TLS handshakes in the core operate in a nonblocking manner with an upper deadline of two-thirds of the session timeout from TCP completion, terminating with `ZOPERATIONTIMEOUT` and `ETIMEDOUT` upon expiration. Decrypted plaintext arriving prior to handshake failure is fully processed before tearing down the transport.

### Requests, transaction identifiers, and replies

The `submit` interface operates atomically under the driver lock: it executes argument validation, assigns a transaction identifier (xid), serializes the frame, and enqueues the request into both the send queue and the pending reply queue. Wire order strictly mirrors xid order. Errors returned during submission map directly to standard C client return codes, returning ownership of completion tokens to the caller.

Drop-in request validation follows a strict sequence: TTL parameter validation, chroot and path syntax verification, session state checks, multi-transaction scans, local watch removal checks, and frame serialization. Multi-requests validate state per sub-operation; unsupported operations immediately yield `ZUNIMPLEMENTED`, taking precedence over preceding validation errors. Local watch removals bypass state checks and complete synchronously within the submission call. Serialization failures and operations submitted against closing handles yield `ZMARSHALLINGERROR`.

Path validation implements the canonical logic of `isValidPath`. When a chroot is configured, relative paths (including empty paths) and sequential creations directly on `/` return `ZBADARGUMENTS` prior to network transmission. Valid paths are prefixed with the chroot byte-for-byte. The core strips chroot prefixes from server responses only along `/` component boundaries; mismatched prefixes (such as `/app2/x` under `/app`) remain unstripped and emit diagnostic warnings. Config-node watches on `/zookeeper/config` bypass chroot prefixing.

Each session instance maintains an independent xid counter, seeded from `time(0)` in drop-in mode and initialized to 1 in native mode. Counters wrap from `i32::MAX` to 1, reserving non-positive values (-1, -2, -4, -8) for protocol control frames. Failed submission attempts do not consume xid values.

The send queue buffers outbound frames, while the pending queue tracks operations awaiting server responses. Standard requests, ping heartbeats, and close requests append to the tail. Priority frames (Connect, SASL, authentication, SetWatches) insert ahead of unstarted, unpinned frames, ensuring multi-frame transmissions are never bifurcated. Frames containing sensitive credentials are zeroized in memory upon completion or teardown. Established sessions permit all transmissions; SASL phases restrict transmission strictly to SASL exchanges; associating phases permit only `ConnectRequest` frames.

Every inbound frame refreshes the `last_recv` keepalive timestamp. Frames received during SASL negotiation are routed directly to the mechanism handler without xid validation. Otherwise, frames smaller than 16 bytes trigger connection loss. Unsolicited responses matching reserved xids (such as heartbeat and SetWatches acknowledgments) are consumed and discarded. All other responses must match the xid at the head of the pending queue.

If an unexpected reply arrives when the pending queue is empty or unwritten, the frame is dropped with `ZINVALIDSTATE` during close sequences, or triggers `ZRUNTIMEINCONSISTENCY` during normal operation. Mismatched xids immediately terminate the connection with `ZRUNTIMEINCONSISTENCY`. On valid matches, the core updates `last_zxid` for positive response zxids, arms or disarms associated watches, and dispatches completion events.

### Watcher tables

Watches arm upon receiving successful operation responses. The iteration order of registered watchers is observable when session state events fan out to all tables and when `SetWatches` frames are constructed on reconnect. Because historical C applications depend on hash-table traversal ordering, the drop-in library implements a memory-safe port of the C client's `hashtable.c` and `zk_hashtable.c`:
- Hashing uses the DJB2 algorithm over `unsigned int`, accumulating characters matching platform-specific signedness rules.
- Hash table capacity begins at 53 buckets (from prime table allocation), with a load factor threshold of `ceil(size * 0.65f)`.
- Hash collisions prepend elements to bucket chains. Table resizing traverses old buckets sequentially, prepending entries to new chains and reversing relative order.
- Watcher removal unlinks matching nodes, while iteration traverses buckets sequentially and chains from head to tail.

Delivery ordering reproduces C's deduplication semantics: watchers are gathered in collection order, deduplicated preserving first occurrences, and dispatched in reverse order. Node creation and modification events notify existence watchers before data watchers; node deletion events notify child watchers, existence watchers, and data watchers in order. Path watchers are unregistered upon event receipt, creating an observable window where `has_watcher` queries evaluate to false while delivery remains pending. Session events collect the default session watcher first, followed by all path watchers in iteration order, resulting in the default watcher executing last after deduplication.

On reconnection, `SetWatches` packets transmit the current `last_zxid` and all active watch paths in iteration order. To prevent large watch registrations from exceeding the server's `jute.maxbuffer` threshold, the core fragments registrations into packets bounded at approximately 128 KiB, mirroring the Java client. Packets are enqueued in reverse order so they reach the wire sequentially ahead of standard queued requests.

The native client diverges from these legacy behaviors: it utilizes deterministic insertion-ordered tables, supports modern persistent and recursive watch modes, does not broadcast session lifecycle events to path watchers, clears registrations upon terminal session expiration, and issues opcode 105 (`SetWatches2`) when persistent watches are active.

### Authentication sequencing

The `add_auth` interface appends credentials to an append-only, non-deduplicating list. When connected (or associating without SASL), the core enqueues an authentication frame as a priority insert; otherwise, credentials are recorded for transmission upon subsequent connection establishment. During association, re-establishment resends all registered credentials, causing pending entries to transmit twice.

Inbound authentication responses acknowledge frames sequentially. The core records outbound authentication packets upon transmission, ensuring responses match wire order. Each reply completes the oldest pending authentication token and records the scheme name. If an authentication exchange yields a non-zero error, the session state transitions to `AUTH_FAILED`, enqueues an authentication failure event, and terminates the connection with `ZAUTHFAILED`.

To replicate the legacy client's synchronization semantics, the core inserts an `AuthLockpoint` barrier at the four execution points where C acquires the authentication mutex: handling authentication responses, failing queues during teardown, executing handle close, and resending credentials during connection establishment. The C shim acquires and releases the actual POSIX mutex without active Rust frames on the stack, allowing external applications holding `zoo_lock_auth` to serialize authentication processing as expected.

SASL state management is embedded within the core protocol machine, while mechanism execution remains decoupled. The core emits `Sasl` actions (`Start`, `Step`, `Dispose`) tagged with 32-bit generation counters. The C drop-in executes Cyrus SASL within dedicated C modules, while the native client delegates to a `SaslSession` trait implementation. Generation counters advance on each initiation and teardown, preventing delayed responses from influencing subsequent connections.

SASL negotiations adhere to specific wire behaviors:
- SASL frames use opcode 102 with standard xid assignments, inserted at priority boundaries.
- Empty initial tokens transmit as length 0.
- Outbound frames are transmitted only when the mechanism requires continuation or produces a non-empty token; mechanisms completing with empty tokens stall until the receive timeout fires.
- Non-empty initial DIGEST-MD5 responses are suppressed to accommodate historical JDK server behaviors.
- GSSAPI negotiations conclude upon receiving the final server packet without invoking additional mechanism steps.

When non-GSSAPI mechanisms complete with an outbound token, the core transmits the token, awaits the server's acknowledgment, and only then finalizes establishment, correcting a C client defect where SetWatches preceded final SASL tokens.

### Timers and keepalive pings

Keepalive scheduling derives from the negotiated session timeout $T$, evaluated using 64-bit millisecond arithmetic to prevent signed integer overflow. Timeouts smaller than 3 milliseconds expire immediately.

Connection liveliness enforces two primary timer thresholds:
- **Receive Timeout**: When elapsed idle receive time reaches $\frac{2}{3}T$, the connection terminates with `ZOPERATIONTIMEOUT` and `ETIMEDOUT`. This timeout applies equally to TCP connection phases under TLS (state 7), closing a gap in the legacy client.
- **Send Timeout / Keepalive**: When connected and elapsed idle send time reaches $\frac{1}{3}T$, a ping request is queued if the pending reply queue is empty. Idle send timers reset only upon outbound frame flushes, ensuring heartbeats transmit immediately once the pending queue drains.

Calculated timeout values reported to external callers match legacy C formulas, bounded by active TLS handshake deadlines.

### Host resolution, DNS, and failover mechanics

Host management coordinates the primary server list, active cursor positions, rebalancing lists (`old` and `new`), and reconnect passes. Host string resolution executes at initialization, during `zoo_set_servers`, upon connection loss, and periodically while connected according to the configured resolution delay.

Drivers perform `getaddrinfo` lookups without holding session locks, streaming resolved endpoints into `handle_host_resolved` to preserve log order. Each resolution cycle tracks a generation counter representing the active server configuration string; results matching stale configuration strings are discarded. If resolution yields no valid endpoints and an existing server list is available, the client retains the existing list, allowing connection retries and keepalive timers to proceed uninterrupted.

Successful resolutions shuffle endpoints using ported `addrvec_shuffle` logic. In the drop-in client, libc pseudo-random generators (`srandom`, `srand48`) are reseeded prior to shuffling to match C behavior, whereas the native client employs an isolated cryptographically secure random source.

Rebalancing implements the `update_addrs` algorithm using identical double-precision floating-point arithmetic. Server lists are considered identical when their sizes match and bidirectional set containment holds (evaluating both IP address and port). When lists match, existing connections remain undisturbed. If membership changes, new server lists are committed and active sockets undergo forced closure.

Connection failover fixes a critical defect in the legacy client:
- In the legacy C client, server cursor advancement occurs upon failure reporting and again during the subsequent connect attempt, skipping alternate servers and preventing half the ensemble from being reached in even-sized deployments.
- The Rust core inspects candidate servers without advancing the cursor prematurely, advancing exactly once per connection attempt. Consequently, two-node ensembles alternate reliably (A, B, A, B), and full passes over the server list trigger a standardized reconnect delay equal to one-sixtieth of the session timeout.

### Read-write probing and mode switching

When connected in read-only mode, the client periodically attempts to locate a read-write server. When the idle probe timer expires, the core emits a `ProbeRw` action targeting the endpoint at the current cursor without advancing the main list. The probe interval starts at 200 milliseconds, doubling on each attempt up to a maximum of 60 seconds, and resets upon reconnect.

The driver executes probes inline using a nonblocking 10-byte transaction verifying the canonical `isro` response. On failure, the cursor advances without altering the active read-only connection. On success, the probed endpoint is recorded for immediate reconnection, and the active read-only session is torn down.

Unlike the legacy C client, which executed this teardown via `cleanup(zh, ZOK)`—falsely reporting successful completion for unexecuted operations—the Rust core fails all pending in-flight requests and authentication tokens with `ZCONNECTIONLOSS`, preventing silent data corruption.

### Connection teardown

Connection teardown executes as a structured sequence capable of suspending at application barriers:
1. Emission of diagnostic socket error logs.
2. Transport closure, SASL generation advancement, receive buffer reset, and handshake deadline cancellation (retaining persistent mechanism states).
3. Emission of session state events (`CONNECTING` for recoverable drops, or specific negative terminal states).
4. Cancellation of queued and in-flight requests: pending send and reply queues are drained, failing operations with appropriate error codes, dropping watches, and clearing authentication lockpoints.
5. Transition to state 0 (or terminal negative states) and emission of `DrainPoint` markers.
6. Execution of host failover cursor advancement.

Pending operations receive error codes reflecting the underlying teardown cause: `ZCONNECTIONLOSS` for transport I/O drops, `ZOPERATIONTIMEOUT` for expired timers, `ZSSLCONNECTIONERROR` for TLS failures, `ZRUNTIMEINCONSISTENCY` for protocol violations, `ZSESSIONEXPIRED` for expired sessions, and `ZAUTHFAILED` for authentication rejections.

### Forced closure

Forced closures occur during server list rebalancing or upon send buffer flush failures in single threaded mode. The Rust core converts these events into clean teardowns:
- Within the triggering call, the state transitions to 999, the transport is marked for closure, SASL generations advance, and currently pending work is captured.
- Callbacks do not execute synchronously within the triggering API call.
- The subsequent driving step drains and fails captured work with `ZCONNECTIONLOSS` and delivers a `CONNECTING` event.
- Requests submitted subsequent to the trigger remain safely buffered for transmission over the next established connection.

If a forced close intersects an in-progress authentication response or session establishment handshake, execution proceeds predictably: rejected authentications override state 999 with `AUTH_FAILED`, ensuring terminal failures are never masked.

### SASL failure semantics

Every SASL authentication failure triggers an immediate, comprehensive session teardown mirroring digest authentication rejections:
- The session transitions to `AUTH_FAILED`.
- An `AUTH_FAILED` session event is dispatched.
- The transport socket is closed immediately.
- All pending synchronous, asynchronous, and authentication requests fail with `ZAUTHFAILED`.
- multithreaded I/O threads terminate while completion threads remain active to deliver queued events.

Subsequent operations and driving calls return `ZINVALIDSTATE`. This resolves a severe C client defect where SASL failures left sockets open and worker threads deadlocked indefinitely.

### Session closure

Drop-in session closure accommodates legacy reference-counting semantics across three functional stages:
- `begin_close`: Invoked when external references remain. Fails pending requests and authentication tokens with `ZCLOSING` without emitting session events. Unsent frames are flushed before socket shutdown to ensure final administrative requests reach the wire.
- `queue_close_op`: Enqueues an explicit close request frame if the connection remains active.
- `destroy`: Terminates the transport, disposes of active SASL sessions, and releases driver resources.

The native Rust client implements a clean draining model: `close()` rejects new submissions, flushes pending requests, awaits server acknowledgment of the close frame up to a configured timeout, and terminates the session cleanly.

### Compatibility policies and operational modes

Behavioral differences between the drop-in C libraries and the native Rust client are governed by an immutable `Compat` configuration established at session creation. Bug fixes and safety repairs apply unconditionally across all configurations.

The `Compat` policy configuration distinguishes the two operational targets across the following functional areas:
- **Authentication Resend**: Drop-in mode resends authentication frames in reverse registration order at the head of the stream to match C wire order; native mode resends credentials in forward chronological order.
- **Association Duplication**: Drop-in mode duplicates authentication packets submitted while associating; native mode records entries without duplicate transmissions.
- **Watcher Table Implementation**: Drop-in mode uses a ported DJB2 hash table preserving historical bucket traversal order; native mode uses deterministic insertion-ordered maps.
- **Session Event Fanout**: Drop-in mode replicates C's dispatch of session events to all registered path watchers; native mode delivers session events strictly to dedicated session watchers.
- **Watch Retention on Expiry**: Drop-in mode retains registered watches across session expiration; native mode clears watch tables upon terminal expiration.
- **Protocol Operations**: Drop-in mode disables modern opcodes; native mode enables advanced ZooKeeper operations, including persistent recursive watches and identity inspection (`who_am_i`).
- **Maximum Frame Size**: Drop-in mode enforces no arbitrary client-side frame limit; native mode enforces a default 4 MiB inbound frame cap.
- **Malformed Frame Handling**: Drop-in mode stalls the decoder until the operation timeout triggers; native mode immediately terminates the transport.
- **Session Expiry Conditions**: Drop-in mode recognizes expiry upon session ID reassignment; native mode additionally expires sessions if the negotiated timeout is non-positive.
- **DNS Failure Resilience**: Drop-in mode surfaces resolver error codes; native mode retains the last known healthy address list.
- **Resolution Cadence**: Drop-in mode attempts resolution on every iteration while disconnected; native mode resolves per pass, on a configured interval, or statically.
- **Reconnection Backoff**: Drop-in mode employs a single-shot delay equal to one-sixtieth of the session timeout; native mode applies exponential backoff with jitter.
- **Handshake Timeout**: Drop-in mode bounds the handshake by the negotiated session timeout; native mode bounds by the requested timeout.
- **Keepalive Ping Scheduling**: Drop-in mode suppresses heartbeats while requests remain outstanding; native mode issues periodic pings regardless of queue depth.
- **Close Semantics**: Drop-in mode fails pending work immediately with `ZCLOSING`; native mode gracefully drains in-flight requests before shutdown.
- **Chroot Path Validation**: Drop-in mode emulates C byte-level path joining; native mode validates and applies chroots strictly along logical path component boundaries.
- **Disconnect Return Codes**: Drop-in mode returns specific cause-derived error codes; native mode normalizes recoverable disconnects to `ZCONNECTIONLOSS`.
- **TLS Verification**: Drop-in mode validates certificate chains without hostname verification; native mode verifies both certificate chains and hostnames.

Driver execution modes are further configured via `DriverMode`, which governs write-interest calculation rules and determines whether session notifications are delivered as inline barriers (in single threaded mode) or dispatched to an asynchronous completion queue.

## Drivers and I/O Architecture

The `zk-driver` crate implements network transports, TLS session management, I/O multiplexing, inter-thread notification channels, and time abstraction. It operates strictly as an execution harness for the protocol core: it carries out I/O actions demanded by the core state machine without making autonomous protocol decisions. All driver code enforces `#![forbid(unsafe_code)]` and relies on `rustix` (configured with its libc backend) for direct system call invocation. Diagnostic output is emitted exclusively as structured log records through the protocol core, preserving strict chronological ordering with operational events.

All four driver implementations share a common execution step pipeline.

### Outbox management and synchronization barriers

Every thread or task driving the protocol core manages an isolated `Outbox` buffer. In the C drop-in libraries, this outbox is represented by a lightweight handle resident on the C call stack. During each execution step, the driver invokes the core under the session lock, transfers all generated outputs into its local outbox, and immediately releases the lock. Outbox buffers are strictly thread-confined; no execution thread accesses or mutates another thread's queued items.

The C shim drains outbox items iteratively, executing corresponding actions within pure C stack frames. Rust execution frames exist exclusively during the item extraction phase (`zkrs_outbox_next`), guaranteeing that application callbacks never execute with Rust frames active on the stack.

Execution steps yield one of four outcomes defined by the FFI boundary (`ZKRS_DONE`, `ZKRS_MORE`, `ZKRS_RELEASE`, `ZKRS_EXIT`). When a step encounters an operational barrier, its continuation is suspended and preserved within the outbox until resumed.

Execution barriers are established at the following synchronization points:
- Synchronous request completion within the I/O loop or close sequence, ensuring synchronous waiters are signaled before subsequent wire frames are processed. Multiple contiguous synchronous completions coalesce into a single barrier without altering observable event order.
- Completion of authentication callback batches.
- Authentication lockpoints matching the exact positions where the legacy C client acquires its internal authentication mutex.
- SASL negotiation initiation (`Start`) and step transitions (`Step`), where Cyrus SASL routines execute in C and return via `zkrs_sasl_result`.
- Session state transitions and completion queue drain points within the single threaded driver.
- Thread join and detach milestones, ensuring subsequent operations execute only after thread state settles.
- Pre-blocking pauses with pending diagnostic log records, ensuring log output flushes on the originating thread prior to blocking.

While a driver step is suspended at a barrier, client invocations originating from active callbacks operate on current session state. Consequently, if a session watcher triggers `zookeeper_close()`, pending requests fail with `ZCLOSING` rather than `ZCONNECTIONLOSS`, matching legacy semantics. A suspended step halts further socket reading. Subsequent wire data remains buffered within the transport layer for processing upon resume.

Notifications destined for the multithreaded completion thread are transferred to a thread-safe completion queue during extraction. When multiple threads generate notifications concurrently, delivery ordering matches the order of outbox extraction. Locks are never held across step completions or outbox extractions.

### Shared driver execution pipeline

Transport state and staging buffers are unified within `IoCore`, which maintains the active transport state (idle, TCP connecting, plaintext, TLS, or parked), a 64 KiB heap read buffer, unconsumed decrypted plaintext, and a staging buffer for outbound frames. Driver loops execute two core transitions: `interest_step` (the foundation of `zookeeper_interest`) and `process_step` (the foundation of `zookeeper_process` and event dispatch). multithreaded I/O loops sequence these steps across interest calculation, socket polling, and network processing.

When a session enters an unrecoverable state, `interest_step` returns `ZINVALIDSTATE` while leaving caller output variables undisturbed. It processes any remaining phases of pending forced closures, immediately terminating condemned sockets before invoking application callbacks. Active interest masks are calculated based on transport state: asserting write interest when TLS buffers or staging queues hold unsent data, suppressing read interest when frame decoding stalls, and forcing a zero timeout when complete plaintext frames await consumption.

`process_step` handles transport I/O readiness:
- Upon completing nonblocking TCP connections, it inspects socket errors via `SO_ERROR` and returns immediately without consuming data.
- During TLS handshakes, it advances one cryptographic flight.
- Upon write readiness, it executes a single flush pass: staging the head frame (pinned by the core), releasing the session lock, transmitting to the socket until blocking, re-acquiring the lock, and acknowledging transmitted byte counts.
- Upon read readiness, incoming bytes are offered to the protocol core. Incomplete frames yield `ZNOTHING` without dispatching completions, while negative frame lengths stall the decoder and clear read interest.

### multithreaded driver design (`libzookeeper_mt`)

The multithreaded compatibility driver manages two POSIX threads per session handle: an I/O worker thread and a completion dispatch thread. Threads are spawned by the C shim with standard default attributes. Thread roles and associated session handle pointers are tracked in thread-local storage, allowing foreign threads to be recognized and handled safely during close operations and synchronous checks.

The I/O thread repeatedly executes `zkrs_io_step` until receiving `ZKRS_EXIT`. Each iteration checks for pending close requests, runs `interest_step` under the I/O lock, suspends if outbox items require draining, polls the waker channel and transport socket, and advances `process_step`. Upon encountering an unrecoverable session state, the I/O thread signals waiters, publishes queued events, and terminates its execution loop. When interest calculations produce no immediate actions, the loop sleeps on the waker channel bounded by one-sixtieth of the session timeout.

The completion thread processes queued completions in contiguous batches, exiting only after a close request is issued, the I/O thread has terminated, and the completion queue has completely emptied. This ensures completion threads remain operational following session expiration or authentication failure until explicit handle destruction.

Session closure coordinates thread lifetimes based on the calling thread's identity:
- **Foreign Application Thread**: The caller signals and joins the I/O thread, notifies and joins the completion thread (allowing all queued events to drain), and executes final session destruction.
- **Completion Thread Callback**: The callback thread joins the I/O thread and detaches itself. Final cleanup runs on the completion thread following callback return.
- **I/O Thread Callback**: The callback thread detaches itself, marks itself terminated, and joins the completion thread. Final cleanup executes on the I/O thread, dropping subsequent notifications.

In all scenarios, `zookeeper_close()` returns `ZOK`. Final cleanup executes exactly once upon the release of the last reference, flushing pending requests for up to 3,000 milliseconds, waiting up to 1,500 milliseconds for transport quiescence, sending TLS `close_notify` alerts where applicable, and destroying session resources.

To prevent deadlocks following `fork()` without `exec()`, the multithreaded driver records the parent process ID upon creation. When invoked within a forked child process, `zookeeper_close()` bypasses thread joining, suppresses network transmissions, executes no application callbacks, closes accessible file descriptors, and intentionally leaks handle memory to prevent terminating the parent's remote session.

### single threaded driver design (`libzookeeper_st`)

The single threaded compatibility driver operates without background threads or wake pipes. A single mutex serializes access across the protocol core, `IoCore`, and a local completion queue, unlocking only during blocking DNS resolution and TLS certificate loading to ensure memory safety under accidental cross-thread usage.

I/O processing adheres to strict batching limits: driving calls process at most one connection event and execute a single flush pass per invocation. On plaintext connections, the driver reads precisely the byte count requested by the frame decoder, processing at most one complete frame per driving call.

Asynchronous completions, watch notifications, and session state transitions buffer on an internal handle queue. Completions drain at explicit points corresponding to C's `process_completions`: the driving step halts at a `DrainPoint` barrier, allowing the C shim to dispatch queued items sequentially until exhausted. Authentication completions dispatch inline, and teardowns conclude with a final queue drain.

Asynchronous API invocations flush outbound buffers immediately following submission, preserving C's behavior where serialization errors in multi-requests do not prevent prior valid submissions from transmitting. A flush failure initiates a forced closure within the submitting call, deferring notification dispatch to the subsequent driving step.

### Native driver implementations

The native client provides two driver architectures optimized for Rust execution environments:
- **Threaded Driver**: Runs a dedicated event loop on an isolated `std::thread` with configurable stack attributes. It routes protocol notifications directly into caller result slots, asynchronous watcher channels, and session state watchers via a zero-allocation `RouteSink` abstraction. Background DNS resolution executes on a dedicated short-lived worker thread, preventing slow name resolution from impeding keepalive pings or socket reads.
- **Asynchronous Tokio Driver**: Implemented under the `tokio` feature flag for Unix platforms. It integrates transport sockets via `tokio::io::unix::AsyncFd` and drives the core state machine within a spawned asynchronous task using a nonblocking `poll_fn` loop. DNS resolution, SASL mechanism steps, and filesystem certificate loading offload to `tokio::task::spawn_blocking`. Submissions execute synchronously up to frame staging, ensuring xid allocation and wire transmission orders are deterministic.

Both native drivers guarantee nonblocking event routing. One-shot watchers and operation futures deliver directly into bounded channels, while persistent watcher streams support configurable backpressure buffering (`persistent_watch_buffer`).

### Socket abstractions, polling, and wakeups

Network sockets are configured strictly in nonblocking mode. In the drop-in drivers, `SOCK_CLOEXEC` is applied conditionally based on the build-time `--with-sock-cloexec` setting. Native drivers unconditionally configure close-on-exec on all sockets, probe channels, and waker pipes (using `fcntl` on platforms lacking `SOCK_CLOEXEC`, including macOS). All outbound transmissions suppress broken-pipe signals using `MSG_NOSIGNAL` or `SO_NOSIGPIPE`.

Event polling translates socket hangup conditions (`POLLHUP`) to write readiness, matching `do_io` semantics. Poll errors (`POLLERR`) and invalid descriptor indicators (`POLLNVAL`) map to combined read/write readiness, allowing subsequent I/O system calls to capture and report specific error codes.

Thread wakeup uses an asynchronous, nonblocking pipe. Wakeup events are coalesced via an atomic boolean flag: only transitions from false to true trigger byte writes to the pipe, preventing pipe buffer saturation. The I/O thread drains the pipe and clears the flag prior to evaluating core state under the lock, ensuring wakeups are never lost.

Read-write server probes run inline within the driver loop against endpoints designated by the core. Probes enforce strict nonblocking deadlines of one second each for TCP connection, TLS negotiation, and probe exchange (`isro`). Probes terminate their sockets unconditionally upon completion, ensuring probe failures never disrupt the primary session transport.

To accommodate platforms with constrained default thread stacks (such as musl libc with 128 KiB stacks), driver crates restrict stack allocations to a maximum of 4 KiB, allocating I/O buffers and TLS engines on the heap.

### TLS transport integration

TLS communication is managed via `rustls::ClientConnection`, abstracted behind the `TlsConn` trait to give the driver complete control over ciphertext transmission and plaintext buffering:

```rust
pub trait TlsConn: Send {
    fn is_handshaking(&self) -> bool;
    fn has_ciphertext(&self) -> bool;
    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure>;
    fn feed_eof(&mut self) -> Result<(), TlsFailure>;
    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure>;
    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure>;
    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize;
    fn send_close_notify(&mut self);
}
```

The TLS engine latches fatal errors, protocol alerts, and EOF conditions without discarding previously decrypted plaintext. `read_plaintext` returns all successfully decrypted application data before subsequent calls yield the latched error. A fresh TLS session is constructed for each connection attempt and read-write probe without holding driver locks.

Handshake negotiation begins upon TCP connection establishment, triggering the protocol core's handshake deadline. Handshake flights advance incrementally across polling wakes. Inbound reads process up to 64 KiB of ciphertext per iteration, dispatching all complete plaintext frames prior to handling latched connection errors. Outbound application data is acknowledged to the protocol core only after the underlying kernel socket has accepted all corresponding ciphertext.

### Windows platform support

Windows deployment supports both drop-in drivers and the native threaded driver (the tokio driver is restricted to Unix due to `AsyncFd` dependencies). C-facing file descriptors are modeled as `intptr_t` to accommodate 64-bit `SOCKET` types. Winsock initialization (`WSAStartup`) and cleanup (`WSACleanup`) execute per handle, matching C client behavior. The multithreaded shim creates worker threads via `_beginthreadex`.

The waker mechanism utilizes a loopback TCP socket pair. The driver validates that the accepted peer connection matches the connecting socket, mitigating local cross-process connection hijacking.

I/O polling maps to `rustix::event::poll` (wrapping `WSAPoll`). To replicate C's nonblocking connect timeout semantics under Winsock—where failed connects report neither read nor write interest—the driver periodically queries socket error status via `SO_ERROR` every one-third of the session timeout.

## The C Compatibility Layer

The C drop-in compatibility libraries deliver drop-in replacements for `libzookeeper_mt` and `libzookeeper_st` by coupling the safe Rust protocol engine and drivers with a specialized C shim. This architecture preserves the external ABI and behavioral contract of the historical C client while executing stateful protocol logic and memory handling within safe Rust. Transport I/O and protocol state machines remain strictly decoupled from application code, and application callbacks execute exclusively outside Rust stack contexts.

### Binary compatibility and ABI stability

Existing applications compiled against the canonical ZooKeeper C client run against the drop-in libraries without recompilation or relinking:
- Installed public headers (`zookeeper.h`, `zookeeper_version.h`, `recordio.h`) remain byte-identical to upstream releases.
- Exported functions and public data objects maintain identical names, symbol types, storage sizes, and memory mutability, ensuring ELF copy relocations continue functioning seamlessly.
- Shared library sonames and versions remain strictly preserved (`libzookeeper_mt.so.2` and `libzookeeper_st.so.2` on ELF systems; `libzookeeper_mt.2.dylib` on macOS).
- The `zhandle_t` opaque type retains its historical `struct _zhandle` tag, preserving C++ name-mangling compatibility.
- Public structure layouts and alignments match upstream C definitions across supported ABIs (including ILP32 and LP64 differences in `struct Stat`).

Symbol interposition works as expected: where one exported C routine invokes another, calls resolve through the Procedure Linkage Table (PLT). For example, `zoo_aget` routes through `zoo_awget`, and `zoo_get` routes through `zoo_wget` to `zoo_awget`. In contrast, purely internal C helper calls that have migrated into Rust (such as internal `is_unrecoverable` checks, record deallocators, or internal `zookeeper_interest` invocations) no longer traverse PLT stubs, as their logic is encapsulated within the Rust driver layer.

### Shim architecture and module structure

C code is restricted to areas where foreign ABI constraints require it: allocating and freeing application-visible buffers, dispatching application callbacks, exposing public entry points, and interfacing with Cyrus SASL types. All stateful protocol processing, framing, and timer logic reside in safe Rust.

The shim encompasses approximately 5,000 lines of C located in `zookeeper-client-c/shim/`:
- `zks_internal.h`: Internal type declarations, function prototypes, and compiler configuration included by all shim units.
- `zks_data.c`: Allocations for the 36 exported global data symbols (`logLevel`, global ACL vectors, and ID structures).
- `zks_handle.c`: Lifecycle operations (initialization, close, handle release, accessors) and driving entry points (`zookeeper_interest`, `zookeeper_process`).
- `zks_async.c`: Asynchronous API implementations (`zoo_a*`) and `zoo_add_auth`.
- `zks_multi.c`: Multi-operation orchestration (`zoo_amulti`), operation initializers, and transaction result formatting.
- `zks_sync.c`: Synchronous wrappers and condition-variable synchronization records (`libzookeeper_mt` only).
- `zks_drain.c`: The core notification loop that extracts outbox records and dispatches callbacks and watchers.
- `zks_log.c`, `zks_env.c`: Diagnostic logging sinks, environment inspection (`log_env`), and log-format renderers.
- `zks_errors.c`: Error code translations (`zerror`), version reporting, and recoverability checks.
- `zks_resolve.c`: Network address resolution wrapping `getaddrinfo` and endpoint string formatting.
- `zks_rng.c`: Process-wide pseudo-random number generator seeding and deterministic connection ordering hooks.
- `zks_threads.c`: Thread management, worker loops, and lifecycle latches (`libzookeeper_mt` only).
- `zks_compat.c`: Backward-compatibility stubs, including `zoo_lock_auth`, `zoo_unlock_auth`, and legacy static archive symbols.
- `zks_layout_check.c`: Static compile-time assertions verifying type sizes, structure alignments, and member offsets.
- `zks_hashtable_stub.c`: Compatibility stub enabling `-lhashtable` to link successfully against CMake builds.
- `zks_sasl_cyrus.c`, `zks_sasl_cb.c`: Cyrus SASL integration modules, compiled only when SASL support is enabled.

Legacy serialization utilities (`recordio.c`) and generated Jute C record definitions are compiled into the library without modification.

Internal shim functions use the `zks_*` prefix and are configured with static or hidden visibility. The interface exported from Rust to the shim is declared in `zkrs.h` (generated by cbindgen from `zk-ffi`). This header is verified for drift in continuous integration, includes only standard integer headers (`<stdint.h>`, `<stddef.h>`), and avoids exposing public client types. Public union structures, such as `zoo_op_t`, are decomposed into private C records before being passed across the FFI boundary.

### Handle representation and memory safety invariants

The public `zhandle_t` handle held by client applications is a heap-allocated C structure initialized via `calloc`. It maintains fields that C must access directly without invoking Rust:
- Embedded `clientid_t` storage exposed directly via `zoo_client_id()`.
- Default watcher and context pointers, synchronized via an `atomic_flag` spinlock to eliminate torn reads during concurrent updates.
- Relaxed atomic storage for custom logging callbacks.
- Thread management handles, synchronization latches, and Cyrus SASL session pointers.
- A raw pointer referencing the underlying Rust driver handle.

The underlying Rust handle is managed as an `Arc<Inner>`, transferred to C via `Arc::into_raw`. C maintains one primary owning reference, dropped during final handle release. In the multithreaded library, worker threads and active outbox continuations maintain independent `Arc` clones. Each FFI invocation (`zkrs_*`) temporarily rebuilds an owned `Arc` clone for the duration of the call. FFI entry points never accept borrowed references derived from C pointers, ensuring concurrent close operations on separate threads cannot deallocate state underneath an active Rust execution frame.

Memory safety is maintained through strict structural invariants:
- **Pointer Isolation**: Safe Rust code never holds raw C pointers. Application requests, completions, and watchers are tracked via opaque integer tokens. Only the C shim reconstructs and invokes function pointers.
- **Linear Token Lifecycle**: Request tokens are strictly non-duplicable (`!Clone`, `!Copy`). Tokens enter the core upon successful submission and return to C exactly once: either through normal completion dispatch or via an explicit `Drop` destructor that frees the associated C request record. Synchronous waiter records are guaranteed completion signals even during abnormal connection termination.
- **Bounded View Lifetimes**: Data buffers passed from C into Rust are copied into owned structures during FFI transitions. Conversely, memory views provided by Rust to C remain pinned and valid until the subsequent FFI operation on that outbox.
- **Re-entrancy Protection**: C callback code never executes with an active Rust frame on the stack. Re-entrant invocations from application callbacks enter the FFI as distinct, nested operations.
- **Unwind and Cancellation Safety**: Shipped binaries configure `panic = "abort"`. To protect against asynchronous thread cancellation (`pthread_cancel`), FFI boundary macros temporarily disable cancellation via `pthread_setcancelstate` upon entering Rust, restoring the prior cancellation state upon returning to C.

### Foreign Function Interface boundary

Interactions between the C shim and the Rust core center on a structured outbox pattern. Driving calls invoke Rust and receive an execution step status and an outbox populated with actions for C to execute:

```c
typedef enum { ZKRS_DONE, ZKRS_MORE, ZKRS_RELEASE, ZKRS_EXIT } zkrs_step;

zkrs_step zkrs_process(const zkrs_handle *h, int32_t events, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_resume(const zkrs_handle *h, zkrs_outbox *ob, int *rc);
int       zkrs_outbox_next(zkrs_outbox *ob, zkrs_item *it);
void      zkrs_outbox_finish(zkrs_outbox *ob);
void      zkrs_outbox_requeue(zkrs_outbox *ob);
```

Outbox records encapsulate diagnostic log lines, operation completions, watcher dispatches, authentication lockpoints, completion queue drains, Cyrus SASL requests, and thread lifecycle commands. The `zks_drain` module processes these items sequentially within pure C frames:
- `ZKRS_DONE`: Indicates step completion. The outbox is exhausted and `*rc` contains the final status code.
- `ZKRS_MORE`: Indicates execution suspended at an operational barrier. C dispatches the extracted items and invokes `zkrs_resume` with the outbox to advance the continuation.
- `ZKRS_RELEASE`: Signals final session destruction. C drains remaining resources, releases internal structures, and frees the `zhandle_t` handle.
- `ZKRS_EXIT`: Instructs worker thread loops in `libzookeeper_mt` to terminate.

If an application callback throws a C++ exception, the exception unwinds through `zks_drain` toward the application call frame. A C++ cleanup handler invokes `zkrs_outbox_requeue`, returning the active continuation to the session core and marking it abandoned. Subsequent driving calls drain the abandoned items and complete teardown without corrupting state.

If an application callback executes a `longjmp`, skipping standard cleanup handlers, the outbox continuation remains safely preserved: memory remains uncorrupted, subsequent driving calls return `ZINVALIDSTATE`, and the handle safely awaits final closure via `zookeeper_close()`.

The Rust core interacts with system facilities and libc through an immutable function pointer table supplied during handle creation. Pure routines (such as random number generation or default watcher queries) are callable while holding internal locks. Blocking routines (such as DNS resolution via `getaddrinfo` or random reseeding) require all internal locks to be released. In Rust, a zero-sized `Unlocked` proof token statically prevents blocking operations from executing while locks are held.

### The audited foreign memory module

Foreign memory reads and writes within Rust are strictly isolated to `abi::raw` in `zk-ffi`. No other code within the crate uses raw pointer casting, `slice::from_raw_parts`, or `CStr::from_ptr`.

The module encapsulates:
- Safe ingestion of C strings, counted arrays, and pointer-based structs into owned Rust buffers.
- Direct zeroizing copies for cryptographic credentials.
- Safe serialization of operation results into C-allocated memory structures.
- FFI conversions for handle and outbox references.

Inbound buffers adhere to legacy C length conventions: a length of -1 represents a null payload (ignoring the pointer), a length of 0 represents an empty payload, and lengths below -1 (or null pointers with positive lengths) trigger validation errors during request submission. Outbound writers write directly into raw target locations using pointer primitives without generating intermediate Rust mutable references, preventing aliasing violations against application buffers.

### Result structure allocation and lifecycle

All data structures passed to application callbacks are allocated directly through libc `malloc` and `calloc`, ensuring full compatibility with application-level `free()` calls, legacy `deallocate_*` functions, and Jute record destructors:
- String vectors are allocated via `calloc(count, sizeof(char*))` with individual elements allocated via `malloc(len + 1)`. A null vector is represented with a count of -1 and a null data pointer.
- Payload data buffers are allocated via `malloc(len)` without null termination (or `malloc(0)` for empty buffers). null znodes return null pointers with length -1.
- Path strings are allocated via `malloc(len + 1)`, containing chroot-stripped, null-terminated strings.
- `struct Stat` instances are populated field-by-field through an ABI-verified `#[repr(C)]` layout mirror.

Result writers build structures directly from decoded wire records rather than running legacy deserializers over unvalidated byte buffers. If memory allocation fails during result construction, the process aborts immediately, eliminating the legacy C client's undefined behavior and null-pointer dereferences on out-of-memory conditions.

### Callback and watcher delivery

Request records (`struct zks_req`) track the application callback pointer, user context data, and completion mode (asynchronous, synchronous, or authentication). Upon completion:
- Synchronous completions populate their shared result record and signal the waiting condition variable without invoking application callbacks.
- Multi-operation completions execute the multi-result writer before dispatching the user callback.
- Asynchronous completions skip execution if the callback pointer is null (avoiding C client crashes).
- Upon success, temporary C argument structures are passed to the callback. Upon error, null pointers and zero-length indicators are provided in accordance with C conventions.

Following callback return, temporary allocation cleanup executes in historical C order: individual payload or string buffers are freed, collection vectors are released via `deallocate_String_vector` or `deallocate_ACL_vector`, and the request record is deallocated. If an application callback modifies internal vector elements, cleanup respects those modifications. Cleanups are registered within C frame cleanup handlers (`__try`/`__finally` on MSVC, or GCC cleanup attributes) to ensure resources free cleanly if a callback throws an exception.

Watcher delivery (`zks_deliver_watch`) formats event paths via `malloc`, notifies each matching watcher with the application handle, and releases path memory after the last watcher returns. Execution threading strictly matches historical behavior: asynchronous completions, watcher events, and session events dispatch on the completion thread in `libzookeeper_mt` and inline within driving calls in `libzookeeper_st`. Synchronous completions and authentication callbacks dispatch on the I/O or closing thread, bypassing completion queues.

### Request validation and submission ordering

Asynchronous submission routines (`zoo_a*`) marshal arguments into private request records and invoke `zkrs_submit`. Argument validation occurs in a strict, canonical sequence that applications can observe when multiple arguments are invalid:
1. Synchronous checks: verify buffer length pointers in `zoo_wget` or `zoo_reconfig` (returning `ZBADARGUMENTS` before inspecting the handle).
2. TTL validation: verify mode-specific TTL boundaries (returning `ZBADARGUMENTS`).
3. Handle validation: verify handle presence (returning `ZBADARGUMENTS`).
4. Path validation: verify syntax and chroot compatibility (returning `ZBADARGUMENTS`).
5. Session state validation: verify the session is not in an unrecoverable negative state (returning `ZINVALIDSTATE`).
6. Serialization and memory validation: verify buffer bounds and encoding capacity (returning `ZMARSHALLINGERROR`).

Multi-operation requests (`zoo_amulti`) enforce specialized validation: null operation lists or negative counts return `ZBADARGUMENTS`. Unsupported operation types return `ZUNIMPLEMENTED`, taking precedence over subsequent validation failures.

`zoo_add_auth` verifies the scheme, handle, and session state before copying credentials. An unlocked preliminary check verifies state before acquiring the authentication mutex, ensuring invocations on terminated handles return immediately without contending on locks.

### Synchronous operation wrappers

Synchronous API wrappers exist exclusively within `libzookeeper_mt`. Each wrapper allocates a heap synchronization record via `calloc`, executes the corresponding asynchronous operation with an internal completion marker, and blocks on a POSIX condition variable. Waited condition variables are guarded by cancellation cleanup handlers (`pthread_cleanup_push`) that release references safely if the waiting thread is canceled.

Buffers are populated on the caller's thread upon waking via `zkrs_sync_copy_out`, ensuring multithreaded safety. Output data lengths are bounded by caller capacity: if the returned length exceeds buffer capacity, the buffer is filled to capacity while the full length is reported. Output vectors transfer ownership directly to caller pointers.

If a synchronous operation is invoked from an I/O worker thread (such as from within an authentication callback), queuing the request would result in an immediate deadlock. The wrapper detects this condition and returns `ZSYSTEMERROR` with `errno` set to `EDEADLK`.

### Driving entry points (`zookeeper_interest` and `zookeeper_process`)

In `libzookeeper_st`, `zookeeper_interest` and `zookeeper_process` serve as the primary execution drivers. Each call enters the Rust driver loop, processing I/O, draining outbox items, and executing inline callbacks within the caller's stack frame. `zookeeper_interest` populates the active file descriptor, interest bitmask, and timeout structures upon loop conclusion.

In `libzookeeper_mt`, these driving symbols remain exported to maintain link-time compatibility but do not drive network I/O, preventing race conditions against the internal I/O worker thread. Instead, they perform argument validation and return dummy values (`fd = -1`, `interest = 0`, timeout equal to one-third of the session duration, returning `ZOK` or `ZINVALIDSTATE`).

### Initialization and shutdown lifecycles

Session initialization routines share a common C entry pipeline that verifies host strings, allocates `zhandle_t`, configures logging hooks, initializes Winsock on Windows, and invokes `zkrs_handle_new`. If host strings fail to parse or chroot paths are invalid, initialization halts and returns null with `errno` set to `EINVAL`. Host names resolve sequentially, emitting diagnostic log records for each endpoint before attempting subsequent resolutions.

`zookeeper_init_ssl` parses certificate configuration strings using re-entrant `strtok_r`, avoiding corruption of application string-tokenization states.

`zookeeper_close` coordinates shutdown across driver threads:
- Synchronous waiters and pending authentication tokens fail immediately with `ZCLOSING`.
- Asynchronous completion queues drain remaining events.
- Worker threads are joined and detached via explicit barrier transitions.
- Underlying transports flush pending administrative frames and terminate sockets.
- Handle resources, mutexes, and thread synchronization latches are destroyed.

In release builds, double-close or use-after-close conditions remain undefined behavior without runtime checking overhead. In debug configurations (compiled with `--enable-debug` or CMake Debug), a global handle registry tracks active handles and aborts with an informative diagnostic message if an invalid handle is accessed.

### Diagnostic logging compatibility

The logging subsystem (`log_message`, `zoo_set_debug_level`, `zoo_set_log_stream`, `logLevel`) maintains complete behavioral equivalence with historical C conventions:
- Log messages use thread-local formatting buffers in both single threaded and multithreaded configurations, preventing cross-thread buffer corruption.
- Process IDs are captured dynamically per line, ensuring child processes created via `fork()` log their own process IDs rather than cached parent IDs.
- Log message prefixes, timestamp formatting, and stream flushing match C client output byte-for-byte.

Because external operational monitoring tools and automated test suites inspect exact log text, file names, and line numbers, log formatting is pinned against the historical C client source. A generated lookup table (`abi/log-sites.tsv`) records canonical log sites, severity levels, and format strings. The protocol core emits structured log descriptors referencing these sites, and `zks_log_emit` renders the formatted message through `log_message` via the PLT.

### Accessors, data symbols, and symbol visibility

Exported accessor functions (`zoo_get_current_server`, `zookeeper_get_connected_host`, `zoo_recv_timeout`, `zoo_client_id`) return expected values while safely handling null handles without crashing:
- `zoo_get_current_server(NULL)` returns the string `"null"`.
- `zookeeper_get_connected_host` queries the connected peer address via `getpeername` through `rustix`, returning valid endpoints in both connected and read-only states.
- Global symbols (`logLevel`, `ZOO_OPEN_ACL_UNSAFE`, `ZOO_READ_ACL_UNSAFE`, `ZOO_CREATOR_ALL_ACL`) are defined as real C data objects in `zks_data.c`, supporting standard copy relocations.

Symbol visibility is strictly managed via linker export maps generated from `abi/symbols.tsv` and `abi/static-symbols.tsv`. Shipped shared libraries export exactly 211 symbols in `libzookeeper_mt` and 188 symbols in `libzookeeper_st` (with TLS and SASL enabled). Internal Rust symbols, standard library functions, and unexported C symbols are hidden, preventing namespace pollution and symbol collision when linked alongside other Rust or OpenSSL components.

## Security Architecture: TLS and SASL

The security architecture encompasses transport layer security (TLS), SASL authentication, digest credentials, and in-memory secret lifecycle management. Both the drop-in C libraries and the native Rust client share a common TLS implementation powered by `rustls` within `zk-driver`. SASL implementations diverge based on target ABI requirements: the drop-in library bridges to Cyrus SASL via hardened C compilation units to satisfy legacy C API constraints, whereas the native Rust client employs a pure Rust SASL engine without dynamic dependencies on `libsasl2`. Digest authentication is implemented directly within the protocol state machine.

### Transport Layer Security via `rustls`

The implementation eliminates OpenSSL dependencies entirely: no OpenSSL libraries are linked, no OpenSSL runtime initialization is executed, and legacy C socket structures (`ssl_sock`, `ssl_ctx`) remain unused. Build systems preserve OpenSSL header discovery probes exclusively to govern conditional symbol export (`zookeeper_init_ssl`) to match platform export signatures. Consolidating TLS within `rustls` guarantees memory safety for all protocol framing, cryptographic handshakes, and record decryption.

The TLS subsystem resides in `zk-driver/src/net/tls/` under `#![forbid(unsafe_code)]`, built against pinned releases of `rustls`, `rustls-webpki`, and `rustls-pki-types`. The driver retains exclusive ownership of the underlying network socket, utilizing `rustls` strictly for in-memory plaintext and ciphertext transformations via the `TlsConn` trait.

In the drop-in library, each connection attempt and read-write probe reconstructs an isolated TLS session and re-reads certificate and private key files from disk, reproducing the per-attempt context initialization of the legacy C client. Session resumption, ALPN, and early data are disabled. To match C client behavior, the peer IP address is supplied to `rustls` as a placeholder name without hostname verification. Connections negotiate TLS 1.2 or TLS 1.3, and renegotiation requests are rejected.

TLS context configuration executes upon TCP connection establishment on the I/O driver thread without holding session locks. Initialization follows historical sequence: context allocation, CA certificate loading, default system path inspection, client certificate chain ingestion, private key loading, key-pair consistency verification, and session attachment. Pending log events flush across an operational barrier prior to disk operations, ensuring log callbacks execute within valid C stack frames.

Configuration failures emit corresponding legacy diagnostic messages ("Failed to load CA file %s"), assign `errno` to `EINVAL`, and return `ZSSLCONNECTIONERROR` without tearing down the session. Unlike the legacy C client, which exempted TLS connection states from receive timeouts and looped indefinitely on write readiness, the Rust driver enforces standard receive deadlines, advancing failover to subsequent ensemble hosts upon expiration.

Because the drop-in client replicates legacy C behavior by omitting server hostname validation, any server certificate chaining to an accepted trust anchor is accepted. Applications requiring strict peer authentication should utilize the native Rust client, which enforces RFC-compliant hostname and Subject Alternative Name verification by default.

### Trust store ingestion and path evaluation

Trust anchor loading mirrors OpenSSL configuration conventions: peer certificates are evaluated against supplied CA files and platform default paths under the `ssl_server` purpose (enforcing trusted-first resolution without partial chains or CRL validation). Reference compatibility targets OpenSSL 3.0 and 3.5 LTS releases. `trust.rs` processes trust inputs in priority order: configured CA bundles, default system certificate files, and hashed certificate directories.

CA bundles are parsed using strict PEM decoding. Valid `CERTIFICATE`, `X509 CERTIFICATE`, and `TRUSTED CERTIFICATE` records are ingested as trust anchors. Non-certificate records and malformed PEM headers are rejected. Unreadable files or base64 decoding errors fail the load operation immediately.

Default system certificate paths resolve according to standard conventions:
- When permitted, `SSL_CERT_FILE` and `SSL_CERT_DIR` override system defaults. In set-uid or set-gid processes, these environment variables are ignored to prevent privilege escalation.
- Build-time default locations are configured via `--with-rust-tls-default-paths=FILE:DIR` (defaulting to the detected OpenSSL directory). Standard discovery searches `/usr/lib/ssl`, `/etc/pki/tls`, `/etc/ssl`, `/usr/local/ssl`, and Homebrew locations.
- Certificate directories are traversed using OpenSSL `by_dir` hash conventions (`HHHHHHHH.N`). Files are inspected sequentially per hash, verifying that canonical subject hashes match the filename. Directory lookups occur only when explicit CA files do not contain candidate issuers for the subject under evaluation.

Trust anchors are classified dynamically during path construction:
- **Anchors**: Self-signed certificates possessing valid BasicConstraints (`CA:TRUE`) and key usage compatible with `ssl_server`.
- **Bridges**: Intermediate certificates functioning as issuer candidates within a path.
- **Refused Inputs**: Certificates containing OpenSSL auxiliary trust restrictions that reject `serverAuth`. While retained in the candidate pool to emulate OpenSSL candidate selection, chains selecting refused inputs fail validation with `X509_V_ERR_CERT_REJECTED`.

### Server certificate verification pipeline

Certificate verification is implemented via a specialized `ServerCertVerifier` in `zk-driver`. Leaf public keys are first validated against supported provider algorithms, after which verification branches across three distinct evaluation pipelines:
1. **Self-Signed Leaf Certificates**: Accepted if byte-identical to an unrefused trust anchor, within valid validity periods, compatible with `ssl_server` constraints, and free of unprocessed critical extensions.
2. **Standard X.509 v3 Chains**: Validated through `verify_server_cert_signed_by_trust_anchor` via `rustls-webpki`, supplemented by strict post-validation checks to maintain OpenSSL parity:
   - *KeyCertSign Verification*: Candidate intermediate certificates must assert the `keyCertSign` key usage bit.
   - *Refused Issuer Rejection*: Ensures intermediate resolution does not traverse certificates marked as untrusted within OpenSSL auxiliary trust sets.
   - *OpenSSL Path Parity Check*: Validates intermediate path length limits, authority key identifier consistency, Netscape certificate type extensions, and directory name constraints along the selected path.
3. **Legacy X.509 v1 Chains**: Evaluated through a dedicated, safe-Rust v1 verification engine (`v1_leaf.rs`).

Handshake signature verification operates directly on the leaf certificate's SubjectPublicKeyInfo. On TLS 1.3, signatures are validated using raw public key primitives restricted to standard TLS 1.3 signature algorithms. On TLS 1.2, ECDSA and Ed25519 leaf certificates must assert `digitalSignature` within their key usage extensions. Compressed elliptic curve points are rejected to guarantee fail-closed security.

### Legacy X.509 v1 leaf verifier

To support legacy deployment environments—such as certificates generated via historical OpenSSL scripts where leaf certificates lack v3 extensions—an in-tree verifier (`v1_leaf.rs`) executes under `#![forbid(unsafe_code)]`. It provides full compatibility with OpenSSL v1 handling: accepting v1 leaves beneath valid v3 intermediate chains and v1 self-signed trust anchors, while rejecting v1 intermediate certificates.

The verifier reconstructs validation paths iteratively matching OpenSSL's `find_issuer` mechanics:
- Candidates are evaluated using canonical name equivalence, authority key identifier consistency, and public key algorithm matching.
- Trust anchors take precedence over wire-delivered certificates.
- Path traversal is bounded by a maximum depth of 100 certificates and an evaluation budget of 256 candidate steps, mitigating denial-of-service risks from maliciously crafted cyclic certificate chains.
- Path finalization enforces temporal validity (`notBefore <= t < notAfter`), intermediate `CA:TRUE` indicators, path length limits, name constraints, and prohibition of unsupported critical extensions or RFC 3779 resource extensions.

### Client identity and private key parsing

Client authentication loads when certificate and private key paths are supplied. Passphrase arguments are optional. The first certificate block in the chain file represents the client leaf, followed by optional intermediate certificates. Client credentials are configured via `with_client_cert_resolver` using `CertifiedKey`, enabling support for v1 client certificates.

Supported private key formats include:
- Unencrypted PKCS#8 (`PRIVATE KEY`), PKCS#1 (`RSA PRIVATE KEY`), and SEC1 (`EC PRIVATE KEY`).
- Encrypted PKCS#8 using PBES2 with PBKDF2 (HMAC-SHA1, SHA224, SHA256, SHA384, SHA512) or scrypt, combined with AES-CBC or DES-EDE3-CBC ciphers. Scrypt memory allocation is strictly bounded at 32 MiB to prevent memory exhaustion.
- Traditional OpenSSL encrypted PEM formats (`Proc-Type: 4,ENCRYPTED`) using AES-CBC, DES-EDE3-CBC, or DES-CBC with MD5 key derivation.

Decrypted keys are passed to the cryptographic provider for validation against the leaf certificate's public key. Key mismatches emit corresponding legacy diagnostic messages ("Failed to load client private key from %s" or "SSL_CTX_check_private_key failed").

### Cryptographic provider configuration

The client supports three distinct cryptographic providers, configured at build time:
- `ring`: The default provider, offering high-performance, cross-platform cryptography for standard deployments.
- `aws-lc-rs`: An alternative provider leveraging AWS-LC for specialized architecture optimizations.
- `aws-lc-rs-fips`: A FIPS 140-3 validated provider utilizing `aws-lc-fips-sys`, restricted to Linux x86_64 and AArch64 platforms.

Build configurations select exactly one provider via `--with-rust-tls-provider` (CMake `ZK_RUST_TLS_PROVIDER`, Cargo features `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`). Selecting multiple providers or omitting provider configuration halts the build via `compile_error!`.

Under the FIPS configuration:
- The provider asserts active FIPS validation status during handle initialization, aborting execution if FIPS mode is unverified.
- TLS 1.2 requires Extended Master Secret negotiation.
- Supported algorithms are strictly confined to FIPS-approved suites (P-256, P-384, RSA 2048-8192 bits; AES-GCM, ChaCha20-Poly1305). Traditional PEM encryption and legacy ciphers (MD5, DES, SHA-1) are rejected.

### Connection termination and `close_notify`

During graceful session termination, both the drop-in and native clients transmit TLS `close_notify` alerts before closing the transport socket. Outbound alerts flush nonblockingly within the allocated close timeout budget. The driver does not block awaiting peer acknowledgment.

Forced disconnects and error teardowns (such as heartbeat timeouts, SASL failures, or forced rebalancing) suppress `close_notify` transmission, terminating the socket immediately. If `rustls` encounters an internal fatal error, a protocol alert is transmitted immediately via a nonblocking send, avoiding transport corruption when unwritten application data remains buffered.

### Cyrus SASL integration

The C drop-in client integrates Cyrus SASL via `libsasl2`, isolating all interaction within two hardened compilation units (`zks_sasl_cyrus.c` and `zks_sasl_cb.c`). Only these files include SASL headers, preventing foreign SASL types from propagating into the broader shim or Rust core.

The Rust core drives SASL protocol sequencing via the outbox. The core yields `Sasl` actions (`Start`, `Step`, `Dispose`), and the driver dispatches them to C routines on the I/O thread without active Rust stack frames. The FFI boundary passes bounded byte slices, eliminating raw descriptor sharing:

```c
typedef struct zkrs_bytes { const uint8_t *p; uint32_t len; uint8_t is_null; } zkrs_bytes;
typedef struct zkrs_sasl_out { int32_t status; zkrs_bytes mech; zkrs_bytes token; } zkrs_sasl_out;

void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, const zks_sasl_params *p,
                    zkrs_bytes local, zkrs_bytes peer, zkrs_sasl_out *out);
void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zkrs_bytes challenge, zkrs_sasl_out *out);
void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen);
void zkrs_sasl_result(const zkrs_handle *h, uint32_t gen, const zkrs_sasl_out *v);
```

Generation counters tag SASL operations, discarding results originating from stale or disconnected sessions. If an application callback throws an exception during SASL execution, the receive timer triggers connection teardown, recycling mechanism resources safely.

### Per-session password isolation

In the canonical C client, `zoo_sasl_make_password_callbacks` allocated a single shared memory block for credentials. When multiple sessions shared this structure, `_zsasl_getsecret` mutated the embedded password buffer in place without synchronization, creating data races when multiple connections negotiated credentials concurrently.

The Rust client eliminates this race while preserving the public memory layout and deallocation contract of the shared block:
- At connection initiation, the C shim constructs an isolated, per-session duplicate of the callback array.
- The primary `SASL_CB_PASS` entry is intercepted with an internal trampoline routing to private session storage.
- When Cyrus requests credentials, passwords retrieved from disk or interactive prompts are written exclusively into zeroized session-private buffers. The shared parameter block remains untouched during normal authentication.
- For external applications invoking Cyrus SASL directly using ZooKeeper callback blocks, a dedicated static mutex serializes buffer updates without holding locks across external callbacks.
- Passwords read from disk files are bounded at 1,023 bytes. Longer initial lines trigger `SASL_BUFOVER`, replacing the silent string truncation present in the legacy client.

### Hardened C coding standards

The Cyrus SASL integration modules adhere to rigorous security standards enforced via continuous integration analysis (Clang-Tidy, Clang Static Analyzer, GCC `-fanalyzer`, CodeQL):
- Insecure string routines (`strcpy`, `strcat`, `sprintf`, `vsprintf`, `gets`, `strtok`) are prohibited.
- String formatting utilizes `snprintf` with explicit truncation verification.
- Integer arithmetic on memory allocations enforces overflow detection using checked arithmetic macros (`ZKS_ADD_OVF`, `ZKS_MUL_OVF`).
- External string lengths are bounded using `strnlen`.
- Secrets resident in memory are cleared via `zks_secure_zero` prior to deallocation and on function exit paths (supplemented by compiler cleanup attributes).

### In-memory secret lifecycle and zeroization

Sensitive data—including digest authentication credentials, SASL tokens, private keys, TLS passphrases, and session passwords—is strictly protected throughout its lifecycle in memory:
- In Rust, secret data is encapsulated within `zk_proto::SecretBytes`, wrapping `zeroize::Zeroizing<Vec<u8>>`. The type prohibits cloning (`!Clone`) and redacts internal contents from `Debug` representations. PEM buffers, derived keys, and decrypted DER buffers utilize zeroizing allocators.
- In C, sensitive buffers are scrubbed using `zks_secure_zero` (mapping to `explicit_bzero`, `memset_s`, or `SecureZeroMemory`), guaranteeing that compilers do not optimize away memory sanitization passes.
- Cryptographic material is excluded from diagnostic log records across all logging levels. Heap allocations containing credentials are scrubbed immediately upon handle destruction.

## The Native Rust API

The native Rust client is distributed as the `apache-zookeeper` package (crate `zk-client`, library `apache_zookeeper`). It builds upon the shared protocol core and driver layer, parameterized unconditionally with `Compat::NATIVE` to decouple modern Rust applications from historical C client idiosyncrasies. Higher-level distributed coordination recipes are provided as a layered companion package, `apache-zookeeper-recipes` (`zk-recipes`).

### Core Design Principles

The native API adheres to several foundational design invariants:
- **Eager Request Submission**: API methods submit requests immediately upon invocation rather than deferring execution until the returned future is first polled. Consequently, request methods are standard functions returning custom future types (`ResponseFuture`) rather than `async fn`. This guarantees that invocation order strictly dictates transaction ID (xid) allocation and network transmission sequence, matching client-side ordering expectations.
- **Lock-Free User Execution**: Application-provided code and callbacks never execute under internal library locks, nor do they run directly on background I/O threads. Operation results and watch notifications are delivered via futures, channels, and streams. Trait hooks executing on I/O pathways (`Resolve`, `SaslMechanism`, `CredentialSource`, `Metrics`) run with all internal mutexes released and are protected by `catch_unwind`.
- **Strongly Typed Domain Invariants**: The API leverages Rust's type system to eliminate runtime protocol errors: validated paths (`ZPath`, `ZPathBuf`), mode-safe node creation (`CreateMode`), bitflag permissions (`Acl`), and dedicated newtypes for `Version`, `Zxid`, and `SessionId`.
- **Zero Global State**: Clients encapsulate their own xid generators, logging contexts, and secure random number generators, avoiding mutation of process-wide libc random seeds. The minimum supported ZooKeeper server version is 3.5.0.

### Client Construction and Configuration

Client instances are configured via `ClientBuilder`:

```rust
impl ClientBuilder {
    pub fn session_timeout(self, t: Duration) -> Self;           // Default: 30 seconds
    pub fn chroot(self, chroot: impl PathArg) -> Self;
    pub fn allow_read_only(self, yes: bool) -> Self;
    pub fn session(self, id: SessionId, pw: SessionPassword) -> Self;
    pub fn auth(self, scheme: impl Into<String>, data: impl Into<Vec<u8>>) -> Self;
    pub fn restore_watches(self, yes: bool) -> Self;             // Default: true
    pub fn connect_timeout(self, t: Duration) -> Self;           // Default: session_timeout / 3
    pub fn reconnect_delay(self, t: Duration) -> Self;           // Default: 1 second with 25% jitter
    pub fn server_order(self, o: ServerOrder) -> Self;           // Shuffled | AsGiven
    pub fn dns_refresh(self, p: DnsRefresh) -> Self;             // EachPass | Interval(Duration) | Never
    pub fn resolver(self, r: impl Resolve) -> Self;
    pub fn runtime(self, rt: Runtime) -> Self;
    pub fn update_servers_from_config(self, yes: bool) -> Self;
    pub fn max_pending_requests(self, n: usize) -> Self;         // Default: unbounded
    pub fn persistent_watch_buffer(self, events: usize) -> Self; // Default: unbounded
    pub fn max_frame_size(self, bytes: u32) -> Self;             // Default: 4 MiB
    pub fn close_timeout(self, t: Duration) -> Self;             // Default: 3 seconds
    pub fn connect(self) -> ConnectFuture;
    pub fn build(self) -> Result<Client, ConnectError>;
}
```

Connection strings follow the canonical Java ZooKeeper syntax (`host1:2181,host2:2181/chroot`), supporting IPv6 bracket notation (`[::1]:2182`) and reserving path suffixes exclusively for chroot specifications.

The builder supports two instantiation pathways:
- `connect()`: The primary entry point. Returns a `ConnectFuture` that resolves once the session is fully established, following successful TLS negotiation, SASL handshakes, and initial credential acknowledgments. If the ensemble is temporarily unreachable, the future remains pending while exposing diagnostic status via `ConnectFuture::state_watcher()`.
- `build()`: Instantiates the `Client` handle immediately, queuing subsequent requests until the background driver establishes a connection.

Connection management enforces structured timeouts: individual TCP connection attempts are bounded by `connect_timeout` (defaulting to one-third of the session timeout). Reconnection sequences apply randomized jitter (`reconnect_delay`) following complete passes over the server list. DNS refresh behaviors are configured via `DnsRefresh`:
- `EachPass`: Re-resolves hostnames once per traversal of the ensemble list (the default).
- `Interval(Duration)`: Re-resolves hostnames periodically in the background without interrupting active connections.
- `Never`: Resolves hostnames statically at initialization.

Memory protection is enforced by bounding maximum inbound frame sizes (`max_frame_size`, default 4 MiB) and optionally constraining queued in-flight requests (`max_pending_requests`).

### Execution Runtimes

The client abstracts asynchronous runtime execution across thread-based and task-based models:

```rust
#[non_exhaustive]
pub enum Runtime {
    Thread(ThreadOptions),
    #[cfg(all(unix, feature = "tokio"))]
    Tokio(tokio::runtime::Handle),
}
```

On Unix systems with the `tokio` feature enabled, clients automatically detect multithreaded Tokio runtime contexts, driving network I/O through `tokio::io::unix::AsyncFd` within an asynchronous driver task. If no Tokio runtime context exists or the target is Windows, the client defaults to the threaded driver, executing an event loop on a dedicated background thread.

Synchronous applications can utilize `blocking::Client` (available via the `blocking` feature), which executes atop the threaded driver and provides synchronous equivalents for all operations. Furthermore, native `ResponseFuture` instances provide explicit `wait()` and `wait_timeout()` blocking primitives. To prevent runtime deadlocks, blocking wait calls immediately fail with `Error::InvalidState` if invoked from within Tokio runtime worker threads or the driver's internal I/O thread.

### Operations, Result Types, and Error Handling

Data operations expose consistent asynchronous signatures:

```rust
pub fn create(&self, path: impl PathArg, data: &[u8], mode: CreateMode, acl: &[Acl])
    -> ResponseFuture<(ZPathBuf, Stat)>;
pub fn exists(&self, path: impl PathArg) -> ResponseFuture<Option<Stat>>;
pub fn get_data(&self, path: impl PathArg) -> ResponseFuture<(Vec<u8>, Stat)>;
pub fn set_data(&self, path: impl PathArg, data: &[u8], version: Option<Version>)
    -> ResponseFuture<Stat>;
pub fn delete(&self, path: impl PathArg, version: Option<Version>)
    -> ResponseFuture<()>;
pub fn get_children(&self, path: impl PathArg)
    -> ResponseFuture<Vec<String>>;
```

Operation execution follows three distinct phases:
1. Path validation executes synchronously without holding locks. Invalid path syntax immediately yields an unresolved `Err(Error::InvalidPath)`.
2. Under the session lock, terminal session states are checked, chroot prefixes are joined along path boundaries, xids are assigned, and serialized frames append to the send queue.
3. The I/O driver is signaled via its waker channel.

This architecture ensures strict serialization: if call A returns its future before call B is invoked on the same client, call A is assigned a lower xid, transmits first on the wire, and completes first upon response receipt.

Error conditions are unified under a single non-exhaustive enum, `Error`:
- Recoverable transport disconnections surface as `Error::ConnectionLoss`, providing a uniform match arm for application-level retry policies.
- Terminal session failures preserve exact causes: `SessionExpired`, `AuthFailed`, or `Closing`.
- Detailed transport diagnostics (such as I/O errors, TLS alerts, or protocol violations) are accessible via `client.state_watcher().last_error()`.

### Watcher Abstractions and Event Streaming

Session lifecycle transitions and path data notifications are cleanly separated:
- **Session State Watchers**: Monitored via `client.state_watcher()`, returning a `StateWatcher` stream (modeled on `tokio::sync::watch`) yielding `SessionState` updates (`Connecting`, `Connected`, `ConnectedReadOnly`, `Expired`, `AuthFailed`, `Closed`) alongside monotonic connection epoch counters.
- **Data and Existence Watchers**: Created via dedicated methods returning one-shot futures or event streams:

```rust
pub fn get_data_watched(&self, path: impl PathArg)
    -> ResponseFuture<(Vec<u8>, Stat, OneshotWatcher)>;
pub fn add_watch(&self, path: impl PathArg, mode: AddWatchMode)
    -> ResponseFuture<PersistentWatcher>;
pub fn remove_watches(&self, path: impl PathArg, kind: WatcherKind, local_only: bool)
    -> ResponseFuture<()>;

impl Future for OneshotWatcher {
    type Output = Result<WatchedEvent, WatchError>;
}

impl futures_core::Stream for PersistentWatcher {
    type Item = Result<WatchedEvent, WatchError>;
}
```

Watches arm atomically upon successful response decoding. One-shot watchers resolve exactly once upon receiving a matching node notification. Persistent watchers (`add_watch`) deliver continuous event streams for the designated path (`AddWatchMode::Persistent`) or entire subtrees (`AddWatchMode::PersistentRecursive`).

The native client manages watcher registrations with strict reference counting per path and watch class, issuing remote `RemoveWatches` requests when local watcher handles drop. By default, registered watches survive transport reconnections via `SetWatches` (or `SetWatches2` when persistent watches are registered). Backpressure on persistent watch streams is managed via `persistent_watch_buffer`: if a consumer lags behind, unconsumed events are dropped and the stream yields `Err(WatchError::Lagged { missed })`, prompting the application to resynchronize state without stalling transport I/O.

### Transactions and Dynamic Reconfiguration

Atomic multi-transactions are assembled using `MultiWriter`:

```rust
let mut txn = MultiWriter::new();
txn.check("/locks/primary", version)?;
let job = txn.create(
    "/jobs/task-",
    &payload,
    CreateMode::PersistentSequential,
    acl::OPEN_ACL_UNSAFE,
)?;
match client.multi(&txn).await {
    Ok(results) => {
        let created_path: &ZPath = results.get(job);
    }
    Err(MultiError::Aborted { index, error, per_op }) => {
        // Transaction aborted by server due to sub-operation failure
    }
    Err(MultiError::Request(err)) => {
        // Transport- or session-level request failure
    }
}
```

Dynamic ensemble reconfiguration is supported via `reconfig()` and `get_config()`. The client can track cluster membership changes automatically: enabling `update_servers_from_config(true)` arms a persistent watch on `/zookeeper/config`, updating the client's internal host list and initiating dynamic rebalancing as server configurations evolve.

### Modern Protocol Capabilities

The native client supports advanced ZooKeeper protocol operations unsupported by the legacy C client:
- Persistent and recursive watch management (`add_watch`, requiring ZooKeeper 3.6+).
- Transactional read batches (`multi_read`).
- Ephemeral znode discovery (`get_ephemerals`, `get_ephemerals_all`, requiring ZooKeeper 3.6+).
- Identity introspection (`who_am_i`, requiring ZooKeeper 3.7+).
- Concurrent negotiation of TLS encryption and SASL authentication over a single connection.

### Lifecycle Management and Teardown

`Client` handles are cheaply clonable (`Clone + Send + Sync`) and share an underlying session driver. Session closure operates via `close()`:
- `close()` immediately marks the client as closing, rejecting new submissions with `Error::Closing`.
- In-flight and queued requests drain gracefully, followed by the transmission of an explicit protocol close frame.
- The client awaits server acknowledgment up to `close_timeout` before terminating network transports and notifying active watchers.
- When the final `Client` clone drops, background cleanup terminates the session asynchronously.
- Alternatively, `detach()` closes the network transport without revoking the remote session, returning the active `SessionId` and `SessionPassword` for session resumption across processes.

Dropping an unresolved `ResponseFuture` abandons interest in the result but does not cancel the remote operation if already transmitted to the network.

### Native Security Configuration

TLS options are managed via `TlsOptionsBuilder`:
- **Hostname Verification**: Enabled by default, verifying server identities against connection strings and Subject Alternative Names.
- **Trust Roots**: Configurable via explicit CA bundles (`ca_file`), system trust anchors (`add_system_roots`), or native platform policy verifiers via `rustls-platform-verifier` (`with_platform_verifier`).
- **Dynamic Reloading**: Automatically re-reads certificate and key files upon connection retries (`ReloadPolicy::OnChange`).

Native SASL configuration (`SaslConfig`) supports pure-Rust DIGEST-MD5 with dynamic credential rotation (`CredentialSource`), GSSAPI/Kerberos via `libgssapi`, and pluggable custom mechanisms via `SaslMechanismFactory`.

### Observability and Diagnostic Instrumentation

Diagnostic records emitted by the protocol core integrate seamlessly with the Rust observability ecosystem:
- Events and spans map to `tracing` by default, or optionally to the standard `log` crate. Log targets are organized hierarchically: `apache_zookeeper::session`, `apache_zookeeper::request`, `apache_zookeeper::watch`, and `apache_zookeeper::proto`.
- Operational metrics are captured via the `Metrics` trait, providing nonblocking hooks for request latency, session state transitions, byte throughput, and watcher queue lag.

### Feature Flags

The `zk-client` crate exposes a modular feature architecture:
- `tokio`: Enables the Tokio asynchronous I/O driver (Unix only).
- `tracing`: Directs diagnostic output to `tracing` (enabled by default).
- `stream`: Implements `futures_core::Stream` for persistent watcher streams (enabled by default).
- `blocking`: Exposes `blocking::Client` and synchronous execution helpers.
- `tls-rustls`: Enables TLS support via `rustls`.
- `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`: Selects the underlying cryptographic provider.
- `tls-platform-verifier`: Integrates OS-native certificate verification policies.
- `sasl`: Pure-Rust DIGEST-MD5 authentication.
- `sasl-gssapi`: GSSAPI / Kerberos authentication linking system libraries.
- `serde`: Serialization implementations for protocol records and identifiers.

### Distributed Coordination Recipes

The `zk-recipes` crate (`apache-zookeeper-recipes`) implements battle-tested coordination patterns designed for seamless interoperability with Apache Curator recipes in Java:
- **`DistributedLock`**: Implements fully distributed, non-reentrant mutual exclusion compatible with Curator's `InterProcessMutex`. Lock candidates create ephemeral sequential znodes beneath a common parent path (`<path>/_c_<uuid>-lock-<seq>`). Waiters monitor the node immediately preceding their own in sequence, eliminating thundering-herd wakeups upon lock release. Protected-mode recovery guarantees that if connection loss occurs during node creation, candidates locate and adopt existing nodes on reconnect before retrying.
- **`LeaderLatch`**: Provides deterministic leader election compatible with Curator's `LeaderLatch`. The candidate possessing the lowest sequential node acts as the current leader. Connection loss events immediately revoke leadership status until the session reconnects and node presence is verified.
- **`TreeCache`**: Maintains an in-memory replicated snapshot of a znode subtree, powered by persistent recursive watches (`AddWatchMode::PersistentRecursive`). The cache dynamically resolves node creation, data modification, and deletion events, re-synchronizing full tree state across transport interruptions.

## Differences from the C Client

This section specifies all behavioral, structural, and interface differences between the Rust drop-in libraries and the canonical C client. Where legacy behaviors appear non-standard but are relied upon by existing applications, they are intentionally preserved. Where the legacy client contains structural defects, undefined behaviors, or resource leaks, the Rust core substitutes defined, safe semantics. All functional bug fixes remain permanently enabled across all configurations.

### Remediated Defects and Structural Bug Fixes

The Rust core resolves several long-standing design and implementation defects present in the legacy C client:

- **Predictable Ensemble Failover Progression**: In the legacy client, connection failures advance the server list cursor inside error handlers and advance it again upon the subsequent connection attempt via `zoo_cycle_next_server`. Consequently, a client attempting to connect to a cluster with dead servers skips alternate endpoints, causing two-node deployments to repeatedly retry a single dead host and even-sized ensembles to ignore half of their configured members. The Rust core advances the server cursor exactly once per failed connection attempt. In a two-server ensemble, the client alternates deterministically between nodes (A, B, A, B), and full passes over the server list trigger a standardized reconnect pause equal to one-sixtieth of the session timeout.
- **Bidirectional Endpoint Comparison in Server Lists**: The legacy client evaluates server list updates by comparing IP addresses only (ignoring ports) in a single direction. As a result, configuration updates modifying only ports are ignored, multi-instance nodes on a single host collapse into a single endpoint, and replacements of duplicate entries fail to register. The Rust core enforces bidirectional equality comparing both IP address and port, ensuring multi-server single-host configurations and port migrations take effect immediately.
- **Comprehensive SASL Failure Teardown**: In the legacy client, `zoo_sasl_mark_failed` merely assigns internal state variables: network sockets remain open, watcher callbacks receive no notification, worker threads exit, and synchronous application callers block indefinitely. The Rust core treats SASL failures as terminal session rejections identical to digest authentication failures: the session transitions to `AUTH_FAILED`, an `AUTH_FAILED` session event is dispatched, the socket terminates, and all pending synchronous, asynchronous, and authentication requests fail immediately with `ZAUTHFAILED`.
- **Packetized Watcher Re-registration (`SetWatches`)**: On reconnect, the legacy client transmits all active watchers in a single monolithic `SetWatches` frame. When large watch registries exceed the server's `jute.maxbuffer` threshold, the server forcibly disconnects the client, inducing an infinite reconnection loop. The Rust core partitions watch registrations into packets bounded at approximately 128 KiB (matching Java client behavior), allowing arbitrarily large watcher registries to re-arm reliably.
- **Chroot-Independent Dynamic Configuration Queries**: Synchronous configuration lookups (`zoo_getconfig`, `zoo_wgetconfig`) in the legacy client erroneously prepend the active chroot prefix to `/zookeeper/config`, addressing a non-existent path. The Rust core aligns synchronous lookups with asynchronous operations, querying the canonical cluster configuration path regardless of chroot settings.
- **Explicit SSL Error String Mapping**: The legacy `zerror` implementation lacks a mapping for `ZSSLCONNECTIONERROR` (-15), returning `"unknown error"`. The Rust client maps code -15 to `"ssl connection error"`.
- **Strict Chroot Path Validation and Prefix Stripping**: Under a configured chroot, the legacy client accepts relative paths, concatenating them directly (e.g., `foo` under `/app` becomes `/appfoo`), and maps empty paths to the chroot node itself. The Rust core validates that all client paths begin with `/`, returning `ZBADARGUMENTS` for malformed or relative paths prior to network transmission. Furthermore, chroot prefix removal on inbound server paths is enforced strictly on `/` path component boundaries, preventing partial string slicing when a znode path shares a prefix with the chroot.
- **Atomic Forced Teardown on Server Rebalancing**: When server lists update or single threaded transmit buffers fail, the legacy client abruptly closes the socket and assigns state 999 without draining queues, causing unsent requests to be replayed mid-frame on new connections and generating subsequent xid mismatches. The Rust core converts forced closures into clean teardowns: the socket terminates, in-flight work fails safely with `ZCONNECTIONLOSS`, watchers receive `CONNECTING` events, and only requests submitted after the reconfiguration are transmitted over the subsequent connection.
- **Fail-Safe Read-Only to Read-Write Transitions**: When switching from a read-only to a read-write server, the legacy client invokes `cleanup(zh, ZOK)`, generating synthetic success responses for unexecuted operations and corrupting application memory with half-initialized structures. The Rust core terminates active read-only connections cleanly, failing all pending in-flight requests and authentication tokens with `ZCONNECTIONLOSS` to prevent silent data corruption.
- **Fault-Tolerant DNS Resolution Outages**: A transient DNS resolution failure in the legacy client causes `zookeeper_interest` to return prematurely without populating output variables, disabling keepalive timers and leaving disconnected clients unable to retry known healthy IP addresses. The Rust core preserves the last known valid endpoint list during resolution outages, allowing keepalive timers, connection attempts, and socket polling to proceed uninterrupted while returning the resolver status code.
- **Enforced Timeouts During TLS TCP Connection**: The legacy client exempts TLS connecting states (`ZOO_SSL_CONNECTING_STATE`, 7) from receive timeouts, resulting in unbounded blocking when connecting to unresponsive TLS endpoints. The Rust core applies standard receive timeout deadlines to state 7, ensuring failover proceeds to alternative servers upon timeout expiration.
- **Defined Results in Synchronous Multi-Operations**: In the legacy client, invoking synchronous `zoo_multi` with an empty operation list on an expired session enqueues an unanswerable request, deadlocking the calling thread. The Rust core validates session state and argument counts upfront, returning `ZMARSHALLINGERROR` immediately. Furthermore, failed synchronous multi-operations guarantee that all individual result slots have their error fields populated with the failure code, eliminating uninitialized memory reads.
- **Independent Authentication Completion and Logging**: The legacy client completes all queued `zoo_add_auth` requests using the result code of the first received response, logging only the initial scheme. The Rust core establishes strict 1:1 correlation between authentication requests and responses, completing each token with its own result and logging the correct scheme.
- **Rejection of Empty Server Lists in `zoo_set_servers`**: Invoking `zoo_set_servers(zh, "")` in the legacy client installs an empty list, closes active connections, and returns `ZOK`, permanently stranding the handle. The Rust core rejects empty server strings immediately with `ZBADARGUMENTS`, leaving existing server configurations undisturbed.
- **Peer Address Availability in Read-Only State**: `zookeeper_get_connected_host` in the legacy client returns null during read-only sessions despite an active connection. The Rust client queries the underlying socket directly via `getpeername`, returning the connected host in both read-only and read-write states.
- **Detection of Truncated SASL Password Files**: When reading credentials without a custom callback, the legacy client silently truncates password files exceeding 1,023 bytes, causing subsequent authentication failures without diagnostic context. The Rust shim detects oversized initial lines and returns `SASL_BUFOVER` immediately.
- **Client Identity Support Without Passwords**: The legacy client silently ignores client certificate and key configurations when no password argument is provided. The Rust client successfully loads unencrypted client certificates and keys without requiring empty password strings, reporting errors only when encrypted keys lack necessary passphrases.
- **Teardown Preservation Across Application Exceptions**: When an application callback throws a C++ exception during connection teardown, the legacy client aborts the teardown sequence, leaking authentication tokens and reconnecting with stale queues. The Rust shim captures unhandled exceptions at the FFI boundary, marking the continuation abandoned and allowing subsequent driving calls to complete queue cleanup safely.
- **Safe State Suspension on Callback `longjmp`**: If an application callback invokes `longjmp`, skipping frame cleanup handlers, the Rust core suspends the handle safely: memory remains protected, subsequent driving calls return `ZINVALIDSTATE`, and the handle safely awaits final closure via `zookeeper_close()`, eliminating memory corruption and duplicate callback dispatches.

### Replacement of Undefined Behavior and Crash Paths with Defined Results

The legacy C client contains numerous paths where unexpected inputs trigger segmentation faults, infinite loops, or undefined behavior. The Rust drop-in client replaces these paths with defined, fail-safe error handling:

- **Null Pointer Defenses**: Passing null handles to legacy routines (`zoo_aget`, `zoo_aexists`, `zoo_aget_children`) dereferences uninitialized pointers. The Rust shim intercepts null handles across all entry points, returning `ZBADARGUMENTS` (or null from pointer accessors such as `zoo_client_id` and `zoo_get_context`, and 0 from `zoo_recv_timeout`). Passing null completion callbacks is permitted, cleanly skipping callback execution upon response arrival without crashing.
- **Bounded Buffer Memory Management**: Legacy synchronous get routines execute unchecked `memcpy` operations when passed null buffers or negative capacities. The Rust core validates output pointers and capacities prior to copying, reporting required data lengths while skipping invalid memory writes.
- **Validation Prior to Serialization**: Malformed request inputs (such as null value buffers with positive lengths or null ACL vectors in `zoo_set_acl`) fail safely at the serialization stage with `ZMARSHALLINGERROR` rather than crashing within Jute marshalling routines.
- **Strict Protocol Decoding and Buffer Bounds**: The legacy client parses response packets using buffer bounds exceeding actual frame lengths, occasionally dispatching uninitialized stack memory to callbacks. The Rust frame decoder enforces exact wire boundaries. Malformed frames immediately trigger connection loss. Negative frame lengths suspend read interest and await receive timeouts, preventing CPU-saturating allocation retry loops.
- **Isolated Transaction ID Allocation**: Transaction IDs (xids) are tracked per session handle and wrap within positive integer ranges ($1$ to $2^{31}-1$), reserving negative values for protocol control frames and eliminating process-wide xid collisions across concurrent sessions.
- **Typed Multi-Operation Wire Parsing**: Container creation responses within multi-transactions include server `Stat` structures that the legacy C client fails to parse correctly, causing subsequent sub-results to misalign. The Rust core decodes sub-responses dynamically according to the wire type declared in each sub-header.
- **Clean SASL State Re-initialization**: Legacy SASL negotiation states (`is_gssapi`, `is_last_packet`) persist across reconnection attempts, causing reconnected sessions to bypass negotiation or enter busy-wait loops. The Rust core resets negotiation state fresh upon receiving each `ConnectResponse`.
- **Thread-Safe SASL Password Buffering**: Legacy shared password structures allocated by `zoo_sasl_make_password_callbacks` are mutated without synchronization during authentication. The Rust shim isolates password retrieval within per-session private heap buffers, eliminating concurrent read/write races across handles.
- **Thread Safety and Lock Hierarchy Enforcement**: Legacy authentication completions and logging hooks execute while holding internal session mutexes, causing immediate deadlocks if application callbacks re-enter client APIs. The Rust architecture guarantees that no internal locks are held when invoking foreign application code, completely eliminating self-deadlocks on re-entrant calls.
- **Safe Signal Handling and Process Forking**: Transmissions suppress `SIGPIPE` signals across all platforms using `MSG_NOSIGNAL` or `SO_NOSIGPIPE`. In multithreaded environments, invoking `zookeeper_close()` from within a child process created via `fork()` avoids joining non-existent threads, closing open file descriptors while intentionally leaking memory to avoid disrupting parent session state.
- **Controlled Out-of-Memory Aborts**: Memory allocation failures during result construction or protocol processing trigger immediate process aborts with informative standard error diagnostics, eliminating undefined behavior and null-pointer dereferences inherent in legacy unchecked `malloc` calls.

### Transport Layer Security (TLS) Differences

Because TLS is powered by `rustls` rather than OpenSSL, the drop-in client exhibits specific, intentional behavioral differences on secure connections:

- **Protocol Baseline**: The implementation enforces a strict minimum protocol version of TLS 1.2, offering TLS 1.2 and TLS 1.3 exclusively. Obsolete protocols (SSLv3, TLS 1.0, TLS 1.1) and server-initiated renegotiation are completely unsupported.
- **Decoupling from System OpenSSL Policies**: Host OpenSSL configuration files (`openssl.cnf`), distribution crypto policies, and OpenSSL security levels have no effect on client cryptographic evaluation. Default trust anchors are derived from build-time paths or supplied environment variables (`SSL_CERT_FILE`, `SSL_CERT_DIR`).
- **Cryptographic Cipher and Key Acceptance**: Cryptographic evaluation adheres to modern `webpki` standards:
  - Supported public key algorithms are restricted to RSA (2048 to 8192 bits), ECDSA (NIST P-256, P-384; P-521 in AWS-LC builds), and Ed25519. Legacy algorithms (DSA, Ed448, RSA keys under 2048 bits) are rejected.
  - Signature algorithms require SHA-256 or stronger; MD5 and SHA-1 signatures are rejected.
  - Cipher suites are restricted to modern AEAD constructions with forward secrecy (ECDHE with AES-GCM or ChaCha20-Poly1305). Legacy CBC mode ciphers and static RSA key exchanges are rejected.
- **Strict Trust Store Parsing**: Hashed certificate directories (`by_dir`) are traversed strictly: filenames must match canonical lower-case subject hashes with contiguous numeric suffixes (`.0`, `.1`), and malformed certificate blocks within files are rejected.
- **Elimination of OpenSSL Linkage**: Shipped drop-in libraries do not link `libssl` or `libcrypto`. Applications requiring OpenSSL symbols for their own operations must link them explicitly.

### Linkage, ABI, and Build Differences

- **Encapsulation of Undeclared Symbols**: Approximately 95 undocumented, unexported internal symbols present in legacy static archives (`do_io`, `flush_send_queue`, `get_xid`, `addrvec_*`, `hashtable_*`) are excluded from drop-in archives, preventing symbol collisions with application code. Publicly declared APIs and Jute serialization symbols remain intact.
- **Runtime Dependencies**: Drop-in libraries link runtime dependencies required by the Rust standard library (`libgcc_s`, `libdl`, `libpthread`). These dependencies are reflected within generated pkg-config descriptors (`zookeeper_mt.pc`, `zookeeper_st.pc`).
- **Windows Runtime and Emulation Cleanup**: Static MSVC libraries (`zookeeper.lib`) omit flawed POSIX emulation symbols previously defined in `winport.c` (`pthread_*`, socket `close` overrides), avoiding conflicts with standard C runtime functions.
- **Monolithic Position-Independent Archives**: Static archives contain a single pre-linked object compiled as position-independent code (PIC), ensuring compatibility with modern hardened executables.

### Preserved Legacy Quirks

The following historical behaviors appear non-standard but are intentionally preserved in the drop-in libraries to maintain full bug-for-bug compatibility with existing C applications:

- **Process-Wide Random Reseeding**: During DNS resolution, the drop-in client reseeds the host process's libc random number generators (`srandom`, `srand48`) using entropy from `/dev/urandom`, matching legacy behavior.
- **Reconnection Polling Cadence**: Reconnection retry frequencies and host selection sequences reproduce historical C timing, including blocking `getaddrinfo` lookups on driving threads.
- **Return Code Precedence**: Invocations on closed or closing handles return `ZMARSHALLINGERROR` rather than `ZCLOSING`. Asynchronous submissions failing during serialization fold errors into `ZMARSHALLINGERROR`.
- **Permissive Path and Connection String Syntax**: Connection strings tokenize chroot prefixes at the first occurrence of `/` anywhere in the string, and ports are split at the final colon. Path validation permits control characters (0x1F, 0x7F) and high bytes (0x80+).
- **Asymmetric State Visibility**: Intermediate connection states, such as `ZOO_SSL_CONNECTING_STATE` (7), remain observable via `zoo_state()`. Recoverable connection drops report state 0, while callbacks executed during teardown observe states 3 or 5.
- **Reversed Authentication Wire Order**: Credentials registered during connection establishment or association are transmitted in reverse registration order, matching historical wire traces.
- **Permissive Framing Boundaries**: Inbound frames decode with structural leniency: existence checks decode as `SetData` responses, `sync` operations decode as create responses, and trailing unused bytes within responses are safely ignored.
- **Watch Registration Semantics**: Watchers maintain pointer identity based on function-pointer and context pairs (`fn`, `ctx`). Existence watches arm upon `ZNONODE` responses, and session lifecycle events fan out to all registered path watchers in hash table iteration order.

## Build and packaging

The ZooKeeper Rust client integrates directly into the existing multi-system build architecture of the ZooKeeper repository. Autotools and CMake produce the C compatibility libraries from either the legacy C codebase or the Rust drop-in core, while Maven coordinates repository-wide builds and reactor workflows. The standalone Cargo workspace houses all Rust crates and can be built independently to produce the native client library and associated recipes.

### Workspace layout and dependency rules

The Rust implementation resides within the `zookeeper-client/zookeeper-client-rust` directory, structured as a Cargo workspace and represented in Maven as a sibling module to `zookeeper-client-c`. In addition to the core crates (`zk-jute`, `zk-proto`, `zk-driver`, `zk-client`, `zk-ffi`, and `zk-recipes`), the workspace incorporates internal test and development infrastructure: `zk-testkit` for deterministic mock transports, `zk-fakeserver` for protocol test harnessing, `zk-xcheck` for cross-language validation, and `xtask` for administrative build automation. The fuzzing harnesses in `fuzz/` and the ABI structure layout validator in `crates/zk-ffi-layout` reside in independent Cargo workspaces with dedicated lockfiles to accommodate distinct toolchain requirements.

The C integration remains rooted in `zookeeper-client-c`. The C compatibility shims reside in `shim/`, ABI baseline specifications and pre-linking scripts are maintained in `abi/`, and custom CMake integration modules are located in `cmake/`. The legacy serialization source `recordio.c` and generated Jute C structures compile verbatim into both implementations. Public header files installed into system include directories are byte-for-byte identical between the C and Rust implementations. Shim headers remain strictly private and are never installed.

The implementation targets the Rust 2024 edition, establishing a minimum supported Rust version (MSRV) of 1.85.0. To avoid restricting downstream distribution packagers and local toolchain configurations, the repository does not commit a `rust-toolchain.toml` file. The only committed Cargo configuration is the `xtask` command alias in `.cargo/config.toml`. All other build-time configurations are supplied via `--config` command-line arguments, ensuring that packaging environments, offline repository layouts, and user-defined `RUSTFLAGS` retain complete control over compilation.

Crate dependencies maintain a strict unidirectional hierarchy: `zk-jute` forms the base, followed by `zk-proto` and `zk-driver`. Above the driver layer, `zk-client` and `zk-ffi` reside side by side, with `zk-recipes` layered exclusively on top of `zk-client`. No internal or external crate may depend on `zk-ffi`. The C foreign function interface static library is compiled strictly with `cargo build -p zk-ffi` rather than across the entire workspace (`--workspace`). workspace-level builds would unify optional feature sets, inadvertently pulling asynchronous runtimes like `tokio` into The synchronous C compatibility binaries. Builds with `--all-features` are strictly prohibited, as configuring multiple cryptographic TLS providers simultaneously triggers a compile-time assertion failure via `compile_error!`. At the foundational layers, `zk-jute` contains zero third-party dependencies, `zk-proto` depends only on `zeroize`, and `zk-driver` requires only `rustix`.

Third-party dependencies are restricted to a curated and audited whitelist. The repository commits a pinned `Cargo.lock`, and crates.io serves as the sole authorized upstream package registry:

- **Drop-in C Compatibility (Always Linked)**: `rustix` (configured with `std`, `net`, `event`, `pipe`, and `use-libc`), `libc`, `errno`, `bitflags`, `zeroize`, `linux-raw-sys` (on Linux), and `windows-sys` alongside its import library crates (on Windows). The `zk-ffi` crate links `libc` directly to invoke system `malloc` and `calloc` for heap-allocated C ABI structures.
- **TLS Dependencies (Drop-in and Native)**: `rustls` (with default features disabled, activating `std`, `tls12`, and the designated provider), `rustls-pki-types`, `rustls-webpki`, `untrusted`, `base64ct`, `pkcs8`, `pkcs5`, `der`, `spki`, `pem-rfc7468`, and `sha2`. When non-FIPS providers are configured, PKCS#8 encrypted private key support adds `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des`, and `md-5`. Their transitive dependency set is bounded by `subtle`, `digest`, `crypto-common`, `block-buffer`, `inout`, `cpufeatures`, `const-oid`, `salsa20`, `cfg-if`, `getrandom`, `typenum`, and `generic-array`.
- **Cryptographic Provider (Mutually Exclusive)**: Exactly one provider is selected when TLS is enabled: `ring` 0.17 (the default backend, utilizing `cc` during compilation), `aws-lc-rs` with `aws-lc-sys`, or `aws-lc-rs` with the `fips` feature enabled and `aws-lc-fips-sys`.
- **Native Rust Client Only**: `tokio` (configured with `rt`, `net`, `time`, `sync`, and `io-util`), `mio`, `socket2`, and `pin-project-lite` on Unix platforms; `tracing`, `tracing-core`, `futures-core`, `log`, and `serde`; `libgssapi` for Kerberos GSSAPI authentication; `md-5` and `getrandom` for DIGEST-MD5; `getrandom` for `zk-recipes`; `rustix` with process credential extensions; and `rustls-platform-verifier` behind the opt-in `tls-platform-verifier` feature.
- **Test and Build Infrastructure (Never Shipped in Production Binaries)**: `proptest`, `proptest-state-machine`, `arbitrary`, `loom`, `object`, `serde_json`, `ctest`, and `cc`.

All third-party crates must carry approved open-source licenses: Apache-2.0, Apache-2.0 with LLVM-exception, MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib, Unicode-3.0, or CC0-1.0 (with explicit attribution). Crate licenses outside this set require explicit project approval, as granted for the AWS-LC native wrapper crates. Prohibited libraries—including `openssl`, `openssl-sys`, `native-tls`, `rustls-pemfile`, and `webpki-roots`—are rejected unconditionally. Platform verifier dependencies (`rustls-native-certs` and `openssl-probe`) are permitted strictly beneath `rustls-platform-verifier`, which itself is isolated beneath the native `apache-zookeeper` crate to guarantee they can never link into C compatibility libraries. The `aws-lc-fips-sys` crate is permitted solely under `deny-fips.toml`.

These supply-chain constraints are enforced programmatically during continuous integration. For every supported target architecture and build configuration, `supply-chain/closure/` maintains an exact inventory of resolved crates for `zk-ffi`, checked against `cargo tree`. Parallel inventories validate the native feature combinations and `zk-recipes`. License compliance, package sources, and dependency prohibitions are verified across each feature configuration using `cargo-deny`. Supply-chain audits are validated via `cargo-vet`, which requires complete `safe-to-deploy` certification without exemptions for C drop-in closures, and for native closures prior to establishing the Rust implementation as the repository default.

### Producing the libraries

Compilation produces a dedicated static archive per target triple, build profile, and cryptographic provider configuration using explicit Cargo invocations:

```sh
cargo build -p zk-ffi --profile capi --locked \
      [--features tls,tls-<provider>] [--target TRIPLE] [--offline --config ...]
```

The `capi` compilation profile inherits from `release`, configuring `panic = "abort"`, `lto = "fat"`, `codegen-units = 1`, and `opt-level = 2` to match standard C distribution baselines, with integer overflow checks and debug assertions disabled. The `capi-dev` profile, selected via `--enable-debug` in Autotools or a `Debug` build type in CMake, inherits from the development profile while preserving `panic = "abort"`.

A single static archive, `libzk_ffi.a`, links into both the multi-threaded (`libzookeeper_mt`) and single-threaded (`libzookeeper_st`) compatibility libraries. Threading models are differentiated entirely at compile time within the C shim via the `THREADED` preprocessor definition. During handle initialization, the shim communicates its runtime model to the Rust core, configuring driver execution modes without altering ABI layouts. Support for TLS is encapsulated as a Cargo compile-time feature. A C shim compiled with TLS support references symbol signatures that exist exclusively when `zk-ffi` is compiled with the `tls` feature, guaranteeing that mismatched build configurations fail immediately at link time.

The intermediate link script `abi/prelink.sh`, driven by Make and CMake, consolidates the compiled shim objects and `libzk_ffi.a` into a single pre-linked object file. On ELF and MinGW platforms, it executes `ld -r` followed by `objcopy --keep-global-symbols`, supplying an export whitelist generated from the ABI specification. On Mach-O platforms, it executes `cc -r` using `-exported_symbols_list`. All unexported symbols—including the Rust standard library, internal `zkrs_*` primitives, Cyrus SASL helper routines (`zks_*`), and the cryptographic backend—are converted to local file visibility. This encapsulation prevents symbol collisions when client applications link their own instances of OpenSSL or third-party Rust libraries. The script then validates that visible symbols match the export whitelist exactly, ensures that no unresolved Rust or OpenSSL symbols remain, verifies that stack memory segments are non-executable (`GNU_STACK`), and confirms the absence of text relocations. Libtool ingests this object via a generated `.lo` wrapper.

Shared libraries preserve established ABI versions: `-version-info 2` produces `libzookeeper_mt.so.2` on ELF systems, and Mach-O targets produce `libzookeeper_mt.2.dylib` with compatibility and current versions pinned to 3.0.0. Linker flags such as `-Bsymbolic` and protected symbol visibility are prohibited. On ELF targets, shim objects compile with `-fsemantic-interposition` to ensure that applications can interpose functions like `zoo_awget` and observe invocations originating from within `zoo_get`. The Rust drop-in core never links `-lssl` or `-lcrypto`, while retaining `-lsasl2` when SASL authentication is enabled.

Static archives contain exactly one member: the position-independent, pre-linked object file. Exported symbols encompass the designated compatibility interface along with traditional archive-only utility routines: `serialize_*`, `deserialize_*`, `oa_*`, `ia_*`, and `close_zsock`. CMake additionally produces a single-member `hashtable` static archive containing no public symbols, preserving compatibility with build configurations specifying `-lhashtable`.

Runtime dependencies required by the Rust standard library are resolved dynamically during build configuration. Autotools and CMake execute `rustc --print native-static-libs` and append the resulting library flags to `LIBADD`, Libtool `.la` metadata, the `Libs.private` fields of pkg-config definitions, and the link interface of the CMake `zookeeper` target. The build generates and installs modern pkg-config definitions (`zookeeper_mt.pc` and `zookeeper_st.pc`) whose `Libs.private` fields specify native system libraries and `-lsasl2` (when SASL is enabled) without OpenSSL entries.

### Selecting what to build

The Autotools and CMake build systems evaluate host environment capabilities while honoring explicit user configuration flags.

Explicit requests for components are treated as mandatory. If the host environment cannot satisfy the prerequisites of a requested option, build configuration halts immediately with an explanatory diagnostic identifying the unsatisfied dependency. This strict enforcement governs `--with-rust-client=yes`, `--with-rust-client=side-by-side`, `-DWITH_RUST_CLIENT=ON`, `--with-rust-tls=yes`, explicit TLS provider selections (including `ring`), and related Rust-specific options.

When an implementation is not selected, the build defaults to `auto`. A host capable of compiling the Rust core builds the Rust implementation. A host lacking the required prerequisites falls back to compiling the traditional C client, logging an informational notice: `building the C client: <reason>`. However, if the user specifies Rust-specific configuration options while the implementation selection remains `auto`, prerequisite failures halt the build rather than falling back, since the C client cannot honor Rust-specific options.

Disabling the Rust client (`--with-rust-client=no` or `-DWITH_RUST_CLIENT=OFF`) bypasses all Rust prerequisite checks and ignores Rust-specific options. Maven relies on this behavior during full builds by passing standardized properties across every configuration. Specifying Rust-specific options while the Rust client is disabled triggers a configuration failure indicating that the options require `--with-rust-client=yes`.

A host environment is deemed capable of compiling the Rust core when all of the following conditions are met: the host operating system is not Cygwin, the Rust workspace is present and matches `ZOO_VERSION`, and either a valid precompiled static library is provided alongside an operational `rustc` compiler, or the host provides `rustc` and `cargo` at version 1.85.0 or later, cross-compilation configurations specify an explicit target triple, `ring` supports the target architecture (unless an alternative provider is specified), and all required crates are accessible via a local vendor directory, system package directories, or successful retrieval via `cargo fetch --locked`. The first unsatisfied condition supplies the diagnostic reason for fallback.

The default selection undergoes a single transition across project milestones. Prior to establishing the Rust implementation as the primary distribution default, `--with-rust-client` defaults to `no` and an unset `WITH_RUST_CLIENT` evaluates to `OFF`. Once validated, the repository defaults transition to `auto` and `AUTO`. Downstream users and packagers may continue to specify `no` or `OFF` across all releases to build the legacy C client, without encountering deprecation warnings.

CMake establishes a uniform minimum version across all configurations: `cmake_minimum_required(VERSION 3.17)`, superseding the legacy baseline of 3.5. This minimum is required by the target properties `MACHO_COMPATIBILITY_VERSION` and `MACHO_CURRENT_VERSION`, introduced in CMake 3.17 to manage Darwin shared library versioning for the `.2` binary release. Additionally, MSVC runtime library selection relies on `CMAKE_MSVC_RUNTIME_LIBRARY` and policy CMP0091 (introduced in CMake 3.15), which must be evaluated prior to the top-level `project()` declaration. The legacy C client build remains fully compatible under these modern policies, maintaining default `/MD` (or `/MDd` in Debug mode) runtime configurations and retaining `/W3` compiler warning flags.

Two corrective bug fixes applied to the C client build system benefit both implementations and represent the only modifications to the legacy C build scripts beyond version baseline adjustments:
- In `CMakeLists.txt`, the declaration `set(OPENSSL_ROOT_DIR,${WITH_OPENSSL})` contained an erroneous comma that rendered the setting ineffective. This is corrected to `set(OPENSSL_ROOT_DIR "${WITH_OPENSSL}")`. Furthermore, tests evaluating `WITH_OPENSSL` and `WITH_CYRUS_SASL` previously performed literal string comparisons against `OFF`. They are updated to standard boolean expressions so that values such as `off` and `NO` correctly disable the features.
- In `configure.ac`, feature probes for `SOCK_CLOEXEC` and IPv6 support relied on implicit `int main` declarations and undeclared functions, causing modern strict C compilers to fail the probes and silently disable the capabilities. These probes are updated to conform to valid C prototypes, and `SOCK_CLOEXEC` availability is evaluated directly at compile time.

### Options

Build options across Autotools, CMake, and Maven are aligned into structured configuration groups:

- **Implementation Selection**:
  - Autotools: `--with-rust-client=no|yes|side-by-side|auto`
  - CMake: `-DWITH_RUST_CLIENT=ON|OFF|AUTO`
  - Maven Property: `-Drust-client=no|yes|side-by-side`
  - Selects the active client implementation. The `side-by-side` mode is available exclusively in Autotools, installing the C client into `lib/` and the Rust core into `lib/zookeeper-rs/` under a shared include directory, allowing client applications to select the runtime via dynamic loader search paths.
- **Rust Workspace Location**:
  - Autotools: `--with-rust-workspace=DIR`
  - CMake: `-DZKRS_WORKSPACE=DIR`
  - Overrides the default filesystem location of the `zookeeper-client-rust` Cargo workspace.
- **Prebuilt Static Library**:
  - Autotools: `--with-rust-staticlib=PATH`
  - CMake: `-DZKRS_STATICLIB=PATH`
  - Maven: Passed automatically across reactor module dependencies.
  - Specifies the path to a precompiled `libzk_ffi.a` static archive, bypassing Cargo invocations during intermediate C build phases.
- **Cross-Compilation Target**:
  - Autotools: `--with-rust-target=TRIPLE`
  - CMake: `-DRust_TARGET=TRIPLE`
  - Specifies the target architecture triple passed to `cargo build --target` during cross-compilation.
- **TLS Support**:
  - Autotools: `--with-rust-tls=yes|no`
  - CMake: `-DWITH_RUST_TLS=ON|OFF`
  - Maven Property: `-Drust-tls=yes|no`
  - enables or disables TLS support in the Rust implementation independently of legacy OpenSSL discovery probes.
- **Cryptographic Provider**:
  - Autotools: `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips`
  - CMake: `-DZK_RUST_TLS_PROVIDER=ring|aws-lc-rs|aws-lc-rs-fips`
  - Maven Property: `-Drust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips`
  - Configures the cryptographic backend used for TLS operations, defaulting to `ring`.
- **Default Trust Store Locations**:
  - Autotools: `--with-rust-tls-default-paths=FILE:DIR`
  - CMake: `-DZK_RUST_TLS_DEFAULT_PATHS=FILE:DIR`
  - Configures hardcoded default system certificate bundle paths (`FILE`) and certificate directories (`DIR`) for TLS trust verification.
- **Vendored Crate Sources**:
  - Autotools: `--with-cargo-vendor=DIR`
  - CMake: `-DZOOKEEPER_CARGO_VENDOR=DIR`
  - Maven Property: `-Drust-cargo-vendor=DIR`
  - Directs Cargo to operate in offline mode using a pre-populated crate vendor directory.
- **System Distribution Crates**:
  - Autotools: `--with-system-rust-crates[=DIR]`
  - CMake: `-DZK_SYSTEM_RUST_CRATES=DIR`
  - Maven Property: `-Drust-system-crates=DIR`
  - Configures Cargo to compile against pre-packaged operating system distribution crates (defaulting to `/usr/share/cargo/registry` under Autotools). Specifying both vendored and system-crates options simultaneously is an error.
- **Compiler Warning Policy**:
  - Autotools: `--disable-werror`
  - CMake: `-DZK_WERROR=OFF`
  - Disables fatal compiler warnings (`-Werror` or `/WX`) when compiling the C shim and Cyrus SASL glue code.
- **Shared Library Generation**:
  - CMake: `-DZOOKEEPER_BUILD_SHARED=ON`
  - Instructs CMake to compile dynamic shared libraries in addition to static archives.

Standard C client configuration options—including `--with-openssl`, `--with-sasl`, `--with-syncapi`, `--with-sock-cloexec`, `--enable-debug`, `--enable-asan`, and `--enable-gcov`—retain their established semantics. The `--with-openssl` and `--with-sasl` options issue a warning and proceed without the requested feature if the corresponding development libraries are absent, even when specified. The `--enable-asan` and `--enable-gcov` flags instrument the C compatibility shim only. Rust instrumentation is managed through dedicated Maven profiles. When `aws-lc-rs` is selected, configuration probes verify the build prerequisites required by `aws-lc-sys`. `aws-lc-rs-fips` requires CMake and Go, and is restricted to Linux x86_64 and AArch64. Configured C compilers and archivers are exported to cryptographic build scripts via `CC_<triple>` and `AR_<triple>`.

### Hardening and warnings

Autotools and `ZkHarden.cmake` evaluate security hardening flags against the host compiler, applying accepted flags across all compilation modes, including release builds. Flags unsupported by the compiler are omitted with an informational diagnostic.

Compilation of the C compatibility shim applies `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=3`, `-fstack-protector-strong`, `-fstack-clash-protection`, `-ftrivial-auto-var-init=zero`, `-fno-strict-overflow`, `-Wformat=2`, `-Wformat-security`, and control-flow integrity via `-fcf-protection` on x86 or `-mbranch-protection=standard` on AArch64. Source fortification gracefully falls back to level 2 on toolchains where level 3 is unsupported (such as glibc 2.28) and is omitted entirely when optimization is disabled to prevent glibc compiler warnings. The explicit `-U` flag prevents macro collisions with compiler-defined defaults. The Cyrus SASL integration modules (`zks_sasl_cyrus.c` and `zks_sasl_cb.c`) are compiled with additional type safety checks via `-Wconversion` and `-Wsign-conversion`. When an external include directory is supplied via `--with-sasl=DIR`, it is included with `-isystem` to prevent third-party SASL headers from triggering internal warnings. Under MSVC, equivalent protections are enforced via `/GS`, `/guard:cf`, and `/sdl`.

Fatal compiler warnings (`-Werror` on GCC/Clang, `/WX` on MSVC) are enabled by default for all shim and SASL glue sources, and are enforced across continuous integration. Package maintainers may disable fatal warnings using `--disable-werror` or `-DZK_WERROR=OFF`. Legacy C client sources, `recordio.c`, and generated Jute C files retain their existing compiler flag configurations in all cases.

The compiled libraries do not emit GNU property notes for indirect branch tracking (IBT), shadow stacks (SHSTK), or branch target identification (BTI), as detailed in the differences analysis. Compiler hardening options for Rust will be adopted as they stabilize upstream in `rustc`.

### Maven

The Rust Maven module is fully integrated as an opt-in component that mirrors `zookeeper-client-c`. Configured with `pom` packaging, it does not deploy artifacts to remote artifact repositories. The root `pom.xml` activates the module within a profile named `rust`, triggered via `-Prust` or by defining the `rust-client` property on the command line, ensuring the static library is available when the C module compiles the Rust drop-in core. Standard reactor builds invoking `-Pfull-build` without the `rust` profile compile the legacy C client and do not require Cargo or Rust toolchains.

The default value of `rust-client` in Maven is `no`. Unlike the native build systems, Maven does not support `auto`. The property must evaluate strictly to `no`, `yes`, or `side-by-side`. A profile within `zookeeper-client-c/pom.xml`, active whenever `rust-client` is defined, introduces a `pom`-type dependency on the Rust module to ensure strict reactor ordering. Reciprocally, the Rust module declares `zookeeper-jute` and `zookeeper-server` as `provided` dependencies to guarantee proper build sequencing.

Reactor phase executions align with the C client build:

- `generate-sources`: The `zookeeper-jute` module generates the Rust record definitions alongside the C definitions.
- `process-sources`: Where the C module invokes `autoreconf` and `configure`, the Rust module validates the `rust-client` property, verifies that `cargo` and `rustc` meet the 1.85.0 MSRV, resolves TLS configuration via `abi/tls-probe.sh`, and executes `cargo fetch --locked`.
- `compile`: Executes two distinct Cargo invocations: the C ABI static library via `cargo build -p zk-ffi --profile capi`, followed by the workspace build via `cargo build --workspace --release --exclude zk-ffi`. Compiled artifacts remain isolated within `target/cargo`.
- `test`: Governed by the `cargo-test` profile (active unless `-DskipCargoTest` is specified), executing under the same environment variables as the C module's `cppunit` profile and interacting with `zkServer.sh`.
- `verify`: Executes the `rust-lint` profile (active unless `-DskipRustLint` is specified) and the opt-in `rust-supply-chain` profile, which runs `cargo-deny`, `cargo-vet`, `cargo-audit`, and closure comparison audits.

Advanced testing profiles `rust-test-coverage` and `rust-test-asan` mirror the legacy `c-test-coverage` and `c-test-asan` workflows.

During C module execution, Maven forwards `--with-rust-client`, `--with-rust-staticlib`, `--with-rust-tls-provider`, and `--with-rust-tls` to `configure` across all configurations, appending vendor or system-crates options whenever defined so that `make abi-check` resolves dependencies from identical crate sources. The C module links the prebuilt static library produced by the preceding Rust module execution and never invokes Cargo directly to recompile Rust library code, ensuring single-pass compilation. Because the Rust module executes before `configure` runs, `abi/tls-probe.sh` mirrors the OpenSSL detection logic of `configure`, and `configure` subsequently validates that the precompiled static library matches its own configuration decisions. The C module's sole invocation of Cargo occurs during `make abi-check` in the `verify` phase when `rust-client` is enabled. In standalone environments outside of Maven, Autotools and CMake invoke Cargo directly, managing target artifacts within their respective build directories.

### Jute generation

Rust serialization records are generated directly from `zookeeper.jute` through the established compilation pipeline. The generator class `org.apache.jute.compiler.RustGenerator` resides alongside `CGenerator` within `zookeeper-jute` and is invoked via `Rcc -l rust`. The execution `generate-Rust-Jute` in `zookeeper-jute/pom.xml` mirrors `generate-C-Jute`, directing generated output to `crates/zk-jute/src/generated/`, which is excluded from version control via `.gitignore`.

Code generation produces a single source file, `zookeeper_jute.rs`, defining public modules `pub mod data` and `pub mod proto` while omitting server-internal quorum, persistence, and transaction records. Each Jute record generates a Rust `struct` deriving `Debug`, `Clone`, `PartialEq`, `Eq`, and `Default`, and implementing the `Record` trait. Primitive mappings translate `int` to `i32`, `long` to `i64`, `boolean` to `bool`, `ustring` to `JString`, `buffer` to `JBuffer`, `vector<T>` to `JVec<T>`, and nested record references to their corresponding generated struct types. Encountering an unsupported Jute type halts code generation with a diagnostic identifying the offending record and field. Output generation is strictly deterministic, omitting timestamps, source paths, and non-deterministic hash iteration, ensuring byte-identical reproduction across runs without invoking `unsafe`, `unwrap`, or unchecked slice indexing.

The build script in `zk-jute` does not generate code dynamically. Consistent with the C build system, it verifies the existence of pre-generated sources, halting with an actionable error message if files are missing: `"jute files are missing! Run 'mvn -pl zookeeper-jute generate-sources' from the top level directory."`

### Versions

The root `pom.xml` serves as the authoritative single source of truth for project versioning. Version strings in `ZOO_VERSION`, CMake `project()`, and Autotools `AC_INIT` synchronize with the POM version through the existing execution `replace-cclient-files-during-release`. A companion execution on the root POM, `replace-rustclient-files-during-release` (with inheritance disabled), executes during release preparation and completion goals across every release, operating independently of the Rust reactor profile or local Cargo availability. It synchronizes `ZOO_VERSION`, `project()`, and `AC_INIT` replacements so that both codebases reflect the new release version before any module begins compilation. Additionally, it updates the parent version reference in the Rust module's `pom.xml`, the `[workspace.package] version` and `=x.y.z` dependency pins in the root `Cargo.toml`, and the workspace package entries across `Cargo.lock`, `fuzz/Cargo.lock`, and `crates/zk-ffi-layout/Cargo.lock`. Only the parent POM reference retains the `-SNAPSHOT` qualifier. These modified files are committed alongside the updated C metadata.

During compilation, `zk-ffi/build.rs` reads the version header specified by `ZK_VERSION_H` (exported by all three build systems) and halts compilation if the workspace package version diverges, preventing out-of-sync builds. Identical version comparisons are performed during Autotools and CMake configuration. Under `auto` selection, a version discrepancy causes the build to fall back to the C client with an explanatory notice. Shared library SONAME versioning remains decoupled from project release versions.

### Fetching, offline builds and distributions

Third-party crate dependencies are retrieved dynamically and are never committed to the source repository. Upstream crate archives are excluded from git and ASF source distribution tarballs, and the project publishes no standalone vendor archives. The repository commits a fully resolved `Cargo.lock`. Every build executes with `--locked`, requiring Cargo to verify package checksums against the lockfile and ensuring bit-for-bit adherence to the audited dependency closure.

Offline environments populate dependencies using standard Cargo workflows, as documented in the build instructions. The utility script `dev/vendor.sh` executes `cargo vendor --locked --sync fuzz/Cargo.toml --sync crates/zk-ffi-layout/Cargo.toml`, producing a consolidated vendor directory containing all dependencies resolved across every workspace lockfile. Passing `--with-cargo-vendor=DIR` appends `--offline` and configures source replacement parameters on the command line without modifying tracked files in the source tree.

Operating system distributions that package and maintain their own Rust crates are supported via system-crates mode, allowing distributions to patch dependencies like `rustls` or cryptographic backends and rebuild ZooKeeper without requiring a new project release. The directory path defaults to `/usr/share/cargo/registry` under Autotools and is specified in CMake and Maven. The build writes an isolated Cargo configuration into its output directory that remaps crates.io to the specified directory as a `directory` source (or a `local-registry` source if an `index/` subdirectory exists), passing the configuration via `--config` and `--offline`. Prior to compilation, the build executes `cargo generate-lockfile --offline` to regenerate `Cargo.lock` against the local package versions. This mode is intended for unpacked source releases or distribution package builds. The provided directory must supply all crates resolved by the workspace, including native client and test dependencies. The closure comparison tolerates semver-compatible version variances. Tooling like `cargo-vet` reports version discrepancies against the generated lockfile without failing the build, as it certifies specific package versions. Enforcements performed by `cargo-deny`, export checks, symbol visibility audits, and linked library assertions remain fully active.

Vulnerability management leverages the same supply-chain infrastructure. The continuous integration job `advisories` executes `cargo audit` and `cargo deny check advisories` across all dependency closures on every pull request and on a scheduled daily cadence. Closure inventories pinpoint precisely which shipped components are affected by published security advisories. Resolved vulnerabilities are distributed to downstream users through scheduled ZooKeeper releases with bumped dependency versions, while system-crates mode provides an immediate remediation path for Linux distributions.

### Source release

The Rust codebase is distributed exclusively through official Apache Software Foundation source releases. All crate manifests declare `publish = false`, preventing accidental publication to crates.io or Maven Central. The `apache-release` Maven assembly descriptor packages the complete source repository and requires no structural modifications. Build output directories (`target/`) and generated Jute directories are excluded from source tarballs, requiring builds from source archives to execute `mvn -pl zookeeper-jute generate-sources` prior to compilation.

Rust applications integrate the client by defining path dependencies pointing into unpacked source distributions for `apache-zookeeper` and `apache-zookeeper-recipes`. Internal implementation crates make no independent API stability guarantees. Internal workspace crates pin one another using exact `=x.y.z` version constraints matching the ZooKeeper release version. All newly introduced source files carry standard ASF license headers, and Apache Rat exclusion patterns are restricted to lockfiles, `cargo-vet` audits, closure inventories, ABI symbol definitions, test fixtures, and generated Jute outputs.

### The convenience lib package

Precompiled binary archives (`apache-zookeeper-<version>-lib.tar.gz`) are produced by `zookeeper-assembly` under the `-Pfull-build` profile. When compiled without the Rust module, the archive structure remains identical to legacy distributions. When the Rust module is active, the build executes with `-Pfull-build -Drust-client=side-by-side -Drust-tls=yes -Drust-tls-provider=ring`, activating the packaging descriptor `lib-package-rust.xml`. Enabling `rust-tls=yes` incorporates TLS capabilities into both implementations. In side-by-side mode, configuration halts if host OpenSSL development headers are absent.

The directory layout of binary distributions follows a staged transition model:
- **Initial Transition Stage**: The legacy C client libraries reside in the primary directory `usr/lib/`, while the Rust drop-in core is installed in `usr/lib/zookeeper-rs/`. Downstream applications opt into the Rust implementation by configuring `LD_LIBRARY_PATH` or `DYLD_LIBRARY_PATH`.
- **Default Stage**: Once the Rust core becomes the repository default, the Rust implementation is installed into `usr/lib/` and the legacy C client is relocated to `usr/lib/zookeeper-c/`, selected using equivalent dynamic loader configurations.

Because both implementations share identical SONAME versioning and public header trees, switching between implementations requires no recompilation or relinking of downstream applications.

Both distribution stages bundle a dedicated `LICENSE-binary` and the Rust workspace `NOTICE` alongside the C client's `LICENSE`. The utility script `dev/gen-license-binary.sh` generates `LICENSE-binary` by executing `cargo about` across the `zk-ffi` dependency closure, supplemented by curated attributions for components compiled into the Rust standard library. The script elects the Apache-2.0 license whenever dual-licensing is offered and reproduces the full license texts for ISC, BSD, and `ring` components. Official convenience binaries standardize on the `ring` cryptographic provider.

### What a release with Rust artifacts needs

Preparing an official ZooKeeper release that bundles compiled Rust artifacts requires the following operational prerequisites:

- Activating `-Prust` on the Maven `release:prepare` and `release:perform` invocations, ensuring the Rust module is present in the reactor so that the release plugin updates its version metadata and forwards the profile to child executions.
- Installing `cargo` and `rustc` at version 1.85.0 or later on the release environment host.
- Outbound network access to execute `cargo fetch --locked`, or an accessible pre-populated vendor directory supplied via `-Drust-cargo-vendor=DIR`.
- Optionally specifying `-DskipRustLint` to streamline release execution once audits have passed.
- Configuring the complete property set defined above if generating the precompiled binary convenience archive with the Rust drop-in core.
- Obtaining formal ASF legal approval for `aws-lc-sys` and `aws-lc-fips-sys` if the release includes binary distributions compiled with AWS-LC backends.

### Platforms and the Windows rule

Target architectures are organized into rigorous continuous integration tiers:

- **Tier 1 (Merge-Gating)**: Builds must pass on every pull request across x86_64 and AArch64 Linux glibc targets (covering Ubuntu 22.04, Ubuntu 24.04, and AlmaLinux 8 using GCC 8, modern GCC, and Clang), macOS on Apple Silicon (`aarch64-apple-darwin`), and Windows MSVC (`x86_64-pc-windows-msvc`) once Windows support is introduced.
- **Tier 2 (Nightly Gating)**: Nightly verification validates x86_64 and AArch64 Linux musl targets (`linux-musl`), 32-bit x86 Linux (`i686-linux-gnu`), macOS on Intel (`x86_64-apple-darwin`), Windows MinGW under Autotools (`x86_64-pc-windows-gnu`), and s390x big-endian Linux (`s390x-linux-gnu` via `Jenkinsfile-s390x`, testing the Rust workspace while host C libraries build against the legacy C client). Because MinGW lacks a baseline C reference implementation, its verification is restricted to symbol export assertions, link checks, and native Rust test suites.
- **Tier 3 (Best-Effort)**: Target platforms monitored without gating releases include ARMv7 (`armv7-gnueabihf`), 32-bit Windows MSVC (`i686-pc-windows-msvc`), and FreeBSD (`x86_64-unknown-freebsd`). Cryptographic builds utilizing `aws-lc-rs` are compiled and tested on Linux x86_64 and AArch64, matching its official FIPS validation boundaries. The Rust core does not support Cygwin. Environment checks fail fast on Cygwin, preserving the legacy C client build.

Under MSVC on Windows, CMake compiles `zookeeper.lib` matching the legacy C build configuration: multi-threaded when `WANT_SYNCAPI` is enabled, single-threaded when disabled, accompanied by a single-member `hashtable.lib` archive. Because MSVC does not support partial incremental linking (`ld -r`), `zk_ffi.lib` is merged into the output archive via `STATIC_LIBRARY_OPTIONS`, leaving Rust standard library and internal symbols visible within the static library. Applications that link third-party Rust libraries alongside ZooKeeper are advised to link dynamically against the DLL produced by `-DZOOKEEPER_BUILD_SHARED=ON`, which restricts exported symbols to the public compatibility interface. A unified C runtime (CRT) configuration governs the entire build: Rust's default dynamic CRT links against `/MD`, and when CMake selects the static CRT via `CMAKE_MSVC_RUNTIME_LIBRARY`, the build appends `-C target-feature=+crt-static`. Because upstream `rustc` links exclusively against release CRT variants, the MSVC build compiles the shim, `recordio.c`, and generated Jute C against the non-debug CRT in all configurations, mapping debug CRT requests to their release counterparts. Cyrus SASL support on Windows is compiled only when CMake detects local Cyrus installations, mirroring the C client, and is verified on a best-effort basis.

Windows releases adhere to a strict fallback rule. Functional verification must validate three core subsystems under MSVC: I/O polling via `rustix`, the loopback socket-pair waker mechanism, and compilation of `ring`. If any of these checks fail during a release validation cycle, Windows artifacts for that release ship exclusively using the legacy C client, with the decision noted in the release documentation. The build configuration check reports a failure on Windows under these circumstances, causing `AUTO` to fall back to the C client with an explanatory notice while explicit `ON` selections halt the build. The Rust drop-in core will be enabled on Windows in the first subsequent release where all Windows verification checks succeed.

## Verification and delivery

Prior to deployment into production environments, comprehensive verification must establish three core operational guarantees:
1. **Interface Equivalence**: The C drop-in compatibility libraries must expose an identical linker-visible ABI and compiler-visible API to the traditional C client.
2. **Behavioral Equivalence**: All application-observable interactions across successful and erroneous execution paths must behave identically, adhering to the documented bug fixes and minor architectural divergences.
3. **Memory Safety and Determinism**: The Rust core must guarantee absolute memory safety, deterministic execution under test, and complete panic-freedom when processing hostile or malformed network inputs.

The legacy C client serves as the definitive specification and reference baseline. Reference builds are compiled from a pinned commit on the `master` branch across four primary matrix configurations: with and without OpenSSL, crossed with with and without Cyrus SASL. The pinned commit also establishes the baseline log-site inventory extracted via `cargo xtask log-sites`. This commit baseline tracks upstream C modifications until the final commit preceding the transition to Rust as the repository default. Every black-box assertion must execute and pass against the reference build. An assertion failure against the reference indicates an invalid test harness. All reference artifacts and baseline traces are generated exclusively from the C client.

Core timing and ordering properties are verified deterministically against injected logical time. Protocol-level fault injection is driven through scripted mock servers (`zk-fakeserver`), completely avoiding arbitrary sleeps or heuristic delays.

### Interface conformance

Baseline symbol exports are recorded in `abi/symbols.tsv`, derived via `nm` inspection of the reference C build. Code generators synthesize explicit symbol export maps for Libtool, GNU `ld` version scripts, Darwin export lists, and Windows module definition files. The validation harness `cargo xtask abi check` operates independently of the generator, parsing ELF, Mach-O, and PE/COFF binaries across host and cross-compiled targets.

For shared libraries, the set of exported symbols must match the reference inventory exactly for each threading model and feature configuration. Exporting a superset constitutes a fatal validation failure. Symbols must match reference type, size, and mutability, retaining default symbol visibility (protected visibility is prohibited to ensure interposition of variables like `logLevel`). Shared libraries introduce no symbol versioning, preserving existing SONAMEs and symlink conventions. Dynamic dependencies (`DT_NEEDED`) must match the reference baseline, omitting `libssl` and `libcrypto` while accounting for documented runtime additions. Glibc symbol version requirements on AlmaLinux 8 are pinned to historical baselines. All internal Rust runtime symbols, `zkrs_*` FFI routines, `zks_*` SASL shims, and cryptographic engine routines are strictly encapsulated. No undefined OpenSSL symbols may exist. Equivalent symbol audits govern Mach-O and COFF binaries.

A complementary negative symbol inventory identifies routines that must remain unexported to ensure that invalid application linkages continue to fail at link time (including `close_zsock`, internal Jute serialization functions `serialize_*`, and synchronous API functions in the single-threaded build such as `zoo_remove_all_watches`, which the header declares unconditionally but only multi-threaded builds define). Static archives are verified to ensure that their single pre-linked object defines only expected globals. Isolation link tests verify that the static archive links cleanly alongside unrelated Rust libraries and applications defining symbols such as `hash` or `do_io`.

Binary ABI compatibility is verified using `libabigail` (`abidiff` and `abicompat`) against the pinned reference commit, `release-3.9.6`, and `release-3.8.7` (the only authorized release baselines). Comparisons against the pinned commit must exit with status zero. Against 3.9.6, the only permitted difference is the introduction of `zoo_version_str`. Against 3.8.7, permitted additions include `zoo_version_str` and `zoo_sasl_make_password_callbacks` (in SASL builds), alongside an expected suppression rule for the `readOnly` field in `struct ConnectRequest` and `struct ConnectResponse`. All ABI diffs filter definitions through public installed headers (`--headers-dir1` and `--headers-dir2`), excluding internal structures like `struct _zhandle`. Binary compatibility is validated using `abicompat` against `cli_st`, `cli_mt`, `load_gen`, C recipes, and scenario test binaries compiled against 3.9.6 headers.

Structure layout assertions probe public structure sizes, alignments, and field offsets across all supported targets, evaluating cross-compiled objects without target execution (`struct Stat` evaluates to 72 bytes on 64-bit architectures and 68 bytes on i686). Dedicated layout probes validate `zkrs.h` and the raw FFI module mirrors. Installed public headers must be byte-for-byte identical to the `include/` directory.

Linker and loader semantics are validated through dedicated runtime tests. Non-PIE executables are tested to verify copy relocations for `logLevel` and `ZOO_OPEN_ACL_UNSAFE`. Write attempts to `ZOO_ANYONE_ID_UNSAFE` in forked processes verify that string constants reside in read-only segments, inducing segmentation faults. Public C consumers—including `cli_st`, `cli_mt`, `load_gen`, C recipes, zkpython, zkperl, zktreeutil, and zkfuse—must compile and execute successfully against the drop-in library.

### Comparing behavior with the C client

Behavioral conformance is driven by an extensive suite of C and C++ scenario programs in `tests/conformance`, compiled once against original headers and linked dynamically by SONAME. A dual-execution harness executes each test twice, alternating runtimes solely via dynamic loader search paths (`LD_LIBRARY_PATH` or `DYLD_LIBRARY_PATH`). Each binary asserts and logs its loaded library identity, aborting if the expected library is not loaded.

Tests execute against `zk-fakeserver`, a memory-safe Rust mock server supporting TCP and TLS. Its behavior is deterministically governed by declarative test scripts and pseudo-random seeds, supporting standalone mock mode, custom rule overrides, and real-server proxying.

Execution tracers record every API invocation, return code, `errno` assignment, callback delivery, thread role, and log event. Callbacks perform runtime stack inspections to confirm the absence of Rust frames beneath user callback code, validating callback stack hygiene directly. Scenarios encompass session lifecycles, causal ordering, watch triggers, multi-transactions, chroot isolation, authentication handshakes, handle teardown from every thread role, callback exceptions and `longjmp` handling, read-only connections, and dynamic reconfiguration.

Trace comparisons evaluate execution equivalence across four distinct categories:
- **Single-Threaded Determinism**: Single-threaded scenarios and single-threaded executions of multi-threaded programs require exact, deterministic trace matches.
- **Thread-Role Scoping**: Multi-threaded scenarios match per thread role to accommodate non-deterministic thread scheduling inherent to the C runtime.
- **Permissible Concurrency Sets**: Race conditions permitted by the ZooKeeper contract validate that outcomes fall within declared valid sets (such as operations concurrent with `zookeeper_close` yielding `ZOK`, `ZCLOSING`, or `ZMARSHALLINGERROR`).
- **Two-Sided Defect Assertions**: Bug fixes employ two-sided differential assertions: verifying the defective legacy behavior against the reference C client while verifying the corrected behavior on the Rust drop-in core. For example, failover tests verify that the C client attempts servers in the defective pattern `0, 1, 0, 0, 0` and retries `1, wait, 1, wait` following session loss on server 0, while the Rust core strictly alternates.

Undefined behavior and crashes in the legacy C client are isolated in forked child processes under timeouts to record outcomes without halting test runs, while the Rust client asserts well-defined error handling. A machine-readable matrix maps every behavioral difference to an automated conformance test.

Scenarios execute three times on pull requests and fifty times nightly with randomized stress variants. Fuzz-assisted differential testing via `cargo xtask difftest` executes pseudo-randomized script sequences against both implementations, automatically minimizing discrepancies into permanent regression scenarios.

### TLS verification

Differential trust evaluation validates TLS behavior against OpenSSL 3.0 LTS and 3.5 LTS at frozen patch releases. Upstream OpenSSL source code is inspected to verify fatal alert emission and queueing semantics in `SSL_connect` and `SSL_read`.

A committed certificate test corpus, generated via an extended `ssl/gencerts.sh`, captures certificate chains, CA files, and default trust directories. Test cases cover X.509 v1 certificates, self-signed leaves, keyUsage, basicConstraints, name constraints, RFC 3779 IP/AS extensions, signature algorithms, and malformed DER structures. Test generators produce dual formats to account for OpenSSL 3.1 issuing v1 leaves versus OpenSSL 3.2+ issuing v3 leaves.

Each fixture maintains recorded validation verdicts from `openssl verify` (with host trust stores isolated) and reference client runs. Pull requests evaluate the drop-in verifier against frozen verdicts. Nightly runs re-record verdicts across both OpenSSL lines, failing on unexpected drift, and establish live TLS connections to `zk-fakeserver` to compare handshake outcomes, return codes, state transitions, and diagnostic logs.

Trust evaluation enforces a non-negotiable security invariant: under standard OpenSSL configurations, any case where the Rust core accepts a certificate rejected by either OpenSSL reference line represents a critical blocking defect that cannot be waived or cataloged as an accepted difference. Cases where Rust rejects a certificate accepted by OpenSSL are permitted only if explicitly documented in the architectural differences. Mismatched rejections must produce identical return codes and log messages.

Extended TLS validation evaluates private key formats, trust inputs, environment variables, Windows filesystem path encodings, and crypto-policy decoupling. Suites execute across `ring`, `aws-lc-rs`, and `aws-lc-rs-fips` on Linux x86_64 and AArch64.

### The existing C test suite

The legacy CppUnit test suite is retained to validate the legacy C client. The existing suite cannot link directly against the Rust drop-in core because it relies on linker symbol wrapping (`--wrap`) for internal functions (`flush_send_queue`, `get_xid`, `deliverWatchers`) and mocks system `libc` and `pthread` functions.

A dedicated build target (`make check-public` in Autotools, accompanied by CMake equivalents) compiles the public API test suite with `-DZKTEST_PUBLIC_ONLY`, encompassing 57 test cases across `TestClient`, `TestMulti`, `TestClientRetry`, `TestLogClientEnv`, `TestReadOnlyClient`, `TestSASLAuth`, and `TestReconfigServer`. These tests link the shared library dynamically and run against real ZooKeeper server instances. When building exclusively with the Rust core, `make check` and the CMake test targets execute this public subset while skipping white-box binaries. Legacy white-box tests are converted into conformance scenarios or native Rust unit tests. The `testSSL` suite fixture is updated to omit authority key identifiers on leaf certificates, ensuring consistent validation across both runtimes.

### Testing the Rust code

Protocol testing leverages `zk-testkit` to drive the core state machine deterministically against injected logical time. The test kit incorporates an in-memory transport capable of simulating partial frame writes, fragmented TCP packets, and sudden connection resets, alongside an in-process `rustls` peer and mock server model.

Property-based testing via `proptest` asserts protocol invariants against an independently implemented, intentionally naive reference model sharing zero code with production crates:
- **Exactly-Once Completion**: Every accepted completion token resolves and delivers exactly once.
- **Ordered Transaction Identifiers**: Transaction identifiers (`xid`) increase monotonically, and server replies match requests in strict submission order.
- **Deterministic Teardown Sequencing**: Connection teardown delivers the session state event first, followed by failed request completions in submission order, followed by authentication failure notifications.
- **Frame Boundary Invariance**: Protocol framing remains atomic regardless of network chunking or packet fragmentation, and arbitrary input chunking does not alter state machine transitions.
- **Clean Forced Closures**: Forcible socket closures fail precisely the set of captured inflight operations without leaking uncompleted futures.
- **Panic-Freedom**: The protocol state machine never panics across arbitrary sequences of network inputs.

Property test suites execute 512 iterations on pull requests and 200,000 iterations in nightly continuous integration runs.

Serialization codecs are validated three ways:
1. **Java Codec Validation**: Java integration suites serialize seeded instances of all Jute records, which `zk-jute` deserializes and re-encodes, verifying byte-for-byte wire fidelity.
2. **Bidirectional C Cross-Verification**: The cross-verification crate `zk-xcheck` compiles the legacy C implementations `zookeeper.jute.c` and `recordio.c` across multiple architectures (including big-endian `s390x`), confirming reciprocal decoding between Rust and C.
3. **Algorithm Port Parity**: The `zk-xcheck` harness compiles legacy C implementations of `hashtable.c`, `addrvec.c`, `isValidPath`, and `getaddrs`, asserting identical hash table iteration sequences, address vector cursor steps, server randomization shuffles, and path validation rules across all UTF-8 strings up to three bytes.

Comprehensive fuzzing suites exercise protocol handling:
- Cargo fuzz targets cover Jute record decoding, frame boundaries, connection handshakes, multi-transaction payloads, end-to-end session streams, connect string parsers, path normalization, SASL frame decoding, and TLS certificates, trust stores, and data streams.
- Dedicated C libFuzzer harnesses exercise outbox drainage, Cyrus SASL glue, and password callback synchronization.
- An AddressSanitizer-instrumented fuzzing harness feeds randomized server replies into the raw FFI result builders.
Fuzzing targets execute for 120 seconds on pull requests and one hour in nightly runs.

Formal verification and concurrency audits apply rigorous tooling:
- Miri executes with strict pointer provenance checks across core protocol crates, `zk-driver` (without crypto backends), and production modules of `zk-ffi`. The raw FFI result builders execute through Miri's libc memory allocator shims, verifying the allocation and deallocation of every C ABI structure.
- `loom` systematically validates concurrency interleavings across completion queue shutdown, handle closure from distinct thread roles, exactly-once delivery of completion tokens, POSIX thread lifecycle join/detach transitions, and SASL mutual exclusion serialization.

### Sanitizers and static analysis

Dynamic sanitizers—AddressSanitizer (ASan), UndefinedBehaviorSanitizer (UBSan), and LeakSanitizer (LSan)—run continuously on pull requests across the C shim, scenario suites, and public API tests. Nightly verification expands coverage by instrumenting Rust crates and executing ThreadSanitizer (TSan) to detect data races. Valgrind memory audits execute on a weekly cadence.

The Cyrus SASL integration modules receive specialized security auditing:
- Static header scans verify that `<sasl/sasl.h>` includes are strictly confined to the designated glue sources.
- Binary symbol audits confirm the exclusion of unsafe string routines (`strcpy`, `sprintf`) and verify that writable globals are restricted to the password synchronization mutex.
- MemorySanitizer (MSan) audits execute against an MSan-instrumented Cyrus SASL library.

All C shim sources compile with `-Wall -Wextra -Werror -Wformat=2` and pass static auditing via `clang-tidy`, `scan-build`, `gcc -fanalyzer`, and GitHub CodeQL.

### Supply chain

Crate supply chain governance is enforced through automated checks of closure inventories, `cargo-deny` rules, `cargo-vet` certifications, and vulnerability advisories.

Interface boundaries are strictly maintained: the C FFI layer (`zk-ffi`) is forbidden from referencing `zk-client`, `tokio`, or OpenSSL bindings. Repository-wide source audits verify that `unsafe` keywords are strictly forbidden across production crates outside the isolated `abi::raw` module. Introducing a new `unsafe` block requires an entry in `UNSAFE.md` detailing the invariant, safety rationale, and companion verification test.

### Performance

Automated performance benchmarking uses `zkbench`, a standalone C benchmark compiled once and linked against candidate implementations via dynamic loader paths. Tests execute interleaved on dedicated bare-metal infrastructure with locked CPU clock frequencies, computing median throughput and latency alongside bootstrap 95% confidence intervals. Performance budgets are violated only when the entire confidence interval falls outside acceptable bounds:
- **Synchronous Get Latency**: Synchronous `zoo_get` latency must remain within 5% of the C baseline at p50 and 10% at p99.
- **Throughput Baselines**: Pipelined throughput, 1 MiB large payload throughput, and watch storm event processing must achieve at least 95% of C baseline throughput.
- **Operational Overhead**: Large multi-transactions, reconnection recovery, individual `zoo_aget` invocations, and single event loop turns must remain within 10% of the C baseline.
- **Idle Efficiency**: Idle client handles must exhibit zero measurable excess CPU utilization and zero spurious waker wakeups.
- **Memory Consumption**: Resident set size must remain within 1.25x of the C client, with per-handle allocations bounded by 256 KiB plus 2 MiB fixed overhead.
- **Security Protocols**: TLS connection handshake latency must remain within 25% of the C baseline, TLS throughput must achieve at least 90%, and DIGEST-MD5 authentication handshakes must remain within 10%.

Native Rust client metrics are logged for performance tracking without gating C drop-in acceptance.

### Real servers

Integration test suites execute against official Apache ZooKeeper Java server releases spanning versions 3.5.10, 3.6.4, 3.7.2, 3.8.7, 3.9.6, and `HEAD`.

Tests evaluate a three-way matrix across the legacy C client, the Rust C drop-in core, and the native Rust client. Test coverage encompasses core operations, TTL and container nodes, read-only mode, dynamic cluster reconfiguration, DIGEST-MD5, Kerberos GSSAPI, TLS mutual authentication, ensemble failover, and session expiration.

Watcher compatibility tests verify that mixing persistent and standard watches on identical paths causes subsequent watch registrations to replace prior registrations on server versions 3.6 through 3.8, while coexisting concurrently on server versions 3.9 and later.

### Infrastructure

Continuous integration and verification require dedicated infrastructure resources:
- A dedicated performance test runner with fixed CPU clock frequencies and background process isolation.
- AArch64 Linux runners and s390x big-endian build nodes supporting Rust 1.85.0 or later.
- A container image containing the precompiled C reference build linked against frozen OpenSSL LTS releases, with CLI tools.
- A FIPS validation build container with CMake and Go on Linux x86_64 and AArch64.
- A system-crates container mirroring Debian and Fedora distribution repository layouts.
- A containerized MIT Kerberos KDC, an MSan-instrumented Cyrus SASL environment, and RHEL-family images configured with `FUTURE` and `LEGACY` crypto policies.
- Windows MSVC build runners configured with CP-1252 and CP-932 locales.
- AlmaLinux 8, Libtool 2.4.6 through 2.5.x, and MinGW-w64 build environments.
- Build-selection test containers missing individual dependencies (Cargo, modern rustc, crate sources, baseline CMake, OpenSSL/Cyrus headers).
- Apache Curator Java test harness dependencies integrated into the Maven classpath.

### The order of work

Development and delivery proceed through phased milestones, where each stage concludes upon repeatable, flake-free nightly verification:

1. **Reference Baselines and Native Client Core**: Derive symbol maps, layout definitions, and dynamic library requirements from the reference C client. Implement `RustGenerator` within `zookeeper-jute`, deliver `zk-jute`, build `zk-proto` with `zk-testkit` and `zk-xcheck`, develop `zk-driver` (threaded, single-threaded, and Tokio variants), implement `zk-client`, and establish `zk-ffi` step mechanics against mock hosts. Delivers a fully operational native client independent of C libraries: verified codecs, property tests, clean Miri/loom verification, fuzz targets without regressions, multi-server compatibility, and license/supply-chain compliance.
2. **Multi-Threaded C Drop-in Core (Linux and macOS, Non-TLS/SASL)**: Implement C compatibility shims, passing ABI checks with zero discrepancies, executing all multi-threaded scenario tests, validating bug fix assertions (excluding SASL failure and TLS forced-close), passing public CppUnit suites, validating external C consumers, passing dynamic sanitizers, satisfying performance budgets, and demonstrating object pre-linking on ELF and Mach-O.
3. **Single-Threaded C Drop-in Core and Static Archives**: Deliver linker isolation tests, CMake build products, single-threaded scenario suites, scripted `cli_st` verification, and nightly CI on Linux and s390x architectures.
4. **TLS and Cyrus SASL Integration**: Freeze OpenSSL reference releases, conduct fatal alert evaluation, implement the Rust TLS verifier, private key decoders, hardened SASL integration modules, and remaining defect fix tests. Delivers the certificate verification corpus with zero false accepts, regression tests for documented differences, real-server security integration tests, binary dependencies without OpenSSL symbols, verified TLS performance budgets, and system-crates packaging.
5. **Initial Opt-In Release**: Publish the first release featuring the Rust core, disabled by default and activated via `--with-rust-client=yes` or `side-by-side`. Release notes document behavioral differences and provide linking guidance for under-linked applications.
6. **Windows Platform Support**: Deliver MSVC `zookeeper.lib` in multi-threaded and single-threaded configurations, `SOCKET`-typed interest maps, `WSAStartup` parity, scenario test subsets, native client support on the threaded driver, MinGW pre-link validation, and nightly `x86_64-pc-windows-gnu` CI. If MSVC verification fails, invoke the Windows fallback rule, deferring Windows Rust support to a subsequent release. Cyrus SASL on Windows compiles when detected without blocking the milestone.
7. **Repository Default Transition**: Transition repository defaults to `auto` and `AUTO` once performance budgets are confirmed on dedicated bare-metal infrastructure, build selection probes pass under the new default, and `cargo-vet` certifies native closures. Binary convenience archives update their internal directory layouts. Downstream users retain the ability to compile the legacy C client via `--with-rust-client=no` without deprecation warnings, and operational rollbacks require no relinking.
8. **Distributed Recipes**: Deliver `zk-recipes` providing `DistributedLock`, `LeaderLatch`, and `TreeCache`, validated against mock servers and live clusters. JUnit integration tests verify distributed lock mutual exclusion and leader election ordering interoperating directly with Apache Curator instances running in Java processes on identical ZooKeeper znodes.

