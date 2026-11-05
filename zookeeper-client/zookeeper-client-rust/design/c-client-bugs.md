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

# ZooKeeper C Client: Defect Analysis and Remediation Guide

## Summary

This defect analysis catalogs a total of **101 issues** evaluated in the Apache ZooKeeper C client at commit `87e85f28e`: **97 actionable defects** across 8 operational and architectural defect classes, and **4 preserved architectural behaviors** retained for backward compatibility.

| Defect Class | Issue Count | Primary Operational Impact |
| :--- | :---: | :--- |
| [Wrong results and data-integrity hazards](#wrong-results-and-data-integrity-hazards) | 15 | Silent data corruption, false successes for unexecuted requests, queue leaks across reconnections, chroot escapes, and transaction desynchronization |
| [Hangs and failed failover](#hangs-and-failed-failover) | 25 | Deadlocks, infinite reconnection loops, 100% CPU busy-wait spinning, unbounded TLS loops, and server-list failover starvation |
| [Crashes and memory errors](#crashes-and-memory-errors) | 23 | Missing NULL checks, buffer over-reads during Jute deserialization, unhandled SIGPIPE signals, format string vulnerabilities, and lifecycle crashes |
| [Races](#races) | 13 | Unsynchronized shared state, stale file descriptors, wakeup pipe saturation, and multithreaded teardown/SASL concurrency hazards |
| [Silently lost settings](#silently-lost-settings) | 5 | Dropped reconfigurations, ignored port/topology updates, and silent truncation of SASL credentials |
| [Misleading diagnostics](#misleading-diagnostics) | 7 | Inaccurate error codes, transposed transaction IDs in logs, invalid latency arithmetic, and misleading `errno` reporting |
| [Build System Defects](#build-system-defects) | 4 | CMake and Autotools compilation/probe failures, syntax errors, and static archive symbol collisions |
| [Windows Platform Defects](#windows-platform-defects) | 5 | Flawed pthread emulation, I/O multiplexer issues, missing symbols, and loopback socket hijacking vulnerabilities |
| **Subtotal: Actionable Defects** | **97** | **Identified bugs requiring C client patch remediation** |
| [Preserved Architectural Behaviors and Intentional Semantics](#preserved-architectural-behaviors-and-intentional-semantics) | 4 | Established behaviors preserved for backward compatibility (omission of TLS hostname validation, shutdown flush semantics, child process fork handling, and PRNG reseeding) |
| **Total Cataloged Issues** | **101** | |

**Breakdown by Defect Class:**

- **[Wrong results and data-integrity hazards](#wrong-results-and-data-integrity-hazards) (15 defects):** High-severity correctness failures where the client delivers incorrect results or corrupts protocol state. Highlights include read-only to read-write transitions synthesizing false `ZOK` completions for unexecuted requests, forced socket closures leaking pending queues into subsequent connections, transaction ID wrap-around misrouting responses, and chroot validation omissions exposing root namespace znodes.
- **[Hangs and failed failover](#hangs-and-failed-failover) (25 defects):** Availability defects that cause application stalls or CPU exhaustion. Highlights include infinite reconnection storms when watch sets exceed `jute.maxbuffer`, double cursor advancement starving server failover lists, deadlocks caused by lock inversions and synchronous API invocations inside callbacks, and unthrottled spin loops during TLS connection failures or SASL negotiation.
- **[Crashes and memory errors](#crashes-and-memory-errors) (23 defects):** Memory safety defects leading to SIGSEGV, process aborts, or undefined memory access. Highlights include missing NULL pointer checks across public API entry points and completion dispatchers, buffer over-reads during Jute packet deserialization, unsuppressed `SIGPIPE` signals terminating host processes, out-of-bounds logging writes, and use-after-free/double-close hazards during handle destruction or `dlclose()`.
- **[Races](#races) (13 defects):** Concurrency vulnerabilities between application threads and the background I/O or completion threads. Highlights include unsynchronized server-list manipulation during read-only probing, global cross-session hint leakage, race conditions between `zookeeper_close()` and asynchronous submission, and thread-safety violations in SASL negotiation.
- **[Silently lost settings](#silently-lost-settings) (5 defects):** Silent configuration drops where client parameters are ignored without error or feedback. Highlights include dropping port numbers during cluster reconfiguration comparisons, accepting empty server strings and leaving unconnectable handles, and silently ignoring TLS certificates when passwords are omitted.
- **[Misleading diagnostics](#misleading-diagnostics) (7 defects):** Observability defects that obscure root causes or produce false alarms. Highlights include reporting "unknown error" for TLS failures, transposing expected and received transaction IDs in log messages, subtracting monotonic time from wall-clock time in ping latency computations, and leaking parent process IDs in log lines after `fork()`.
- **[Build System Defects](#build-system-defects) (4 defects):** Build and toolchain issues preventing clean compilation or linking. Highlights include CMake search path syntax errors, broken Autotools `SOCK_CLOEXEC` feature probes on modern compilers, and unnamespaced symbol pollution in static libraries.
- **[Windows Platform Defects](#windows-platform-defects) (5 defects):** Platform-specific flaws in the Windows compatibility layer (`winport`). Highlights include flawed pthread emulation, omission of `exceptfds` in select-based polling, missing SASL symbols at link time, and an insecure loopback signaling socket vulnerable to connection hijacking.
- **[Preserved Architectural Behaviors and Intentional Semantics](#preserved-architectural-behaviors-and-intentional-semantics) (4 items):** Technical analysis of behaviors that appear anomalous but constitute intentional architectural contracts or compatibility requirements: omitting TLS hostname verification, flushing pending requests upon `zookeeper_close()` before sending `CLOSE_OP`, child process session teardown semantics after `fork()`, and process-global PRNG reseeding during address resolution.

## Wrong results and data-integrity hazards

### Read-only to read-write transitions trigger false successes, memory corruption, or assertion failures

When a client operating in read-only mode (`ZOO_READONLY`) discovers a read-write server via background probing, `zookeeper_interest` invokes `cleanup(zh, ZOK)` (`zookeeper.c:2783-2803`, line 2797). Passing `ZOK` causes `free_completions` (`zookeeper.c:2015-2079`) to synthesize a zero-length success response (status code 0 with an empty payload) for every pending operation across the session. Each completion callback interprets this empty response as a successful operation:
- Void operations (such as `zoo_adelete` and `zoo_aset_acl`) report `ZOK` to application callbacks despite never having reached or executed on the server.
- String completions receive a `NULL` path pointer. Under a chroot configuration, this causes an immediate null-pointer dereference inside `sub_string(NULL)`.
- Stat, data, child-list, string-stat, and ACL completions operate on uninitialized stack structures, subsequently freeing invalid pointers when invoking deallocation routines.
- Asynchronous multi-operations fail an assertion in `deserialize_multi` (`zookeeper.c:3308-3337`) or dereference `NULL` in release builds where assertions are disabled.
- Synchronous waiters receive a return code of 0, prompting wrappers such as `zoo_create2_ttl` and `zoo_wget` (`zookeeper.c:5587-5595`, `5661-5671`) to copy garbage data out of their internal unions (`zk_adaptor.h:87-110`). For instance, a synchronous `zoo_create2` call returns `ZOK` with its stat structure populated by the pointer and length bytes of the path buffer due to union aliasing.

**Remediation Design:**
The cleanup invocation in `zookeeper_interest` must pass `ZCONNECTIONLOSS` rather than `ZOK`: `cleanup(zh, ZCONNECTIONLOSS)`. The client's existing failure dispatch logic already handles every completion type safely under this status—passing null pointers cleanly, invoking `cleanup_failed_multi` for multi-operations, and suppressing union memory copy-outs in synchronous wrappers. The transition continues to emit `ZOO_CONNECTING_STATE` as intended. In addition, the stale socket descriptor returned on this path must be reset as detailed in the descriptor lifetime analysis.

**Compatibility and Verification:**
Applications with operations in flight during a server transition observe `ZCONNECTIONLOSS` instead of silent data corruption, crashes, or false positives. The public API and ABI remain untouched. Regression testing requires a mock ensemble pairing a stalled read-only server with a read-write node responding to `"isro"`, validating that operations of every completion variant fail cleanly with `ZCONNECTIONLOSS` under address and memory sanitizers.

### Forced socket closures leak request queues across reconnections

When dynamic cluster reconfiguration alters the server list (`update_addrs` at `zookeeper.c:1219-1226`) or when a socket flush fails during output transmission (`nonblocking_send` at `zookeeper.c:4153-4162`), the client closes the underlying file descriptor and sets `zh->state = ZOO_NOTCONNECTED_STATE`. However, neither path invokes `cleanup()`. As a result, the `sent_requests` and `to_send` queues remain intact without emitting connection state events, and all queued work is carried over into the next socket connection.

This queue persistence introduces multiple protocol failure modes upon reconnecting to a new server:
- Unsent frames in `to_send` are dispatched directly to the new server. If the leading buffer was partially sent prior to disconnection, transmission resumes from `curr_offset`, injecting a truncated frame fragment into the new TCP stream.
- In-flight requests remaining in `sent_requests` retain xids negotiated with the prior connection. When the new server replies to newly issued frames, mismatched transaction identifiers trigger a terminal `ZRUNTIMEINCONSISTENCY` teardown (`zookeeper.c:3613-3626`).
- If the application remains idle after reconnecting, stale entries at the head of `sent_requests` inhibit keepalive pings because `zookeeper_interest` (`zookeeper.c:2771`) only transmits pings when `sent_requests` is empty. The new connection consequently dies of `ZOPERATIONTIMEOUT` after $2T/3$.
- In multithreaded runtimes, `zoo_set_servers` initiates this socket closure from an application thread while the background I/O thread is actively reading or writing, creating concurrency hazards and permitting `SSL_free` to execute concurrently with active `SSL_read` or `SSL_write` operations.

**Remediation Design:**
Forced disconnections must be restructured as complete teardowns orchestrated exclusively on the I/O thread:
1. When invoked from application threads, API functions should transition the handle into a pending-disconnect state, set an internal flag, and signal the I/O event loop without directly closing descriptors or destroying TLS contexts.
2. On its subsequent event loop iteration, the I/O thread closes the active descriptor, dispatches a `ZOO_CONNECTING_STATE` watcher notification if the session was previously established, and invokes `cleanup(zh, ZCONNECTIONLOSS)` to purge outstanding queues.
3. For single threaded flush failures during asynchronous calls, completion dispatch must be deferred to the subsequent `zookeeper_interest` or `zookeeper_process` pass to prevent callback re-entrancy within submission APIs.
4. Any requests queued after the disconnection trigger must ensure their buffer offsets are reset (`curr_offset = 0`) before transmitting across the new transport.

**Compatibility and Verification:**
Applications observe a standard `ZOO_CONNECTING_STATE` transition and receive `ZCONNECTIONLOSS` for pending operations instead of undefined frame splices or runtime protocol failures. API and ABI structures remain unmodified. Verification requires mock-server testing that injects server reconfigurations and flush failures while requests are actively queued across saturated socket buffers.

### Interrupted session establishment delivers invalid session state 999

During connection setup, `finalize_session_establishment` (`zookeeper.c:2986-3008`) marks the session connected, stages watch restoration frames (`SetWatches`), and invokes `send_auth_info` (`zookeeper.c:2212-2230`), which acquires the authentication lock. Only after `send_auth_info` returns does the routine construct and dispatch the session connection event, evaluating `zh->state` dynamically at line 3002.

In multithreaded environments, the I/O thread can block while awaiting the authentication lock. If an application thread invokes `zoo_set_servers` during this window and the active server is omitted from the updated ensemble, `update_addrs` immediately closes the descriptor and assigns `zh->state = ZOO_NOTCONNECTED_STATE` (numerical value 999). When the authentication lock is subsequently released, the I/O thread unblocks, queues redundant authentication frames onto the closed connection, and dispatches a session event carrying state 999. Application watchers monitoring state transitions observe an undocumented, invalid session state, missing both connected and connecting notifications. Leftover watch restoration and authentication packets then leak into the subsequent connection, causing duplicate submissions to the new server.

**Remediation Design:**
1. In `finalize_session_establishment`, capture the intended state (`ZOO_CONNECTED_STATE`) in a stack-local variable and pass that local value to the event constructor, preventing concurrent mutations of `zh->state` from corrupting the notification payload.
2. In `send_auth_info`, verify handle connectivity after acquiring the authentication lock; if a forced disconnection has occurred, suppress frame queuing since the subsequent connection establishment cycle will retransmit all authentication data.
3. If disconnection occurs before the session event is dispatched, suppress the connected notification entirely, relying on the teardown mechanism to emit `ZOO_CONNECTING_STATE`.

**Compatibility and Verification:**
Watchers are protected from receiving invalid state 999 events, maintaining predictable state machine semantics. ABI and API stability are preserved. Verification can be performed by holding `zoo_lock_auth` across the handshake response, modifying the server list via `zoo_set_servers`, and confirming the absence of state 999 notifications.

### Server list changes during authentication failures erase ZOO_AUTH_FAILED_STATE

When the server rejects an authentication packet, `auth_completion_func` (`zookeeper.c:2151-2189`) marks the session state as `ZOO_AUTH_FAILED_STATE` and invokes the registered authentication callbacks. Only after these callbacks return does response processing inspect `is_unrecoverable(zh)` to trigger terminal failure handling via `handle_error(zh, ZAUTHFAILED)` (`zookeeper.c:3590-3598`).

Both `update_addrs` (`zookeeper.c:1222-1226`) and `nonblocking_send` (`zookeeper.c:4153-4162`) assign `zh->state = ZOO_NOTCONNECTED_STATE` (999) without verifying current session status. If an application callback invokes `zoo_set_servers` in response to an authentication failure, or if a concurrent reconfiguration occurs on another thread, `zh->state` is overwritten with 999. Consequently, when `auth_completion_func` returns, `is_unrecoverable(zh)` evaluates to false. The terminal error handler is bypassed, no `ZOO_AUTH_FAILED_STATE` event is emitted, pending requests remain indefinitely blocked, the multithreaded I/O thread does not exit, and the client attempts to reconnect and resend the rejected credential to the new ensemble.

**Remediation Design:**
At both forced-close locations (`update_addrs` and `nonblocking_send`), verify `is_unrecoverable(zh)` before altering session state. If the handle has already transitioned into a terminal failure state (`ZOO_AUTH_FAILED_STATE` or `ZOO_EXPIRED_SESSION_STATE`), the current state must be preserved, allowing `handle_error(zh, ZAUTHFAILED)` to execute terminal teardown, dispatch failure events, and reject subsequent connection attempts.

**Compatibility and Verification:**
Applications modifying server topology from authentication callbacks observe consistent terminal failure behavior. The public API and ABI are preserved. Tests should verify that calling `zoo_set_servers` within a failed authentication callback retains `ZOO_AUTH_FAILED_STATE` and shuts down the session.

### Abnormal termination via longjmp leaves uncleaned queues and corrupts subsequent connections

In the single threaded client, `cleanup` (`zookeeper.c:2101-2122`) executes application callbacks mid-way through teardown. Specifically, `queue_session_event` immediately drains the completion queue, causing the `ZOO_CONNECTING_STATE` watcher callback to run before `cleanup_bufs` clears `to_send` and `sent_requests`. If an application uses `setjmp`/`longjmp` to exit from a watcher or authentication completion:
- The remainder of `cleanup` is bypassed: request queues remain populated, the internal socket pointer is reset to -1 while the session state remains marked connected, backoff delay calculation is skipped, and `api_epilog` does not execute.
- Because internal structures remain uncleaned, subsequent invocations of `zookeeper_interest` proceed with reconnection. Surviving queues are replayed onto the new connection, causing frame splices, xid mismatches, or keepalive stalls.
- The reference counter incremented by `api_prolog` remains elevated. When the application subsequently invokes `zookeeper_close`, the routine takes its multi-reference branch (`refs > 1`), enqueuing `ZCLOSING` results that are never drained and returning `ZOK` without dispatching `CLOSE_OP` or deallocating the handle.

**Remediation Design:**
Maintain an active teardown guard flag on the handle across `cleanup` and `handle_error`. While this guard remains set, subsequent calls to `zookeeper_interest` and `zookeeper_process` must return `ZINVALIDSTATE` without attempting socket operations or queue processing. If control flow exits the callback abnormally, the guard persists, preventing the handle from initiating corrupted reconnections. In addition, internal reference tracking must be decoupled from application callback unwinding to permit clean handle destruction.

**Compatibility and Verification:**
single threaded applications jumping out of teardown callbacks receive `ZINVALIDSTATE` upon subsequent driver calls rather than experiencing silent data replay or hangs. API and ABI interfaces remain stable. Testing involves executing single threaded teardown inside a `setjmp` frame, invoking `longjmp` from a watcher, and confirming that further operations fail safely.

### Chroot validation fails to isolate relative, empty, single-character, and root-sequential paths

Path namespacing under a chroot prefix relies on `prepend_string` (`zookeeper.c:1710-1721`), which concatenates the chroot prefix and the application-supplied path as raw strings without verifying whether the client path possesses a leading slash. Because path validation (`isValidPath` at `zookeeper.c:4064-4106`) executes only on the concatenated result, any relative path will pass validation as long as the chroot prefix begins with `/`. This defect undermines namespace isolation across several input patterns:
- Relative path traversal: Under chroot `/app`, a relative path `"2/x"` produces `/app2/x`, accessing sibling nodes outside the tenant's namespace.
- Empty path bypass: An empty string `""` resolves directly to `/app`, permitting access to the chroot root itself, whereas non-chroot handles reject empty paths with `ZBADARGUMENTS`.
- Single-character resolution: Any single-character path without a slash (e.g., `"x"`) resolves to `/appx` or collides with the chroot root, allowing operations like `zoo_delete(zh, "x", -1)` to delete `/app` itself if empty.
- Sequential creation on root: Calling `zoo_create` with sequential flags on `"/"` sends `/app` with sequential attributes to the server, which creates `/app0000000001` alongside the chroot instead of inside it.
- Symmetrically, `sub_string` (`zookeeper.c:1727-1744`) strips prefixes purely by character length without enforcing a slash boundary, returning truncated, corrupted paths to the application.

**Remediation Design:**
Path validation must be performed on the raw client-supplied path prior to chroot prefixing:
1. Enforce that all client paths are absolute, returning `ZBADARGUMENTS` before transmission if a path is empty or lacks a leading `/`.
2. Under a chroot, sequential creation on `"/"` must either format the wire path with a trailing slash (`/app/`) or be rejected with `ZBADARGUMENTS`.
3. In `sub_string`, enforce strict boundary checks ensuring that prefix removal only occurs along path component boundaries (`/`).

**Compatibility and Verification:**
Applications relying on malformed or relative paths under a chroot receive explicit `ZBADARGUMENTS` errors. Verification requires executing a matrix of path patterns (`""`, `"x"`, `"2/x"`, `"/"` with sequential flags) across chrooted and non-chrooted sessions to ensure identical validation rigor.

### High-priority buffer insertion splits active frame transmission

The internal buffering routine `queue_buffer` (`zookeeper.c:1796-1817`) implements high-priority frame queuing via `add_to_front`, inserting new buffers ahead of the current head of the `to_send` list. This mechanism is utilized by `zoo_add_auth` (`zookeeper.c:5340-5346`), SASL token exchanges, and watch restoration (`SetWatches`).

However, `queue_buffer` performs this insertion without inspecting whether the current head buffer has already been partially written to the transport (`curr_offset > 0`). If a large operation (such as a multi-megabyte `zoo_aset`) fills the socket send buffer and stalls mid-frame, a subsequent high-priority frame queued by another thread is placed ahead of it. When the socket becomes writable, `send_buffer` transmits the high-priority packet and subsequently resumes the original frame at its previous offset. The server receives the beginning of the write request, followed by the interleaved high-priority frame, followed by the remainder of the write payload. This framing corruption causes the server to reject the stream with deserialization errors and abort the connection.

**Remediation Design:**
In `queue_buffer`, inspect the head buffer's transmission state when `add_to_front` is requested. If the current head buffer has `curr_offset > 0`, insert the new buffer immediately behind the head buffer rather than in front of it, and update list tail pointers if the head was the sole element. Because queue traversal and transmission already execute under `zh->to_send` locking, no additional synchronization primitives are required.

**Compatibility and Verification:**
Eliminates intermittent connection resets during concurrent authentication or watch re-registration under high network load. API and ABI specifications are unaffected. Testing requires mocking a constrained socket buffer, halting reads to force a partial write of a large node payload, enqueueing authentication frames, and verifying that both frames arrive intact and uncorrupted.

### Broadcast completion of authentication replies delivers false successes for rejected credentials

When an authentication response arrives with transaction ID -4, `auth_completion_func` (`zookeeper.c:2151-2189`) processes the result. Instead of correlating the response with the specific credential submitted, the function extracts all pending authentication callbacks on the handle (`get_auth_completions`) and executes every callback using the status code of that single reply.

Because `zoo_add_auth` does not immediately wake the I/O thread, multiple authentication calls issued in sequence often accumulate in the send queue. Furthermore, `send_info_packet` prepends authentication frames to the front of `to_send`, reversing their order on the wire:
1. If an application invokes `zoo_add_auth` with an invalid scheme, followed immediately by a valid digest credential, the frames are dispatched in reverse order (digest, then invalid).
2. When the server approves the digest credential, `auth_completion_func` runs with `ZOK`, invoking both callbacks with `ZOK` and logging success for the first scheme.
3. When the server subsequently evaluates the invalid credential, it rejects it with `AUTHFAILED` and terminates the connection.
4. The application has already received a false `ZOK` notification for the rejected credential and may have proceeded with unauthorized operations.
5. In addition, credentials resent during session re-establishment can complete newly issued `zoo_add_auth` requests with premature `ZOK` statuses before the server has processed them.

**Remediation Design:**
Track outstanding authentication submissions in a FIFO structure matching the exact wire transmission sequence. When a reply with xid -4 is processed, correlate the status code strictly with the specific completion callback associated with that frame. Retransmitted credentials from connection handshakes must not consume or interact with application completion callbacks.

**Compatibility and Verification:**
Applications issuing multiple authentication requests receive precise, independent status codes for each submission. API and ABI specifications remain unchanged. Verification involves issuing consecutive `zoo_add_auth` requests containing mixed valid and invalid credentials, confirming that each completion callback receives its exact individual status.

### Synchronous zoo_multi failures leave operation error fields uninitialized

When an in-flight transaction is interrupted by a connection failure or handle closure, `free_completions` (`zookeeper.c:2015-2079`) manages cleanup. For asynchronous transactions, it invokes `cleanup_failed_multi`, which populates each sub-operation's `err` field with the failure code and resets pointers. However, for synchronous transactions (`COMPLETION_MULTI` associated with a synchronous completion record), `free_completions` sets only `sc->rc = reason` and deallocates sub-entries without touching the caller's `zoo_op_result_t` array.

Consequently, `results[i].err` remains uninitialized on the caller's stack or heap. Applications inspecting individual operation results following a non-zero return code (e.g., checking `results[i].err == ZOK`) read uninitialized memory, erroneously interpreting failed operations as successful.

**Remediation Design:**
In `free_completions`, invoke `cleanup_failed_multi(zh, cptr->xid, reason, cptr)` for synchronous multi entries prior to notifying the waiting thread via `notify_sync_completion`. This guarantees that all sub-operation error fields in the caller's results buffer are populated with the failure code before the synchronous wrapper returns. For call-time validation failures, `zoo_multi` must similarly populate all `results[i].err` fields prior to returning.

**Compatibility and Verification:**
Applications inspecting sub-operation error statuses after a connection drop reliably observe failure codes instead of undefined memory. API and ABI structures are preserved. Testing requires initializing a result array with sentinel values, triggering a synchronous transaction failure via connection drop, and verifying that every result slot reflects `ZCONNECTIONLOSS`.

### Synchronous configuration accessors read through chroot namespaces

The synchronous configuration functions `zoo_getconfig` and `zoo_wgetconfig` (`zookeeper.c:5677-5687`) are implemented as wrappers around `zoo_wget` using `ZOO_CONFIG_NODE`. Because `zoo_wget` prepends the handle's chroot path, the resulting wire request targets `/<chroot>/zookeeper/config`. In contrast, asynchronous configuration functions (`zoo_agetconfig`, `zoo_awgetconfig`) query the canonical `/zookeeper/config` directly, bypassing the chroot.

On a handle configured with a chroot (e.g., `/app`), synchronous configuration queries attempt to read `/app/zookeeper/config`, failing with `ZNONODE` or retrieving unrelated application nodes. Asynchronous calls on the identical handle correctly return the cluster configuration.

**Remediation Design:**
Implement dedicated synchronous wrappers for `zoo_getconfig` and `zoo_wgetconfig` that allocate a synchronous completion record and dispatch through `zoo_awgetconfig` using `SYNCHRONOUS_MARKER`. This ensures that synchronous configuration queries target the canonical `/zookeeper/config` path consistently regardless of chroot configuration, aligning their behavior with the asynchronous API.

**Compatibility and Verification:**
Synchronous configuration accessors on chrooted sessions successfully retrieve cluster configuration. API and ABI interfaces remain stable. Verification consists of querying cluster configuration both synchronously and asynchronously on a chrooted handle and asserting that identical payloads are returned.

### Container creation within a transaction desynchronizes multi-response deserialization

When parsing multi-transaction responses, `deserialize_multi` (`zookeeper.c:3308-3337`) determines the decoding format for each sub-result based on the client's expected operation type. Container creations are registered internally as `COMPLETION_STRING`. However, the ZooKeeper server serializes container creation responses within a multi transaction as `Create2Response` structures (`FinalRequestProcessor.java`), which include both a path string and a 68-byte `Stat` record.

Because the client deserializes only the path string for `COMPLETION_STRING`, the 68 `Stat` bytes remain unconsumed in the input buffer. Subsequent sub-operation headers (`deserialize_MultiHeader`) then attempt to parse their fields out of the remaining `Stat` bytes. Because deserialization errors are ignored, subsequent operations in the transaction receive corrupted return codes, terminate early, or trigger assertion failures in debug builds.

**Remediation Design:**
In `deserialize_multi`, inspect the response header's wire operation type (`mhdr.type`) rather than client-side expectations. When the header indicates `ZOO_CREATE2_OP` for a `COMPLETION_STRING` entry, deserialize a full `Create2Response`, deliver the resulting path string to the completion handler, and safely discard the `Stat` payload, preserving buffer alignment for subsequent operations.

**Compatibility and Verification:**
Transactions containing container creations decode correctly without corrupting adjacent sub-results. Public interfaces remain unchanged. Verification involves executing multi transactions that combine container creation with subsequent delete or check operations, confirming that all sub-operations decode accurately.

### zookeeper_get_connected_host returns NULL for read-only connections

The endpoint accessor `zookeeper_get_connected_host` (`zookeeper.c:1265-1275`) verifies connection state using `if (zh->state != ZOO_CONNECTED_STATE) return NULL;`. This check excludes `ZOO_READONLY_STATE`, despite the fact that `is_connected(zh)` in the same source file explicitly treats both states as active connections.

When an application connects to a read-only server during a partition, calls to `zookeeper_get_connected_host` return `NULL` even though a live TCP socket exists and operations succeed. System monitoring and diagnostic tools inspecting this function falsely conclude that the client is disconnected.

**Remediation Design:**
Replace the direct state equality check with `!is_connected(zh)`, allowing the function to format and return the connected peer address whenever the session is established in either read-write or read-only mode.

**Compatibility and Verification:**
Applications querying connected endpoint information on read-only sessions receive the active host address instead of `NULL`. API and ABI specifications remain unchanged. Verification involves establishing a session in read-only mode and asserting that `zookeeper_get_connected_host` returns a valid socket address.

### Configuration watch events under a chroot deliver mangled paths or log false errors

Watches registered on cluster configuration monitor the canonical node `/zookeeper/config`. When dispatching watch notifications, `do_foreach_watcher` (`zk_hashtable.c:283-294`) unconditionally invokes `sub_string` (`zookeeper.c:1727-1744`) to strip the client's chroot prefix from the server path. 

This prefix stripping fails when applied to configuration events:
- If the chroot path is not a prefix of `/zookeeper/config` (for example, `/app`), `sub_string` logs an `ERROR` message ("server path /zookeeper/config does not include chroot path /app") and delivers the raw path.
- If the chroot is a character prefix (for example, `/zoo`), `sub_string` strips characters without checking path component boundaries, delivering an invalid path such as `keeper/config` to the application watcher.

**Remediation Design:**
When dispatching node watch events, verify whether the event path matches `ZOO_CONFIG_NODE`. If the path represents canonical cluster configuration and the chroot is not a root level component match, deliver `ZOO_CONFIG_NODE` directly without logging errors or stripping non-existent prefixes. Enforce path separator boundaries across all prefix stripping operations in `sub_string`.

**Compatibility and Verification:**
Eliminates spurious error log entries and prevents delivering mangled paths to configuration watchers on chrooted handles. API and ABI structures remain untouched. Verification requires setting a configuration watch on a chrooted handle, updating cluster configuration, and asserting that the watcher receives `/zookeeper/config` cleanly without error log emissions.

### Transaction ID counter wrap causes reply misrouting and session desynchronization

Transaction identifiers (xids) are allocated by `get_xid` (`mt_adaptor.c:538-545`, `st_adaptor.c:78-85`) using a process global integer initialized to `time(0)`. In the multithreaded runtime, the counter wraps into negative integers. In the single threaded client, incrementing past `INT32_MAX` triggers undefined signed integer overflow.

When a wrapped xid collides with negative values reserved by protocol control frames (-8 for `SetWatches`, -4 for authentication, -2 for ping), `zookeeper_process` routes incoming replies to internal handlers rather than application completions:
- A reply to a standard operation carrying xid -8 is discarded as a `SetWatches` acknowledgment, leaving the original completion orphaned at the head of `sent_requests`. The subsequent reply triggers an xid mismatch and aborts the connection with `ZRUNTIMEINCONSISTENCY`.
- A reply carrying xid -2 is misidentified as a ping response.
- A reply carrying xid -4 invokes `auth_completion_func`, which may transition the handle into a permanent `ZOO_AUTH_FAILED_STATE`.

**Remediation Design:**
Structure xid generation to operate within positive 32-bit integer ranges, wrapping explicitly from `INT32_MAX` back to 1 and strictly skipping values $\le 0$. In multithreaded builds, manage counter increments using an atomic compare-and-swap loop.

**Compatibility and Verification:**
Eliminates latent protocol crashes and state corruption in long-running processes. Public interfaces remain identical. Verification involves initializing the xid counter near `INT32_MAX` via test hooks and confirming smooth wraparound to 1 without routing failures.

### Unhandled short writes during connection handshake cause connection timeouts

During initial connection establishment, `prime_connection` (`zookeeper.c:2362-2409`) transmits the 4-byte length header and 45-byte `ConnectRequest` frame across two direct invocations of `zookeeper_send`. The function only checks whether the return value is negative, ignoring short writes where the kernel accepts fewer bytes than requested.

If a nonblocking socket buffer is constrained, a short write leaves the handshake frame truncated. Because no state machine resumes the transmission, the server blocks awaiting remaining bytes while the client blocks awaiting a handshake reply, until the attempt times out with `ZOPERATIONTIMEOUT` after $2T/3$. Under TLS, if `SSL_write` encounters a would-block condition and returns -1, the client interprets it as a socket failure and aborts connection setup immediately.

**Remediation Design:**
Enqueue the handshake frame into the standard output queue (`to_send`) ahead of application requests, allowing `flush_send_queue` to handle partial writes and socket readiness across event loop iterations. Alternatively, record written byte offsets on the handle and maintain the connecting state until all 49 bytes have been successfully transmitted.

**Compatibility and Verification:**
Resolves intermittent connection establishment failures under constrained socket buffers. Application-visible interfaces and healthy path behaviors remain unchanged. Verification involves mocking `send` to force partial writes during the connect phase and asserting that the handshake completes successfully.

## Hangs and failed failover

### Double cursor advancement during failover causes server-list starvation

During connection failover, both `handle_error` (`zookeeper.c:2124-2132`) and `zoo_cycle_next_server` (`zookeeper.c:1654-1677`) advance the ensemble address cursor (`zh->addrs`). Following an initial connection attempt, the client increments the server cursor twice per failure, effectively skipping every alternating server in the list.

This double advancement leads to severe ensemble starvation:
- In a two-server ensemble, a client that loses its connection will continuously retry a single failed server indefinitely, never attempting to connect to the healthy alternate server.
- In even-sized ensembles, exactly half of the configured servers are permanently bypassed during failover cycles.
- When an established session on server 0 drops, the client attempts server 1, pauses for $T/60$, and retries server 1 repeatedly without returning to server 0, even if server 0 has recovered.
- Furthermore, outside of reconfig passes, the endpoint string reported by `zoo_get_current_server` immediately following a failure represents the address that the subsequent connection attempt will skip.

**Remediation Design:**
In `handle_error`, advance the cursor via `addrvec_next(&zh->addrs, &zh->addr_cur)` only when reconfiguration mode is active (`zh->reconfig != 0`), and use `addrvec_peek(&zh->addrs, &zh->addr_cur)` during standard operation. This ensures that outside of reconfiguration passes, cursor advancement occurs strictly within `zoo_cycle_next_server`. With this change, the end-of-list delay ($T/60$) executes predictably after traversing the entire ensemble, and `zoo_get_current_server` accurately reflects the endpoint targeted by the subsequent connection attempt.

**Compatibility and Verification:**
Applications observe true round-robin failover across all configured ensemble addresses and consistent reconnection backoff intervals. Public API and ABI specifications remain unchanged. Verification involves configuring a handle with two unreachable ports in deterministic order (`zoo_deterministic_conn_order(1)`) and verifying via socket call tracing that connection attempts alternate evenly between both addresses.

### SASL negotiation failures omit session events and orphan pending requests

When SASL negotiation fails, `check_events` (`zookeeper.c:3141-3158`, `3188-3193`) invokes `zoo_sasl_mark_failed` (`zk_sasl.c:135-154`), which sets `zh->state = ZOO_AUTH_FAILED_STATE` and returns without invoking `cleanup()` or queueing a session event.

Because the state transition occurs without a teardown:
- The multithreaded I/O thread detects an unrecoverable state on its subsequent iteration and terminates its event loop, leaving the underlying TCP socket open.
- Application watchers never receive a `ZOO_AUTH_FAILED_STATE` session event notification.
- Asynchronous requests remain trapped in queues until the application calls `zookeeper_close`.
- Synchronous callers block indefinitely in `wait_sync_completion` because no worker thread remains to process completions or wake waiters. If the blocked thread is the only thread responsible for closing the handle, the application hangs permanently. In contrast, digest authentication handles identical failures cleanly via `handle_error(zh, ZAUTHFAILED)`.

**Remediation Design:**
At both failure locations in `check_events`, invoke `handle_error(zh, ZAUTHFAILED)` immediately following `zoo_sasl_mark_failed(zh)`. This shuts down the socket, dispatches the `ZOO_AUTH_FAILED_STATE` session event to application watchers, wakes synchronous waiters, and fails pending asynchronous operations with `ZAUTHFAILED`, harmonizing SASL failure handling with digest authentication semantics.

**Compatibility and Verification:**
Applications receive timely error completions and watcher events upon SASL authentication rejections instead of deadlocking. API and ABI specifications are preserved. Verification requires attempting SASL authentication against a server with invalid credentials, confirming prompt `ZAUTHFAILED` returns across both synchronous and asynchronous operations.

### Oversized watch restoration frames exceeding jute.maxbuffer cause infinite reconnection loops

Upon re-establishing a connection, `send_set_watches` (`zookeeper.c:2258-2295`) serializes all active data, child, and persistent watch registrations into a single `SetWatches` request frame. When an application maintains a substantial number of watches (for example, 40,000 paths of 40 bytes each, totaling $\approx 1.8$ MB of keys), the serialized frame exceeds the server's default incoming buffer limit (`jute.maxbuffer`, default 1 MB / `0xfffff` bytes).

Upon receiving the oversized frame, the server logs a length error and abruptly terminates the TCP connection. The client reconnects, immediately retransmits the identical oversized `SetWatches` frame, and is disconnected again. Because each reconnection cycle fails all pending client operations, the client becomes trapped in an infinite reconnection loop, rendering the session unusable until process restart.

**Remediation Design:**
Partition the watch set across multiple `SetWatches` frames when total payload volume exceeds a safety threshold (such as 128 KiB, mirroring the Java client implementation). Each partitioned frame transmits with the identical `relativeZxid` and xid -8 header. Because the server processes and acknowledges each frame independently without state conflicts, watches are restored incrementally without exceeding server buffer constraints.

**Compatibility and Verification:**
Applications with large watch sets reconnect reliably across network interruptions without triggering server-side frame rejections. Public interfaces remain identical. Verification requires registering watches exceeding 1 MB across a live session, restarting the server, and verifying successful reconnection and watch delivery.

### DNS failures on active connections halt keepalives and poll uninitialized stack memory

With the default hostname resolution delay (`resolve_delay_ms = 0`), `zookeeper_interest` invokes `update_addrs` on every event loop iteration even while actively connected. If a transient resolver outage occurs, `resolve_hosts` returns `ZSYSTEMERROR` (`zookeeper.c:1028-1031`), prompting `zookeeper_interest` (`zookeeper.c:2643-2653`) to return immediately without populating output arguments (`*fd`, `*interest`, `*tv`) and before evaluating receive timeouts or scheduling keepalive pings.

In multithreaded execution, `do_io` (`mt_adaptor.c:384-405`) discards the return code from `zookeeper_interest` and passes uninitialized stack variables (`fd`, `interest`, `tv`) directly to `poll`. As a result:
- Keepalive ping generation is completely suspended.
- The client fails to monitor peer liveness, and the server expires the session after $T$ ms of inactivity.
- Ephemeral nodes are deleted on the server while the client application continues to believe it is connected.
- In single threaded execution, event loops polling uninitialized descriptors may block or trigger spurious I/O errors.

**Remediation Design:**
Pre-initialize I/O parameters in `do_io` (`fd = -1`, `interest = 0`, `tv = T/60`). In `zookeeper_interest`, populate output parameters before invoking `update_addrs`. When hostname resolution fails on an open socket, treat the failure as a non-fatal "no topology change" condition: record the error status, continue with timer progression, keepalive transmission, and interest calculations, and return the resolver error only after ensuring active connection maintenance proceeds.

**Compatibility and Verification:**
Transient DNS outages no longer disrupt active sessions or suppress heartbeats. Application APIs remain unchanged. Verification involves mocking `getaddrinfo` to return failure while an active session is established, verifying that keepalive pings continue to transmit and session timeouts are properly tracked.

### Global DNS resolution failures prevent reconnection to known-good addresses

When disconnected, `update_addrs` unconditionally attempts hostname resolution regardless of configured backoff delays. If a transient network outage renders DNS unresolvable across all ensemble hosts, `resolve_hosts` returns `ZSYSTEMERROR`, causing `zookeeper_interest` to exit prior to connection logic or timeout evaluations.

Although `zh->addrs` retains valid IP addresses from previous successful resolutions, the client never attempts to connect to them. No teardown runs, pending operations block indefinitely without timing out, and the server-side session expires while the client sits idle with valid server IP addresses in memory. Similarly, passing a mistyped hostname to `zoo_set_servers` permanently wedges the client following the next disconnection, even though `zoo_set_servers` returned an error.

**Remediation Design:**
When `zh->addrs` contains previously resolved addresses, treat a global DNS resolution failure as a non-fatal condition. Allow connection attempts, timeout evaluations, and backoff timers to proceed against the cached address list, ensuring the client attempts reconnection to known-good endpoints while retrying hostname resolution in the background.

**Compatibility and Verification:**
Clients maintain connection resilience during DNS outages by falling back to previously resolved endpoints. API and ABI specifications remain intact. Verification consists of disconnecting an active session, mocking DNS resolution to fail, and confirming that the client reconnects to surviving cached IP addresses.

### Unbounded TLS nonblocking connect loops spin CPU upon timeout

In `zookeeper_interest` (`zookeeper.c:2745-2764`), the receive timeout evaluation explicitly exempts sessions in `ZOO_SSL_CONNECTING_STATE`: `&& zh->state != ZOO_SSL_CONNECTING_STATE`. While a nonblocking TLS TCP connection is pending, timeout tracking is bypassed, leaving termination entirely to OS-level TCP SYN retransmission limits (frequently exceeding 120 seconds on Linux).

Once elapsed time surpasses $2T/3$, the calculation for `recv_to` yields zero or negative values. Because `*tv` takes `min(recv_to, send_to)`, the poll timeout becomes 0 ms. The multithreaded I/O thread spins in a tight loop consuming 100% CPU until the kernel eventually terminates the connection. During this window, queued requests stall, and the session expires on the server despite the availability of healthy alternate servers.

**Remediation Design:**
Remove the `ZOO_SSL_CONNECTING_STATE` exemption from line 2749, ensuring that TLS connection establishment is bounded by $2T/3$ identically to plaintext connections. Furthermore, clamp timeout calculations to prevent zero or negative poll timeouts during connection attempts.

**Compatibility and Verification:**
TLS connection attempts fail over within expected timeout bounds ($2T/3$) without CPU spinning. Public interfaces remain identical. Verification requires targeting an unreachable IP address over TLS, confirming failover within $2T/3$ and verifying bounded CPU utilization.

### Unbounded blocking TLS handshakes freeze I/O threads and block close operations

The TLS handshake helper `init_ssl_for_socket` (`zookeeper.c:2937-2975`) executes `SSL_connect` within a blocking `while (1)` loop driven by a 1-second `select`. It maintains no overall deadline, ignores `zh->close_requested`, and uses `FD_SET` on descriptors that may exceed `FD_SETSIZE` (1024), causing stack corruption.

If a server accepts a TCP connection but stalls during the TLS handshake, the I/O thread remains permanently trapped within `init_ssl_for_socket`. No failover occurs, keepalive pings cease, and calling `zookeeper_close` deadlocks when attempting to join the I/O thread in `adaptor_finish`. Furthermore, on the read-only probing path, this blocking loop stalls maintenance of healthy read-only sessions.

**Remediation Design:**
Refactor the TLS handshake into a nonblocking state machine integrated into `zookeeper_process`, driving `SSL_connect` via event loop readiness (`SSL_ERROR_WANT_READ` / `SSL_ERROR_WANT_WRITE`) bounded by a $2T/3$ deadline. If retaining a blocking implementation, enforce an aggregate deadline of $2T/3$, replace `select` with `poll` to eliminate `FD_SETSIZE` overflow hazards, and check `zh->close_requested` on every pass.

**Compatibility and Verification:**
Eliminates permanent thread deadlocks during unresponsive TLS handshakes, permitting `zookeeper_close` to return promptly. API and ABI structures remain stable. Verification involves targeting an endpoint that accepts TCP connections but halts TLS data exchange, asserting that failover occurs within $2T/3$ and `zookeeper_close` exits cleanly.

### TLS credential load failures spin in unrecoverable retry loops

When a CA certificate, client certificate, or private key fails to load, `init_ssl_for_socket` (`zookeeper.c:2876-2917`) logs the failure, sets `errno = EINVAL`, and returns `ZBADARGUMENTS` without tearing down the socket, and `check_events` returns `ZSSLCONNECTIONERROR`. The handle remains in `ZOO_SSL_CONNECTING_STATE`.

Because the underlying socket remains open and writable, and because `ZOO_SSL_CONNECTING_STATE` is exempt from receive timeouts, subsequent I/O iterations immediately re-invoke `init_ssl_for_socket`. The I/O thread continuously re-reads the failing files, logging error lines and consuming 100% CPU without ever triggering failover or terminating the connection attempt.

**Remediation Design:**
When `init_ssl_for_socket` returns `ZBADARGUMENTS` while the socket is open, immediately execute a socket teardown via `handle_socket_error_msg(zh, ..., ZSSLCONNECTIONERROR)`. This fails pending operations, cleans up transport descriptors, and allows the client to advance to the next configured server with appropriate backoff delays.

**Compatibility and Verification:**
Applications receive `ZSSLCONNECTIONERROR` and failover cleanly rather than pegging CPU cores in an infinite file-reloading loop. API and ABI specifications remain unchanged. Verification consists of supplying an unreadable CA file path and confirming that CPU use remains bounded while pending operations fail promptly.

### Buffered decrypted TLS records stall until subsequent socket traffic arrives

The C client never queries `SSL_pending()` (`zookeeper.c:1932-2007`). It reads frames individually via `zookeeper_recv`, and the I/O loop sleeps in `poll` awaiting kernel socket events.

When a server coalesces multiple protocol responses into a single TLS record, OpenSSL decrypts the entire record during the initial `SSL_read`. Subsequent response frames remain buffered in OpenSSL user-space memory while the kernel socket buffer is empty. Consequently, `poll` does not wake, and pending responses remain stalled in memory until unrelated network traffic arrives or the connection times out falsely with `ZOPERATIONTIMEOUT` after $2T/3$.

**Remediation Design:**
In `zookeeper_interest`, inspect `SSL_pending(zh->fd->ssl_sock)`. If decrypted bytes remain buffered in user space, return a poll timeout of 0 ms with `ZOOKEEPER_READ` readiness. In `do_io`, process reads immediately whenever `SSL_pending()` indicates unread data, bypassing kernel poll waiting.

**Compatibility and Verification:**
Eliminates artificial latency spikes and false timeouts on pipelined TLS connections. API and ABI specifications are unaffected. Verification requires issuing pipelined asynchronous requests over a TLS proxy that bundles responses into shared TLS records, asserting that all completions fire promptly.

### TLS error detection relies on errno and stale OpenSSL error queues

Following the handshake, `send_buffer` and `recv_buffer` evaluate negative returns from `SSL_write` and `SSL_read` against system `errno` (checking for `EAGAIN`) rather than calling `SSL_get_error`. Furthermore, the thread's OpenSSL error queue is never purged via `ERR_clear_error`.

This architecture introduces two distinct failure modes:
- If a protocol error or alert occurs while `errno` happens to contain a stale `EAGAIN` from a previous system call, the client misidentifies the fatal error as a nonblocking would-block condition and stalls until the receive timeout expires.
- Conversely, if an application thread leaves an unhandled error on OpenSSL's thread-local error queue, `SSL_get_error` during a subsequent handshake evaluates that stale error, failing with `SSL_ERROR_SSL` and tearing down a healthy connection.

**Remediation Design:**
Invoke `ERR_clear_error()` prior to every OpenSSL I/O call (`SSL_connect`, `SSL_read`, `SSL_write`). When OpenSSL functions return $\le 0$, evaluate the result strictly through `SSL_get_error`, mapping `SSL_ERROR_WANT_READ` and `SSL_ERROR_WANT_WRITE` to retry conditions and treating all other codes as definitive connection errors.

**Compatibility and Verification:**
Prevents false handshake failures from external OpenSSL error leakage and ensures immediate detection of TLS protocol errors. Public interfaces remain identical. Verification involves injecting errors into the thread-local OpenSSL error queue prior to initiating a connection, confirming that handshake succeeds.

### Moving stack buffer addresses cause SSL_write retry rejections

When transmitting the 4-byte frame length prefix, `send_buffer` (`zookeeper.c:1888-1907`) passes the address of a stack-local variable (`nlen`) to `SSL_write`. If `SSL_write` yields a retryable condition (`SSL_ERROR_WANT_WRITE`), subsequent attempts from different call paths pass the address of `nlen` located in a new stack frame.

OpenSSL enforces that retry calls pass identical buffer memory addresses unless `SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER` is explicitly configured. Because the client only enables `SSL_MODE_AUTO_RETRY`, OpenSSL rejects the retry with `SSL_R_BAD_WRITE_RETRY`, aborting the connection under high network load.

**Remediation Design:**
Add `SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER` to the SSL context configuration at line 2921. Alternatively, allocate the 4-byte prefix within the heap-allocated `buffer_list_t` structure so that its buffer address remains invariant across retry attempts.

**Compatibility and Verification:**
Eliminates unexpected `ZCONNECTIONLOSS` failures during saturated socket conditions over TLS. API and ABI specifications remain unchanged. Verification requires saturating a TLS socket buffer with partial writes and confirming that subsequent retries succeed without OpenSSL errors.

### Zero-count synchronous transactions block permanently on terminated handles

In `zoo_amulti` (`zookeeper.c:4846-4979`), session state checks are executed only within the loop validating individual operations. When `count <= 0`, this loop is skipped. `do_add_completion` enqueues the empty request without verifying whether the session is active, and `zoo_multi` calls `wait_sync_completion`.

On an expired (`ZOO_EXPIRED_SESSION_STATE`) or authentication-failed handle where the I/O thread has already terminated, no teardown will run again to drain completions. The synchronous caller hangs indefinitely, awaiting a background thread that is no longer running.

**Remediation Design:**
In `zoo_multi`, inspect `is_unrecoverable(zh)` when `count <= 0` and return `ZMARSHALLINGERROR` immediately without queuing completions. In addition, guard `do_add_completion` against accepting entries when the handle state indicates terminal failure.

**Compatibility and Verification:**
Zero-count transactions on dead handles return error codes immediately rather than deadlocking. Public interfaces remain unchanged. Verification involves issuing `zoo_multi(zh, 0, NULL, NULL)` on an expired handle and asserting immediate error return.

### Synchronous API invocations from I/O thread callbacks deadlock the runtime

Synchronous API functions block in `wait_sync_completion` awaiting response processing by the background I/O thread. However, authentication completions, SASL callbacks, and logging hooks are executed directly on the I/O thread itself.

If an application issues a synchronous call (such as `zoo_get` or `zoo_exists`) from within one of these callbacks, the I/O thread enqueues the request and enters `wait_sync_completion`, blocking on itself. All network I/O and protocol processing halt permanently until another thread intervenes to close the handle.

**Remediation Design:**
In `do_add_completion`, detect whether the calling thread is identical to the handle's I/O worker thread (`pthread_equal(pthread_self(), zh->io_thread)`). When invoked on the I/O thread, reject synchronous requests with `ZSYSTEMERROR` and set `errno = EDEADLK`, preventing self-deadlocks.

**Compatibility and Verification:**
Prevents catastrophic runtime hangs when application callbacks attempt synchronous calls, returning explicit deadlock diagnostics. API and ABI specifications are preserved. Verification involves issuing a synchronous read from within an authentication completion and asserting that `ZSYSTEMERROR` is returned promptly.

### Unlocking gaps and lock hierarchy inversions in application callbacks cause deadlocks

In `free_completions`, authentication completions are invoked while holding `zh_lock` (`zookeeper.c:2062-2078`), a non-recursive mutex. Furthermore, logging callbacks are invoked while holding `reconfig_lock`, `zh_lock`, and `watchers_lock`.

If an authentication completion or logging hook invokes any API function (`zoo_a*`, `zookeeper_close`, `zoo_get_current_server`), the thread attempts to re-acquire `zh_lock` or `reconfig_lock`, causing an unrecoverable deadlock. Additionally, holding `reconfig_lock` across blocking DNS lookups in `update_addrs` blocks any thread querying client state.

**Remediation Design:**
Extract application callback dispatch outside critical sections: collect completions within the lock, release `zh_lock`, and invoke callbacks without internal locks held. Format log messages before acquiring locks or defer emission. Perform DNS resolution on unshared buffers without holding `reconfig_lock`.

**Compatibility and Verification:**
Re-entrant API calls from callbacks succeed without deadlocking, and DNS lookups no longer stall concurrent reader threads. Public interfaces remain identical. Verification involves calling `zookeeper_close` from an authentication completion during connection drop, confirming clean termination.

### Nested driver calls within teardown callbacks corrupt transport state

In single threaded execution, `cleanup` runs watcher callbacks with the socket set to -1 while the internal state is still marked connected. If a watcher invokes `zookeeper_interest` to register a new descriptor, the routine initiates a connection to the next server, opening a new socket and updating `zh->state` to `ZOO_CONNECTING_STATE`. When the callback returns, the outer `cleanup` continues, writing `zh->state = 0` over the new connection and corrupting transport tracking.

The newly opened socket is orphaned with invalid state flags. The client neither asks for write readiness nor transmits a handshake, leaving the connection stalled until $2T/3$. If the nested connect fails immediately, `handle_socket_error_msg` triggers recursive teardown, leading to unbounded stack exhaustion.

**Remediation Design:**
Enforce the teardown execution guard flag to reject nested `zookeeper_interest` and `zookeeper_process` calls with `ZINVALIDSTATE` while teardown is active.

**Compatibility and Verification:**
Prevents transport corruption and stack overflows from re-entrant event loop drivers. API and ABI specifications are preserved. Verification consists of calling `zookeeper_interest` from within a connecting watcher and checking for safe error handling.

### Read-only probe blocks the I/O thread, uninitializes timeouts, and leaks descriptors

The read-only probe `ping_rw_server` (`zookeeper.c:2461-2522`) executes synchronously on the I/O thread using a blocking socket. In `zookeeper_set_sock_timeout`, `tv.tv_usec` is left uninitialized; if garbage data causes `setsockopt` to fail with `EDOM`, the socket runs with no timeout, allowing `connect` and `recv` to block for minutes. Furthermore, failed connection attempts return without closing the socket descriptor.

The background I/O thread freezes, halting keepalive pings and causing established read-only sessions to time out. Repeated probe failures leak file descriptors, eventually exhausting process descriptor limits.

**Remediation Design:**
Zero `tv.tv_usec` before setting socket options, ensure socket descriptors are closed on all exit paths, and ideally restructure the probe into a nonblocking poll with an explicit 1-second aggregate deadline.

**Compatibility and Verification:**
Eliminates descriptor leaks and prevents read-only probing from stalling active session maintenance. Public interfaces remain unchanged. Verification involves counting open file descriptors across repeated failed probe attempts.

### SASL negotiation with queued application requests causes 100% CPU spin

During SASL negotiation, `zookeeper_interest` requests `ZOOKEEPER_WRITE` whenever `to_send` contains entries. However, `flush_send_queue` only sends SASL packets while authentication is in progress. Once the SASL packet has been sent, an application request sits at the head of `to_send`. `flush_send_queue` performs no I/O and returns `ZOK`.

Because the underlying TCP socket remains writable, `poll` wakes immediately on every iteration. The I/O thread spins in a tight loop at 100% CPU utilization until the server returns the SASL response.

**Remediation Design:**
In `zookeeper_interest`, while SASL is in progress, request write readiness only if the buffer at the head of `to_send` is an internal SASL control packet.

**Compatibility and Verification:**
Eliminates CPU spinning while awaiting SASL authentication challenges under queued application traffic. API and ABI structures remain stable. Verification involves queueing requests during SASL negotiation and asserting low CPU consumption.

### Stale SASL state flags persist across reconnections and desynchronize authentication

`zoo_sasl_connect` (`zk_sasl.c:201-219`) resets session state but does not clear `is_gssapi` or `is_last_packet`. Furthermore, `state` remains `ZOO_SASL_INTERMEDIATE` across disconnections until handshake completion.

If a connection drops after GSSAPI flags are set, the subsequent connection attempt inherits stale flags. The client prematurely marks SASL complete without performing negotiation steps, leading to server-side authentication rejection. In addition, an intermediate state between connections keeps write readiness active, causing the I/O thread to spin.

**Remediation Design:**
Explicitly clear `is_gssapi` and `is_last_packet` in `zoo_sasl_connect`, and reset SASL state to `ZOO_SASL_INITIAL` during `cleanup` whenever a connection drops.

**Compatibility and Verification:**
Ensures every reconnection cycle initiates a clean, independent SASL authentication exchange. Public interfaces remain identical. Verification requires dropping connections during negotiation and confirming that subsequent attempts complete authentication cleanly.

### Immediate-completion SASL mechanisms stall until receive timeout

If `sasl_client_start` returns `SASL_OK` with zero-length output (common in mechanisms like `EXTERNAL`), `zoo_sasl_client_start` (`zk_sasl.c:336`) suppresses frame transmission.

The client remains in `ZOO_SASL_INTERMEDIATE`, the server receives no authentication token to respond to, and the connection stalls silently until $2T/3$, repeating across every reconnection attempt.

**Remediation Design:**
Remove the conditional check at line 336, ensuring that `zoo_sasl_client_start` always queues an initial authentication frame (even if empty) when `sasl_client_start` succeeds.

**Compatibility and Verification:**
Supports SASL mechanisms requiring no client initial response without timing out. API and ABI specifications remain unchanged. Verification requires testing with a mechanism that returns `SASL_OK` on start and asserting immediate response exchange.

### Error-only poll results spin the multithreaded I/O thread

In `do_io` (`mt_adaptor.c:395-404`), only `POLLIN` activates read interest and only `POLLOUT` or `POLLHUP` activates write interest. If `poll` returns with `POLLERR` or `POLLNVAL` alone, no readiness bits are set.

`zookeeper_process(zh, 0)` is invoked with zero interest, performing no I/O. `poll` immediately returns the same error status on the next iteration, spinning the I/O thread at 100% CPU until the receive timeout fires.

**Remediation Design:**
Set both read and write readiness flags when `revents` contains `POLLERR` or `POLLNVAL`, forcing the subsequent socket operation or `SO_ERROR` check to fail immediately and trigger clean connection teardown with `ZCONNECTIONLOSS`.

**Compatibility and Verification:**
Replaces CPU spinning with immediate connection loss handling upon socket errors. Public interfaces remain identical. Verification involves mocking `poll` to return `POLLERR` and checking that connection teardown executes immediately.

### Negative or unallocatable frame lengths spin the I/O thread

When reading packet headers, `recv_buffer` (`zookeeper.c:1957-1962`) reads the 4-byte length. A negative integer is cast to an enormous unsigned value, causing `calloc` to fail and return `NULL`. With `curr_offset` set to 4, `recv_buffer` neither reads data nor reports an error, causing `check_events` to return `ZNOTHING`.

The unconsumed bytes remain in the socket receive buffer. The socket remains permanently readable, causing `poll` to return immediately and spinning the I/O thread at full CPU until the receive timeout expires.

**Remediation Design:**
Validate that frame lengths are non-negative and within reasonable protocol bounds (e.g., $\le$ `jute.maxbuffer`). If a length is invalid or allocation fails, return -1 from `recv_buffer` to abort the connection immediately with `ZCONNECTIONLOSS`.

**Compatibility and Verification:**
Protects the client from hanging or spinning when encountering corrupted network streams or malicious input. API and ABI specifications are preserved. Verification involves injecting negative length headers from a mock server and verifying immediate connection closure.

### Empty server address lists spin the I/O thread and flood logs

If an application initializes the client or calls `zoo_set_servers` with an empty host list (`""`), `count_hosts` returns 0 and `resolve_hosts` returns `ZOK`. In `handle_error`, `addrvec_atend` evaluates to false on an empty list, so backoff delays are never set (`zh->delay = 0`).

`zookeeper_interest` writes a poll timeout of 0 ms. On each pass, `socket(AF_UNSPEC, ...)` fails, `handle_socket_error_msg` logs an error, and the I/O thread loops continuously, exhausting CPU and filling disk logs.

**Remediation Design:**
In `handle_error`, set `zh->delay` whenever the address vector is empty (`addrvec_atend(&zh->addrs) || zh->addrs.count == 0`), inserting a $T/60$ sleep between iterations to suppress CPU spinning.

**Compatibility and Verification:**
Throttles failed socket attempts on unconfigured handles, preventing log flooding and CPU exhaustion. API and ABI structures remain stable. Verification involves initializing a handle with `""` and confirming that socket creation calls are throttled.

### zoo_set_servers commits malformed connection strings before validation

`zoo_set_servers` (`zookeeper.c:1551-1572`) frees `zh->hostname` and stores the new connection string before invoking `update_addrs`. If the string contains syntax errors (e.g., an invalid port), `update_addrs` returns `ZBADARGUMENTS`, but the malformed string remains stored on the handle.

All subsequent resolution passes in `zookeeper_interest` fail on the corrupted string. Active connections stop pinging and time out, and disconnected clients can never reconnect, even though the application caught the error return from `zoo_set_servers`.

**Remediation Design:**
Parse and validate the new connection string in a temporary buffer before modifying handle fields. If syntax validation fails, reject the call with `ZBADARGUMENTS` while keeping `zh->hostname` and the active address vector intact.

**Compatibility and Verification:**
Rejected server configuration changes leave existing connection topologies functional. Public interfaces remain identical. Verification involves passing an invalid address string to `zoo_set_servers` and confirming that the client continues communicating with previously configured servers.

### pthread_create failures abort debug builds or hang release builds

`start_threads` (`mt_adaptor.c:210-228`) initiates the I/O and completion threads, asserting success with `assert(rc == 0)`. When built with `NDEBUG`, the assertion is removed, and `wait_for_others` waits on a synchronization barrier that can never reach zero.

If system thread limits are reached, debug builds crash the process via assertion failure, while production release builds hang indefinitely within `zookeeper_init`.

**Remediation Design:**
Check `pthread_create` return values explicitly. On failure, unwind previously created threads, decrement barrier counts, and return `NULL` from `zookeeper_init` with `errno` reflecting the thread creation failure.

**Compatibility and Verification:**
Replaces process aborts and hangs with graceful, documented error returns from initialization APIs. API and ABI specifications are unaffected. Verification involves mocking `pthread_create` to return `EAGAIN` and verifying that `zookeeper_init` returns `NULL`.

### Unvalidated recv_timeout values cause immediate timeouts or arithmetic overflow

`zookeeper_init` stores `recv_timeout` without boundary validation. A value below 3 ms calculates a receive timeout of 0 or 1 ms, tearing down connections immediately. Values exceeding $\approx 1.07\times 10^9$ ms overflow 32-bit signed integer arithmetic in `zh->recv_timeout * 2 / 3`, producing negative timeout values.

Clients configured with very small timeouts or very large timeouts enter rapid, continuous connection teardown loops, failing to connect to ensembles.

**Remediation Design:**
Validate `recv_timeout` during initialization, enforcing reasonable minimum thresholds (e.g., $\ge 10$ ms) and utilizing 64-bit integer arithmetic for timeout calculations to prevent overflow.

**Compatibility and Verification:**
Ensures robust timeout calculations across extreme configuration values. Public interfaces remain unchanged. Verification involves configuring `recv_timeout` with 1, -1, and large integers, verifying predictable connection handling.

## Crashes and memory errors

### Missing NULL handle checks across public API entry points

Numerous public entry points fail to validate the session handle before accessing its members. Wrappers including `zoo_aget`, `zoo_agetconfig`, `zoo_aexists`, `zoo_aget_children`, `zoo_aget_children2`, `zoo_exists`, `zoo_get`, `zoo_getconfig`, `zoo_get_children`, and `zoo_get_children2` dereference `zh->context` or `zh->watcher` immediately upon entry (`zookeeper.c:4167-5813`). While variant APIs such as `zoo_wget` explicitly validate the handle and return `ZBADARGUMENTS`, these primary entry points trigger segmentation faults.

Similarly, server configuration and inspection functions (`zoo_set_servers`, `zoo_cycle_next_server`, `zoo_get_current_server`, `zoo_set_servers_resolution_delay`) and state accessors (`zoo_get_context`, `zoo_recv_timeout`, `zoo_set_watcher`, `is_unrecoverable`, `zookeeper_get_connected_host`) fault when passed `NULL`. In `zoo_client_id(NULL)`, the function computes an offset from a null pointer, returning an invalid, non-null pointer that violates defined memory semantics.

**Remediation Design:**
Introduce strict handle validation at the beginning of each entry point:
- Return `ZBADARGUMENTS` from functions that return status codes (`zoo_get`, `zoo_set_servers`, etc.).
- Return `NULL` from pointer accessors (`zoo_client_id`, `zoo_get_context`, `zoo_set_watcher`, `zookeeper_get_connected_host`).
- Return 0 from `zoo_recv_timeout`, `ZINVALIDSTATE` from `is_unrecoverable`, and the static literal `"null"` from `zoo_get_current_server` (matching the fallback in `format_endpoint_info`).
- Treat `zoo_cycle_next_server(NULL)` as a safe no-op.

**Compatibility and Verification:**
Replaces undefined segmentation faults with deterministic, documented error returns. Public API and ABI definitions remain intact. Verification consists of executing a table-driven test that passes `NULL` handles across all public API entry points, asserting expected error returns.

### NULL completion callbacks crash or abort during response dispatch

Every asynchronous operation in `zookeeper.c` (with the sole exception of `zoo_add_auth`) accepts a `NULL` completion function pointer at submission time. The request is successfully serialized and queued for network transmission. However, when the server response arrives or when connection teardown executes, `deserialize_response` (`zookeeper.c:3339-3454`) invokes the completion pointer directly without verifying whether it is non-null, or triggers explicit assertion aborts in void and multi-operation handlers (`assert(cptr->c.void_result)`).

As a result, fire-and-forget asynchronous calls (such as issuing a background deletion or node write where the application does not require notification) crash the process on the completion worker thread upon receiving the server reply.

**Remediation Design:**
In `deserialize_response`, replace assertion checks with conditional invocations: verify that the completion pointer is non-null before dispatching results, while still deserializing response payloads, updating watch registrations, and deallocating memory buffers. Document in `zookeeper.h` that passing a `NULL` completion callback explicitly designates fire-and-forget execution without callback delivery.

**Compatibility and Verification:**
Enables callers to pass `NULL` completion callbacks safely without crashing. Existing code passing valid callbacks is unaffected. Verification involves submitting each asynchronous operation variant with a `NULL` callback and verifying that the session completes transactions cleanly.

### Unhandled NULL pointers in initialization, path concatenation, and transactions

Input pointer validation is inconsistently implemented across utility and setup routines:
- `zookeeper_init_ssl` passes unvalidated certificate path pointers directly to `strdup`, crashing on `NULL`.
- `zoo_sasl_make_password_callbacks` dereferences the `password` argument without verifying non-nullness.
- Under a chroot configuration, `prepend_string` crashes in `strlen(NULL)` when the client path is `NULL`, whereas non-chroot handles handle `NULL` paths via `isValidPath` and return `ZBADARGUMENTS`.
- In `zoo_amulti`, passing a `NULL` handle crashes inside `enter_critical` (`zookeeper.c:4964`). Passing `NULL` for `ops` or `results` when `count > 0` causes immediate segmentation faults in request formatting or reply processing.
- The transaction initialization helpers `zoo_*_op_init` abort on assertions or write through null pointers when passed `NULL`.

**Remediation Design:**
Apply defensive input checking across initialization and path routines: return `NULL` with `errno = EINVAL` from `zookeeper_init_ssl` and `zoo_sasl_make_password_callbacks` upon null inputs; return `NULL` from `prepend_string` when the input path is null so that `isValidPath` rejects it with `ZBADARGUMENTS`; and validate that `ops` and `results` are non-null in `zoo_amulti` when `count > 0`, returning `ZBADARGUMENTS` prior to queuing.

**Compatibility and Verification:**
Eliminates segmentation faults, harmonizing parameter validation across chrooted and non-chrooted configurations. API and ABI specifications remain unchanged. Verification involves parameter fuzzing across initialization and transaction setup routines.

### Payload buffer serialization faults on invalid lengths or NULL ACL pointers

In `oa_serialize_buffer` (`recordio.c:137-162`), memory copying proceeds via `memcpy` using the raw pointer and length provided by callers without sanity validation. If an application supplies a `NULL` buffer pointer alongside a positive length, an immediate segmentation fault occurs. If the length is negative and not equal to -1 (e.g., -2), the signed length is cast to an enormous `size_t` value, causing a massive out-of-bounds copy. Furthermore, `zoo_aset_acl` (`zookeeper.c:4767-4772`) performs a direct structure dereference `req.acl = *acl` without verifying that the `acl` pointer is non-null.

Because these operations execute after path validation on the application's thread, client applications crash during request submission.

**Remediation Design:**
In `oa_serialize_buffer`, validate buffer properties before copying: return `-EINVAL` if `b->len < -1` or if `b->len > 0` while `b->buff` is `NULL`. This error flows into `nonblocking_send`, which rejects the request with `ZMARSHALLINGERROR` without queuing corrupted frames. In `zoo_aset_acl`, validate `acl != NULL` immediately following path checks, returning `ZBADARGUMENTS` if omitted.

**Compatibility and Verification:**
Replaces memory faults with structured error returns (`ZMARSHALLINGERROR` or `ZBADARGUMENTS`). API and ABI definitions remain stable. Verification involves passing malformed buffer lengths and null ACL structures, verifying clean error returns.

### Synchronous response processing crashes on invalid user-supplied output buffers

When completing synchronous requests, `process_sync_completion` (`zookeeper.c:5429-5452`) copies response payloads into caller-allocated buffers. If an application supplies a negative buffer capacity less than -1 to `zoo_get` or `zoo_wget`, the capacity is interpreted as an enormous unsigned integer, triggering an out-of-bounds `memcpy`. Passing a `NULL` buffer with a positive capacity similarly faults in `memcpy`. In `zoo_get_children2` and `zoo_wget_children2`, the stat structure pointer `*stat` is written unconditionally without verifying that `stat` is non-null.

Because `memcpy` faults occur on the background I/O thread during reply processing, an application's output buffer error terminates the entire client process rather than returning an error to the caller.

**Remediation Design:**
In `process_sync_completion`, verify output buffer pointers and lengths before executing memory copies: suppress copying if `len < 0` or if the destination buffer pointer is `NULL`, updating `*buffer_len` to reflect required capacity. In `zoo_get_children2`, guard stat assignment with `if (stat)`.

**Compatibility and Verification:**
Protects background worker threads from crashes caused by application buffer parameter mistakes. API and ABI specifications remain unchanged. Verification involves issuing synchronous reads with null destination pointers, negative capacities, and null stat pointers, confirming safe execution.

### Synchronous multi-transactions abort upon receiving header-only error replies

When processing synchronous multi-transaction replies, `process_sync_completion` (`zookeeper.c:3641-3655`) invokes `deserialize_multi` unconditionally, even when the server returned an error status in the response header (`sc->rc != 0`). Responses such as `ZNOTREADONLY` (emitted by read-only servers rejecting writes), `ZSESSIONMOVED`, or cluster throttling responses consist solely of a `ReplyHeader` with no transaction payload body.

Because no transaction body exists, `deserialize_MultiHeader` fails immediately on unpopulated bytes, leaving loop structures uninitialized. The parser consumes garbage bytes until hitting `assert(entry)` (or dereferencing `NULL` in release builds). Consequently, executing any synchronous `zoo_multi` transaction containing a write operation on a read-only session causes the entire application process to abort on the I/O thread.

**Remediation Design:**
In `process_sync_completion`, inspect `sc->rc` before attempting transaction deserialization. If `sc->rc != 0`, bypass `deserialize_multi` entirely, invoke `cleanup_failed_multi(zh, cptr->xid, sc->rc, cptr)` to populate caller result slots with the header error, and complete the synchronous wait cleanly.

**Compatibility and Verification:**
Synchronous transactions on read-only sessions return `ZNOTREADONLY` with populated result records instead of aborting the process. Public interfaces remain identical. Verification involves issuing a synchronous multi-operation against a read-only server and verifying clean error reporting.

### Asynchronous watch removals corrupt caller context memory in multithreaded runtimes

In `aremove_watches` (`zookeeper.c:5021-5029`), when local watch removal is requested (`local = 1`), code compiled under `#ifdef THREADED` casts the caller's context pointer `data` to `struct sync_completion*` and invokes `notify_sync_completion`. The routine attempts to lock a mutex, set synchronization flags, and signal condition variables within arbitrary user memory.

When an application invokes the asynchronous API `zoo_aremove_watches`, `data` represents application-defined context data rather than an internal completion primitive. Consequently, the multithreaded library corrupts user heap memory and risks hanging in `pthread_mutex_lock`.

**Remediation Design:**
Verify that `completion == SYNCHRONOUS_MARKER` prior to invoking `notify_sync_completion`. When called asynchronously, leave the caller's data buffer untouched, return `ZOK`, and skip completion signaling, matching single threaded semantics.

**Compatibility and Verification:**
Eliminates memory corruption during asynchronous local watch removals. Public interfaces remain identical. Verification requires invoking `zoo_aremove_watches` with `local = 1` and a guarded context buffer, asserting that memory remains uncorrupted.

### Public watch removal APIs declare invalid completion function pointer types

In `include/zookeeper.h` (lines 1955-1957, 1996), `zoo_aremove_watches` and `zoo_aremove_all_watches` declare their callback parameter as `void_completion_t *completion`. Because `void_completion_t` is already defined as a function pointer typedef (`typedef void (*void_completion_t)(...);`), this signature declares a pointer to a function pointer.

This declaration mismatch creates two severe hazards:
- A developer who follows the header signature and passes the address of a callback variable (`&cb`) causes the runtime to jump to the memory address of the local variable upon reply, resulting in an immediate crash.
- Passing a callback function directly (`cb`) generates an incompatible pointer type error on modern compilers (such as GCC 14+), which treat this warning as a fatal compilation failure by default.

**Remediation Design:**
Correct the declarations in `include/zookeeper.h`, `zookeeper.c`, and internal headers to `void_completion_t completion`. Because function pointers and pointers to function pointers share identical representation on standard calling conventions, ABI compatibility is preserved.

**Compatibility and Verification:**
Resolves compiler diagnostic errors and prevents memory execution faults. Code passing unnecessary pointer references (`&cb`) will require updating the call site. Verification involves compiling and executing asynchronous watch removals under strict compiler warning settings.

### Unsolicited or unmatched replies trigger process assertion aborts

When processing ordinary response packets, `zookeeper_process` (`zookeeper.c:3602-3611`) matches the incoming frame against the head of the pending request queue (`sent_requests`). Unless handle closure has been initiated, the implementation executes `assert(cptr)`.

If an unsolicited reply arrives—whether generated by an anomalous server, an intermediary proxy, or an unmatched control packet—the process aborts immediately in debug builds and dereferences `NULL` in release builds.

**Remediation Design:**
Replace the assertion abort with graceful protocol mismatch handling: close the deserialization archive, free the packet buffer, and trigger a connection teardown via `handle_socket_error_msg(zh, __LINE__, __func__, ZRUNTIMEINCONSISTENCY, ...)` through `api_epilog`.

**Compatibility and Verification:**
Unsolicited packets trigger standard connection loss and reconnect cycles rather than crashing the client process. API and ABI specifications remain unchanged. Verification involves injecting an unsolicited response frame from a mock server and confirming clean failover.

### Frame deserialization over-reads input buffers and ignores decode failures

When creating input archives for response frames, `zookeeper_process` initializes the archive with `bptr->curr_offset` (`zookeeper.c:3549-3551`). Because `curr_offset` includes the 4-byte length prefix in addition to the frame body, deserializers are permitted to read up to 4 bytes beyond the allocated heap buffer. In addition, return codes from `deserialize_ReplyHeader` and specific record deserializers are universally ignored.

If a server transmits a truncated or malformed frame:
- Deserializers read out of bounds into adjacent heap memory.
- Deserialization failures leave stack structures partly uninitialized, passing undefined memory pointers into application callbacks and deallocation routines (`deallocate_*`).
- Malformed SASL responses over-read similarly, propagating raw negative errno codes from deserializers.

**Remediation Design:**
Initialize input archives strictly using the payload length (`bptr->len` and `zh->input_buffer->len`). Inspect all deserialization return codes: on header decode failures, trigger a socket teardown with `ZCONNECTIONLOSS`; on payload decode failures, invoke the completion's failure path with `ZCONNECTIONLOSS` and schedule an I/O thread teardown.

**Compatibility and Verification:**
Prevents heap over-reads and uninitialized memory deallocations when encountering malformed network data. Public interfaces remain identical. Verification involves transmitting truncated frames under memory sanitizers and verifying clean error handling.

### Authentication response processing faults on handles with unconfigured credentials

When receiving an authentication response with xid -4, `auth_completion_func` (`zookeeper.c:2172-2178`) logs the authentication scheme via `zh->auth_h.auth->scheme` without verifying whether any credentials were ever configured on the session.

If an unsolicited authentication reply arrives on a handle where `zoo_add_auth` was never invoked, `zh->auth_h.auth` is `NULL`, causing an immediate null-pointer dereference on the background I/O thread.

**Remediation Design:**
Verify `zh->auth_h.auth != NULL` before accessing scheme strings, logging `"(null)"` and safely bypassing completion invocation when the internal credential list is empty.

**Compatibility and Verification:**
Prevents denial-of-service crashes from unsolicited authentication packets. Public interfaces remain unchanged. Verification involves transmitting an unexpected xid -4 response to an unauthenticated session and verifying that it is discarded cleanly.

### Unvalidated SASL input parameters trigger buffer overruns and integer overflow

SASL protocol parsing exhibits multiple integer sign and boundary vulnerabilities:
- A token length of -1 is deserialized by `ia_deserialize_buffer` into `(NULL, -1)`. In `zoo_sasl_client_step` (`zk_sasl.c:368`), this is passed to Cyrus SASL's `sasl_client_step`, where the length parameter is unsigned, causing Cyrus to receive `(NULL, UINT_MAX)` (4 GiB).
- Cyrus SASL output lengths are unsigned, but `queue_sasl_request` accepts them as signed `int`. Output lengths exceeding `0x7fffffff` wrap negative, causing `oa_serialize_buffer` to invoke `memcpy` with massive sizes.
- No limits are placed on application-provided `sasl_callback_t` arrays or the frequency of secret requests per connection.

**Remediation Design:**
In `zoo_sasl_client_step`, pass `(NULL, 0)` when server data is null. Validate client data lengths against `0x7fffffff`, treating oversized tokens as mechanism failures with `ZSYSTEMERROR`. Apply reasonable defensive bounds to callback structures.

**Compatibility and Verification:**
Hardens the client against corrupted or hostile SASL challenges. application level APIs remain unchanged. Verification involves fuzzing SASL frame lengths and evaluating token exchanges under address sanitizers.

### SASL final token responses leak into standard request routing

In `zoo_sasl_client_step` (`zk_sasl.c:383-397`), when a non-GSSAPI mechanism completes its final client step with `SASL_OK` while producing an output token, the implementation enqueues the token and immediately marks SASL authentication as complete. Session establishment is finalized, and standard request processing resumes.

Because the final authentication token is queued behind watch and credential frames, its response carries a standard transaction ID and arrives after the client has exited SASL mode. The response enters ordinary request routing, triggering `assert(cptr)` or tearing down the session with `ZRUNTIMEINCONSISTENCY`.

**Remediation Design:**
Harmonize non-GSSAPI mechanisms with GSSAPI handling: when `sasl_client_step` returns `SASL_OK` with an output token, set `is_last_packet = 1` and remain in `ZOO_SASL_INTERMEDIATE`. The final response is then processed cleanly by `process_sasl_response` before the session is finalized.

**Compatibility and Verification:**
Permits SASL mechanisms requiring final client tokens to complete authentication successfully. Public interfaces remain unchanged. Verification requires testing with a SASL mechanism producing final client tokens and asserting successful connection establishment.

### SASL credential stack buffers are not securely scrubbed on error paths

In `_zsasl_getsecret` (`zk_sasl.c:486-601`), password file data and callback results reside in stack buffers (`buf` and `new_passwd`). Memory scrubbing via `memset` is executed only on successful completion paths and is susceptible to compiler dead-store elimination. Early return paths for file read errors, callback failures, and length overruns return without zeroing either buffer.

Consequently, plaintext credentials linger in stack memory, where they may be exposed in core dumps or compromised by subsequent stack over-reads.

**Remediation Design:**
Centralize function exits through a single cleanup block that scrubs both buffers using compiler-barrier zeroization routines (`explicit_bzero`, `memset_s`, or a volatile loop) across all return paths.

**Compatibility and Verification:**
Enhances credential security in memory without modifying return codes or external behavior. Verification involves auditing compiler output to ensure memory zeroization instructions are preserved.

### Unsuppressed SIGPIPE signals on severed sockets terminate application processes

When writing to a broken TCP connection, operating systems deliver a `SIGPIPE` signal to the process unless explicitly suppressed. On Linux plain sockets, suppression is handled via `MSG_NOSIGNAL`. However, on macOS, `SEND_FLAGS` is defined as `SO_NOSIGPIPE`, which represents a socket option rather than a valid send flag. Furthermore, TLS writes across all POSIX platforms traverse OpenSSL BIO layers without signal suppression.

Writing to a reset peer raises an unhandled `SIGPIPE` signal, immediately terminating applications that have not configured a process-wide `SIG_IGN` handler.

**Remediation Design:**
On macOS and BSD systems, configure `SO_NOSIGPIPE` via `setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, &val, sizeof(val))` immediately after descriptor creation (`zookeeper.c:2476, 2681`), and set `SEND_FLAGS = 0`. For TLS connections, configure OpenSSL BIO layers to use `MSG_NOSIGNAL` or mask `SIGPIPE` across thread write operations.

**Compatibility and Verification:**
Eliminates abrupt process termination upon broken network connections. Public interfaces remain unchanged. Verification involves writing to a severed socket pair on macOS and Linux over TLS and verifying that writes return error codes without raising signals.

### NULL string pointer formatting arguments crash non-glibc runtimes

Multiple diagnostic logging calls pass unvalidated string pointers to `%s` format specifiers:
- `log_env` formats `getlogin()`, which returns `NULL` in daemonized, containerized, or headless environments (`zookeeper.c:1310`).
- `init_ssl_for_socket` logs CA file loading with `fd->cert->ca`, which is `NULL` when certificates are unspecified (`zookeeper.c:2881`).
- `zookeeper_init_internal` formats `host=%s` prior to validating the host argument (`zookeeper.c:1395`).

While glibc safely substitutes `(null)` for null pointer arguments, standard C libraries on other platforms (such as Musl or BSD libc) trigger segmentation faults.

**Remediation Design:**
Validate string pointers prior to formatting, substituting the literal string `"(null)"` when pointers are uninitialized.

**Compatibility and Verification:**
Eliminates crashes in containerized and non-glibc environments. Log output remains consistent with glibc behavior. Verification consists of initializing sessions with null host strings and verifying safe logging.

### Out-of-bounds indexing and string truncation overflows in log_message

In `log_message` (`zk_log.c:134-186`), the severity prefix string is resolved via `dbgLevelStr[curLevel]` without bounds checking. Invoking the function with an out-of-range integer reads beyond the array boundary, passing an invalid pointer to `%s`. Furthermore, when formatting long function or file identifiers, `snprintf` return values exceeding buffer capacity or returning negative errors cause offset calculations to overflow, passing invalid buffer boundaries into `vsnprintf`.

**Remediation Design:**
Validate that `curLevel` resides within valid bounds ($0 \le \text{level} \le 4$), mapping invalid values to a safe default (`ZOO_INVALID`). Clamp formatting offsets to the maximum buffer capacity, skipping `vsnprintf` if no buffer room remains.

**Compatibility and Verification:**
Prevents memory corruption and buffer overflows during diagnostic logging. Public interfaces remain unchanged. Verification involves invoking `log_message` with extreme severity levels and long identifier strings under memory checkers.

### Unchecked memory allocations result in null-pointer dereferences and orphaned state

Numerous heap allocation calls across the client library lack return-value validation:
- Memory allocations for `zh->hostname` (`zookeeper.c:1434`) and path prefixing in `prepend_string` (`zookeeper.c:1718`) pass directly to string copy routines.
- Allocations in `add_auth_completion`, watch event creation (`zookeeper.c:3573`), watch registrations (`zookeeper.c:3681`), and hashtable key collection (`zk_hashtable.c`) dereference results without checking for `NULL`.
- If memory allocation fails in `zoo_add_auth`, the function returns `ZMARSHALLINGERROR` while leaving the completion registered, allowing it to fire erroneously on subsequent operations.
- Jute-generated deserializers use `calloc` results without checking allocations.

**Remediation Design:**
Validate allocation results across all entry points, propagating established error codes (`ENOMEM` from initialization, `ZSYSTEMERROR` from authentication, `ZMARSHALLINGERROR` from asynchronous submission). On background I/O threads where error recovery paths do not exist, log an error and call `abort()` to fail deterministically rather than corrupting memory. Ensure registered completions are revoked if subsequent steps fail.

**Compatibility and Verification:**
Replaces undefined memory dereferences with predictable error handling. API and ABI specifications are preserved. Verification involves injecting allocation failures via memory wrapper mocks.

### /dev/urandom read failures trigger assertion aborts or infinite loops

In `setup_random` (`zookeeper.c:753-782`), random entropy is acquired from `/dev/urandom` in a loop governed by `assert(rc > 0 || errno == EINTR)`. If an unexpected read error occurs (such as `EPERM` under constrained container sandboxes):
- Debug builds abort the process via assertion failure.
- Release builds compiled with `NDEBUG` remove the assertion, causing the loop to spin indefinitely without making progress, hanging the I/O thread at 100% CPU.

**Remediation Design:**
Check read return codes explicitly: on any non-EINTR failure, close the file descriptor and fall back to the existing pseudo-random initialization logic utilizing `getpid()` and clock values.

**Compatibility and Verification:**
Ensures client initialization succeeds under constrained sandboxes without hanging or aborting. Public interfaces remain identical. Verification involves mocking `read` on `/dev/urandom` to return `EPERM` and verifying graceful initialization.

### close_zsock reads past application-allocated zsock_t structures

The public header `zookeeper.h` defines `struct zsock_t`, whose size varies depending on whether `HAVE_OPENSSL_H` is defined (16 bytes without OpenSSL, 32 bytes with OpenSSL on 64-bit platforms). When an application compiled without OpenSSL links statically against a library archive built with OpenSSL, `close_zsock` reads `ssl_sock` and `ssl_ctx` past the 16-byte boundary of the application's structure.

If the adjacent stack or heap memory contains non-zero bytes, `close_zsock` interprets them as OpenSSL pointers, invoking `SSL_free` on invalid memory addresses.

**Remediation Design:**
Separate descriptor closure from TLS deallocation: restrict the public `close_zsock` function to closing the underlying socket descriptor `sock`, while maintaining an internal, non-exported routine for freeing OpenSSL structures owned by the library.

**Compatibility and Verification:**
Eliminates structure size mismatches across static library linkages. Public ABI stability is preserved. Verification involves invoking `close_zsock` on non-TLS descriptors within statically linked applications.

### Invoking zookeeper_close in forked multithreaded children deadlocks on parent threads

The multithreaded runtime registers no `pthread_atfork` handlers. When a process forks while maintaining an active ZooKeeper session, the child process inherits handle memory but none of the parent's background threads. If the child attempts to clean up inherited resources by calling `zookeeper_close`:
- The child blocks attempting to acquire `zh_lock` if a parent thread held the mutex at the instant of forking.
- If the lock is acquired, `adaptor_finish` attempts to join POSIX threads that do not exist in the child, blocking indefinitely.

**Remediation Design:**
Record the creating process identifier (`getpid()`) within the handle during initialization. In `zookeeper_close`, check if the current process differs from the handle's creator: in child processes, close accessible socket descriptors, skip lock acquisitions and thread joins, suppress callback dispatch, return `ZOK`, and leak the handle structures safely.

**Compatibility and Verification:**
Allows forked worker processes to clean up inherited state without deadlocking. API and ABI specifications remain unchanged. Verification involves forking with an active multithreaded session, invoking `zookeeper_close` in the child, and asserting immediate, clean return.

### Undefined handle lifecycle operations lack debugging diagnostics

The C client leaves lifecycle invariants—such as avoiding use-after-close, double-closing handles, or invoking APIs concurrently with handle destruction—entirely to application management. Violating these invariants produces heap corruption, memory leaks, and mysterious deadlocks that are difficult to locate.

**Remediation Design:**
In debug builds, maintain a global, mutex-guarded registry of active handles. Upon entering any public API function, verify that the handle resides in the active registry. If an application attempts a double close or accesses a deallocated handle, emit an actionable diagnostic to `stderr` and abort immediately. Release builds omit the registry to preserve maximum throughput.

**Compatibility and Verification:**
Assists developers in diagnosing lifecycle bugs during development without imposing release-mode overhead. Verification consists of executing lifecycle error tests in debug builds and verifying descriptive error emissions.

### Dynamic library unloading via dlclose causes crashes upon thread termination

During initialization, `prepareTSDKeys` (`zk_log.c:44-54`) allocates two thread-specific data keys with `freeBuffer` registered as their destructor, without providing a mechanism to release them upon library unloading. If an application dynamically loads `libzookeeper_mt` via `dlopen`, issues logging calls, and subsequently unloads the library with `dlclose`, the thread-specific keys remain registered in the threading subsystem.

When application worker threads later terminate, the POSIX threading runtime invokes `freeBuffer` at an unmapped memory address, crashing the process.

**Remediation Design:**
Implement an explicit library destructor (`__attribute__((destructor))`) that unregisters both thread-specific keys using `pthread_key_delete`.

**Compatibility and Verification:**
Enables safe dynamic loading and unloading of the client library within plugin architectures. Public interfaces remain identical. Verification involves loading the shared library via `dlopen`, logging from a worker thread, calling `dlclose`, and terminating the thread.

## Races

### Unsynchronized server list access across error handling and read-only probing

The reconfiguration mutex `reconfig_lock` guards server list modifications in `update_addrs`, `zoo_cycle_next_server`, and `zoo_get_current_server`. However, `handle_error` (`zookeeper.c:2124-2132`) and the read-only probing routines in `ping_rw_server` (`zookeeper.c:2469, 2792-2799`) read and advance `zh->addrs` without acquiring this mutex.

If an application thread invokes `zoo_set_servers` while the background I/O thread is handling a connection failure, `addrvec_free(&zh->addrs)` runs on the application thread, deallocating vector memory. The I/O thread concurrently executes `addrvec_next`, copying a `sockaddr_storage` structure out of freed heap memory into `addr_cur`.

**Remediation Design:**
Acquire `reconfig_lock` around all reads and cursor advancements across `handle_error` and `ping_rw_server`. To prevent holding the mutex during blocking network operations in `ping_rw_server`, copy the target endpoint into a stack-local variable under the lock before initiating socket connections. Furthermore, ensure logging calls are deferred or executed outside the critical section to prevent lock inversion.

**Compatibility and Verification:**
Eliminates use-after-free races during concurrent reconfiguration. API and ABI specifications remain unchanged. Verification involves issuing rapid concurrent calls to `zoo_set_servers` while simulating server disconnections under ThreadSanitizer.

### process global read-write server hints leak across distinct client sessions

When `ping_rw_server` discovers an available read-write server, it stores a pointer to `zh->addr_rw_server` in a file-scoped static pointer `addr_rw_server` (`zookeeper.c:288, 2520`). When any session subsequently initiates a connection attempt, `zookeeper_interest` consumes this global pointer (`zookeeper.c:2674-2677`).

In multi-session applications connected to different ZooKeeper ensembles, this global pointer creates severe cross-session contamination:
- A read-only probe discovering a read-write server in cluster A causes an unrelated session to cluster B to connect to cluster A. Because cluster A does not recognize session B's identifier, session B is immediately expired, deleting its ephemeral nodes.
- If handle A is destroyed before handle B reconnects, the file-static pointer dangles, causing handle B to read deallocated memory.

**Remediation Design:**
Remove the file-static pointer completely. Encapsulate read-write server discovery within the session handle by adding a local structure and discovery flag (`zh->has_addr_rw_server`). Because `zhandle_t` is opaque to external applications, modifying its internal layout preserves ABI compatibility.

**Compatibility and Verification:**
Isolates server discovery hints strictly to their originating session handle. Public interfaces remain identical. Verification requires running multiple client sessions targeting distinct mock ensembles, confirming that failover hints never cross session boundaries.

### zookeeper_interest returns stale descriptors following in-call connection teardowns

In `zookeeper_interest` (`zookeeper.c:2650`), the output descriptor `*fd` is populated from `zh->fd->sock` at the start of the function. If a receive timeout expires (`line 2758`) or if a read-only to read-write server transition occurs (`line 2797`) later within the identical call, the underlying socket is closed, yet the function returns the previously recorded file descriptor number (and sets read interest on the read-write path).

Both single threaded applications and multithreaded `do_io` loops subsequently pass this closed descriptor to `poll` or `select`. If the operating system reallocates that descriptor number to an unrelated file or network socket on another thread, ZooKeeper's event loop monitors and consumes readiness events from unrelated application I/O. Furthermore, polling a closed descriptor produces `POLLNVAL`, which spins the event loop.

**Remediation Design:**
Reset output arguments to safe sentinel values (`*fd = -1` and `*interest = 0`) immediately following any connection teardown within `zookeeper_interest`.

**Compatibility and Verification:**
Ensures event loops correctly detect closed connections and wait for subsequent connection attempts without polling invalid descriptors. Public interfaces remain unchanged. Verification involves simulating receive timeouts and confirming that `*fd` evaluates to -1.

### Saturated wakeup pipes trigger spurious forced socket disconnections

To notify the background I/O thread of new outbound requests, `wakeup_io_thread` (`mt_adaptor.c:335-353`) writes a single byte to a nonblocking pipe. If the pipe buffer fills (e.g., 64 KB on Linux during DNS delays or TLS handshake stalls), `write` returns `EAGAIN`, which `wakeup_io_thread` translates to `ZSYSTEMERROR`. This error propagates through `adaptor_send_queue` to `nonblocking_send`, which force-closes the socket descriptor from the application thread and sets `zh->state = ZOO_NOTCONNECTED_STATE`.

A full wakeup pipe simply indicates that the I/O thread already has pending wakeup notifications. Treating pipe saturation as a fatal transport failure causes the client to tear down active TCP connections under high application request throughput.

**Remediation Design:**
In `wakeup_io_thread`, treat `EAGAIN` (and `WSAEWOULDBLOCK` on Windows) as a successful notification, since existing bytes in the pipe will wake the I/O thread. Maintain a lightweight atomic flag to ensure at most one wakeup byte is queued in the pipe concurrently.

**Compatibility and Verification:**
Maintains stable network connections under high asynchronous request concurrency. Public interfaces remain identical. Verification requires pausing the I/O thread and submitting requests beyond pipe buffer limits, verifying that connections remain intact.

### Concurrent asynchronous submissions during handle closure trigger unsynchronized TLS writes

When `close_requested` is true, `adaptor_send_queue` (`mt_adaptor.c:346-353`) invokes `flush_send_queue` directly on the calling thread to dispatch `CLOSE_OP`. However, `nonblocking_send` executes this same path for any asynchronous API call (`zoo_a*`) issued concurrently with handle closure.

If an application thread submits an asynchronous request while `zookeeper_close` is executing, it initiates an uncoordinated `SSL_write` on the caller's thread while the background I/O thread may be actively executing `SSL_read` on the same OpenSSL context, violating OpenSSL thread-safety rules.

**Remediation Design:**
In `nonblocking_send`, skip `adaptor_send_queue` whenever `close_requested` is set, returning `ZMARSHALLINGERROR` immediately without performing socket I/O. Restrict direct caller-thread flushes strictly to `zookeeper_close` after the background I/O thread has been joined.

**Compatibility and Verification:**
Prevents concurrent OpenSSL context corruption during shutdown. Public interfaces remain unchanged. Verification involves submitting asynchronous requests concurrently with `zookeeper_close` under ThreadSanitizer.

### Public export of driver entry points in libzookeeper_mt permits concurrent state machine corruption

The shared library export regular expression in `Makefile.am` exports `zookeeper_interest` and `zookeeper_process` from `libzookeeper_mt`, despite their intended restriction to the single threaded library (`#ifndef THREADED`).

Applications linking against `libzookeeper_mt` can invoke these driver functions from application threads concurrently with the background I/O thread, driving state transitions and socket polling simultaneously and corrupting internal data structures.

**Remediation Design:**
In multithreaded builds, detect whether `zookeeper_interest` or `zookeeper_process` is invoked from any thread other than the internal I/O worker thread. If called from an external thread, return `ZOK` with `*fd = -1` from `zookeeper_interest` and `ZINVALIDSTATE` from `zookeeper_process`, refusing to drive the state machine concurrently.

**Compatibility and Verification:**
Preserves exported symbols for link-time compatibility while preventing concurrent runtime corruption. Verification consists of calling driver functions from application threads on a multithreaded handle and asserting safe error returns.

### Exported SASL internal symbols drive uncoordinated negotiation from application threads

Internal SASL state machine functions (`zoo_sasl_connect`, `zoo_sasl_client_start`, `zoo_sasl_client_step`, `zoo_sasl_mark_failed`) are exported by `libzookeeper_mt` despite being declared only in internal headers (`src/zk_sasl.h`).

An application declaring and invoking these functions directly drives Cyrus SASL operations concurrently with the background I/O thread without synchronization, violating Cyrus thread-safety constraints.

**Remediation Design:**
Restrict symbol visibility using linker version scripts. To preserve symbol availability without breaking ABI, detect calling thread identity within these routines, returning `ZSYSTEMERROR` if invoked from non-I/O threads.

**Compatibility and Verification:**
Enforces thread isolation for SASL negotiation. Public API and ABI specifications remain stable. Verification involves invoking SASL driver routines from application threads and asserting rejection.

### single threaded library shares static transaction counters and log buffers across threads

The single threaded library (`libzookeeper_st`) defines transaction counters and log formatting buffers as unsynchronized process-static variables (`st_adaptor.c:78-85`, `zk_log.c:80-88`).

While `libzookeeper_st` creates no background threads, multi-threaded applications frequently operate independent single threaded handles across distinct application threads. These threads race when incrementing `xid` and formatting log messages, producing garbled logs and transaction identifier collisions.

**Remediation Design:**
Define log formatting and timestamp buffers with thread-local storage duration (`__thread` or `__declspec(thread)`). Manage `xid` counter increments via atomic primitives.

**Compatibility and Verification:**
Enables safe multi-threaded usage of distinct single threaded client handles. Public interfaces remain identical. Verification involves operating separate single threaded sessions concurrently across multiple threads under ThreadSanitizer.

### Unsynchronized access to default watcher and context pointers causes pointer tearing

Setting watcher callbacks via `zoo_set_watcher` and `zoo_set_context` updates `zh->watcher` and `zh->context` without synchronization (`zookeeper.c:491-496`). Concurrently, the I/O thread and event dispatchers read both pointers without synchronization.

A thread updating both fields concurrently with event delivery risks pointer tearing: the dispatching thread pairs an obsolete callback function with a newly updated context pointer, causing application crashes when the callback casts context data to an incompatible type.

**Remediation Design:**
Synchronize watcher and context reads and writes using a dedicated spinlock or lightweight mutex held strictly during pointer copy, ensuring consistent pairing without holding locks during callback execution.

**Compatibility and Verification:**
Guarantees atomic updates for watcher and context pairs. Public APIs remain unchanged. Verification involves concurrent watcher updates and event dispatches under ThreadSanitizer.

### zookeeper_init_ssl and log_env invoke non-thread-safe C runtime functions

`zookeeper_init_ssl` parses certificate strings using `strtok` (`zookeeper.c:1530-1533`), which stores internal parsing position in global static state. `log_env` invokes `getlogin()` (`zookeeper.c:1310`), which returns pointers to shared static storage.

Concurrent initialization across multiple threads corrupts parsing tokens and races with application `strtok` calls.

**Remediation Design:**
Replace `strtok` with re-entrant `strtok_r`, matching the usage in `resolve_hosts`. Substitute `getlogin_r` where available, accompanied by NULL validation.

**Compatibility and Verification:**
Eliminates global state races during client initialization. Public interfaces remain identical. Verification involves concurrent client initializations across multiple threads.

### Shared SASL callback structures race on in-place secret buffer modification

When multiple handles share a `sasl_callback_t` array (such as created by `zoo_sasl_make_basic_callbacks`), `_zsasl_getsecret` (`zk_sasl.c:440-601`) modifies the secret buffer inside the shared callback block in-place without synchronization.

If credentials rotate or multiple handles negotiate simultaneously, one handle's file read overwrites the buffer while another handle's Cyrus step is actively reading it. Cyrus receives a torn password and permanently transitions the handle to `ZOO_AUTH_FAILED_STATE`.

**Remediation Design:**
Allocate an independent, per-session copy of the secret buffer within `zoo_sasl_client_t`. Wrap the `SASL_CB_PASS` callback to populate the session-specific buffer rather than mutating shared memory.

**Compatibility and Verification:**
Permits multiple sessions to share callback definitions safely during credential rotation. Verification involves authenticating multiple sessions concurrently against rotating credential files under ThreadSanitizer.

### Race condition during handle closure delivers completions out of submission order

When an asynchronous response arrives, `zookeeper_process` dequeues the completion entry from `sent_requests`, evaluates watch registrations, and enqueues the entry onto `completions_to_process`. If `zookeeper_close` runs concurrently on another thread, it invokes `free_completions`, which immediately appends `ZCLOSING` failures for subsequent requests onto `completions_to_process`.

The completion worker thread dispatches `ZCLOSING` failures for later operations before delivering the successful response for earlier operations, violating FIFO completion guarantees.

**Remediation Design:**
Hold `zh_lock` continuously from the moment an entry is dequeued from `sent_requests` until it is appended to `completions_to_process`, ensuring atomic ordering with respect to concurrent closure.

**Compatibility and Verification:**
Guarantees FIFO completion delivery order during concurrent connection termination. Public interfaces remain identical. Verification requires stress-testing asynchronous completions during concurrent handle closure under ThreadSanitizer.

### Concurrent zoo_add_auth invocations transmit mismatched credentials

`zoo_add_auth` (`zookeeper.c:5330-5346`) appends credential structures to the handle under the authentication lock, releases the lock, and invokes `send_last_auth_info`, which transmits whichever entry resides at the tail of the list.

If threads A and B call `zoo_add_auth` concurrently, both credentials are appended before either transmits. Both threads then transmit thread B's credential. The server authenticates B twice, and thread A's credential is never sent until the next reconnection, causing subsequent operations to fail with `ZNOAUTH`.

**Remediation Design:**
Hold the authentication lock across both list insertion and frame dispatch, or pass the specific `authinfo` pointer directly to a specialized frame dispatch helper.

**Compatibility and Verification:**
Ensures every `zoo_add_auth` invocation transmits its own credential. Public interfaces remain identical. Verification involves issuing concurrent `zoo_add_auth` calls across distinct threads and inspecting transmitted packets.

## Silently lost settings

### zoo_set_servers accepts empty string, drops connection, and leaves unconnectable handle

The reconfiguration entry point `zoo_set_servers` (`zookeeper.c:1551-1572`) validates against `NULL` pointers, but accepts an empty host string (`""`). Passing an empty string yields a host count of 0 from `count_hosts`, leading `resolve_hosts` to return `ZOK` with an empty address vector. `update_addrs` interprets this result as an active ensemble modification, empties `zh->addrs`, and force-closes the transport socket.

The function returns `ZOK`, misleading the caller into assuming reconfiguration succeeded. However, the client is placed in `ZOO_NOTCONNECTED_STATE`, all pending operations fail, and subsequent reconnection attempts fail continuously in `socket(AF_UNSPEC, ...)` without backoff delays. The session expires on the ensemble while the client spins unproductively.

**Remediation Design:**
In `zoo_set_servers`, validate that the server string contains at least one non-whitespace character before committing modifications, returning `ZBADARGUMENTS` if empty. Maintain the existing address list intact when validation fails. Note that `zookeeper_init` with an empty string retains existing behavior, allowing applications to defer endpoint specification until runtime.

**Compatibility and Verification:**
Applications passing empty strings receive explicit `ZBADARGUMENTS` errors and remain connected to their existing ensemble. API and ABI specifications remain unchanged. Verification involves calling `zoo_set_servers(zh, "")` and confirming that `ZBADARGUMENTS` is returned while the active connection is preserved.

### Endpoint comparison ignores port numbers during reconfiguration and rebalancing

The endpoint comparison functions `addrvec_contains` and `addrvec_eq` (`addrvec.c:119-152, 256-271`) evaluate socket addresses by matching IP address fields (`sin_addr` or `sin6_addr`), omitting port checks (`sin_port` or `sin6_port`).

This omission causes topology changes that alter only port numbers to be discarded silently:
- If an ensemble moves its client ports (e.g., migrating ports from 2181 to 3181 across identical IP hosts), `zoo_set_servers` evaluates `addrvec_eq` to true and discards the new server list, causing subsequent reconnections to fail against decommissioned ports.
- During ensemble rebalancing, adding multiple distinct server instances hosted on a single IP address causes them to be treated as identical entries, corrupting probability weights in `addrs_old` and `addrs_new`.

**Remediation Design:**
Update `addrvec_contains` to compare port fields (`sin_port` for IPv4 and `sin6_port` for IPv6) in addition to IP address bytes. Because `addrvec_eq` and rebalancing routines rely on `addrvec_contains`, updating this helper ensures consistent port-aware comparisons across all configuration pathways.

**Compatibility and Verification:**
Ensemble updates modifying port numbers take effect immediately. Public interfaces remain identical. Verification involves updating server lists with altered ports and confirming that connection establishment targets the updated port numbers.

### Asymmetric address comparison ignores topology changes when current list contains duplicates

When comparing address vectors, `addrvec_eq` (`addrvec.c:256-271`) validates that the entry count matches and that every address in the current vector exists within the candidate vector. However, duplicate IP addresses frequently appear in address vectors when multiple DNS names resolve to shared IP endpoints.

If the active address vector contains duplicate entries (e.g., `{A, A}`) and an updated host list resolves to `{A, B}`, `addrvec_eq` evaluates to true because the vector lengths match and every entry in the current vector (`A`) exists in the candidate vector. `update_addrs` takes its early exit branch, discarding the update without logging. The client never attempts to connect to node B and fails to fail over if node A terminates.

**Remediation Design:**
Enforce bidirectional vector comparison: verify that counts match, that every address in the active vector exists in the candidate vector, and that every address in the candidate vector exists in the active vector. Vectors containing identical address sets with differing duplicates (such as `{A, A, B}` and `{A, B, B}`) remain equivalent, avoiding unnecessary reconnections.

**Compatibility and Verification:**
Ensures client handles correctly adopt topology changes when starting from address vectors with duplicate entries. Public interfaces remain unchanged. Verification involves generating duplicate DNS resolutions, modifying one host target, and confirming that the client adopts the updated server endpoint.

### SASL password files exceeding 1023 bytes are silently truncated

In `_zsasl_getsecret` (`zk_sasl.c:517-588`), credential file reading via `fread` is bounded to 1023 bytes into a stack-allocated buffer. If a credential file contains a secret exceeding 1023 bytes on its initial line, `fread` reads the first 1023 bytes and appends a null terminator without error. Because `len` cannot exceed 1023 on this code path, the `SASL_BUFOVER` length check never executes.

The client authenticates using a truncated secret prefix. The server rejects the credentials, and the session enters `ZOO_AUTH_FAILED_STATE` without diagnostic indication that file truncation occurred. Furthermore, encrypted secret files passed to application password callbacks are corrupted by truncation.

**Remediation Design:**
When reading without custom password callbacks, read up to 1024 bytes into a 1025-byte buffer. If the initial line exceeds 1023 bytes without a terminating newline and the file contains additional unread bytes, return `SASL_BUFOVER` immediately to signal secret overflow.

**Compatibility and Verification:**
Prevents silent credential truncation and emits clear diagnostics. Public interfaces remain unchanged. Verification involves configuring a SASL secret file exceeding 1023 bytes and confirming that `SASL_BUFOVER` is reported.

### Client certificate and key are silently ignored when password omitted

In `init_ssl_for_socket` (`zookeeper.c:2892-2919`), client identity certificates are loaded only when `cert`, `key`, and `passwd` are all non-null. If a user supplies a 3-field configuration string (`"ca.pem,client.pem,client.key"`), `passwd` resolves to `NULL`.

The identity loading block is skipped entirely without logging warnings or errors. When communicating with servers requiring client authentication, the TLS handshake fails cryptographically. With optional client auth, the session establishes anonymously, causing ACL-protected operations to fail with unexpected `ZNOAUTH` errors. Unencrypted private keys cannot be loaded without providing an arbitrary dummy password token.

**Remediation Design:**
Restructure identity loading to require only `cert` and `key`, treating `passwd` as optional. When `passwd` is null, register a dummy password callback returning failure via `SSL_CTX_set_default_passwd_cb` to prevent OpenSSL from prompting on the terminal if an encrypted key is encountered.

**Compatibility and Verification:**
Supports unencrypted client private keys without dummy password tokens. Public interfaces remain identical. Verification involves connecting with unencrypted certificates and keys without password fields.

## Misleading diagnostics

### zerror reports "unknown error" for TLS and quota exceeded status codes

The error string formatter `zerror` (`zookeeper.c:5227-5299`) lacks enumeration mappings for `ZSSLCONNECTIONERROR` (-15) and server-enforced quota exceeded errors (-125).

When applications encounter TLS connection aborts or quota rejections, diagnostic loggers and exception handlers print `"unknown error"`, obscuring the root cause of failures from operators.

**Remediation Design:**
Add explicit cases in `zerror`:
- Map `ZSSLCONNECTIONERROR` (-15) to `"ssl connection error"`.
- Map -125 to `"Quota has exceeded"` (matching the canonical Java client string).

**Compatibility and Verification:**
Provides clear diagnostic strings for common error codes. Public interfaces remain identical. Verification involves querying `zerror` with -15 and -125 and confirming accurate string descriptions.

### Spurious "Exceeded deadline" warnings emitted from stale state during disconnection

In `zookeeper_interest` (`zookeeper.c:2636-2813`), `next_deadline` tracks expected event loop wakeup timestamps. It is updated only upon successful completion of `zookeeper_interest` when an active socket is present, and is never cleared during connection teardowns.

When a connection terminates and subsequent reconnection attempts fail immediately (such as `ECONNREFUSED` or network unreachability), `zookeeper_interest` exits before refreshing `next_deadline`. Subsequent event loop passes compare current time against the obsolete deadline from the prior connection, emitting `WARN` messages ("Exceeded deadline by Nms") with monotonically increasing delay values. This falsely suggests event loop starvation during simple network disconnects.

**Remediation Design:**
Reset `next_deadline = 0` inside `cleanup` during connection teardown. Only evaluate deadline delays when `next_deadline` was established within the active connection lifecycle.

**Compatibility and Verification:**
Eliminates false event loop latency warnings during network disconnects. Public interfaces remain identical. Verification involves simulating rapid connection refusals and verifying absence of spurious deadline warnings.

### Transaction identifier mismatch log line transposes expected and received values

When handling `ZRUNTIMEINCONSISTENCY` errors in `zookeeper_process` (`zookeeper.c:3612-3626`), the error log formats `"unexpected server response: expected %#x, but received %#x"`. However, arguments are passed in reverse order: `hdr.xid` (received) followed by `cptr->xid` (expected).

Diagnostic logs report inverted transaction IDs, confusing operators diagnosing rare protocol desynchronization events.

**Remediation Design:**
Invert parameter ordering to pass `cptr->xid` (expected) followed by `hdr.xid` (received).

**Compatibility and Verification:**
Corrects diagnostic log output. Public interfaces remain unchanged. Verification involves injecting an xid mismatch and inspecting logged message text.

### os.arch environment diagnostic logs kernel release instead of machine architecture

During client initialization logging, `log_env` (`zookeeper.c:1298-1302`) formats `Client environment:os.arch=%s` using `utsname.release` rather than `utsname.machine`.

Diagnostic logs record the OS kernel version (e.g., `os.arch=5.15.0-91-generic`) rather than CPU architecture (`x86_64`, `aarch64`), preventing support teams from identifying architecture mismatches.

**Remediation Design:**
Map `utsname.machine` to `os.arch`, and optionally introduce an explicit `os.release` field for kernel release strings.

**Compatibility and Verification:**
Corrects client diagnostic environment dumps. Public interfaces remain identical. Verify by capturing log environment output and comparing `os.arch` with system architecture.

### Ping latency calculation subtracts monotonic time from wall-clock time

In `send_ping` (`zookeeper.c:2449`), `zh->last_ping` is recorded using `get_system_time`, which queries monotonic clock time (`CLOCK_MONOTONIC_RAW` on Linux and macOS). When processing ping responses in `zookeeper_process` (`zookeeper.c:3553-3560`), current time is measured using `gettimeofday` (wall-clock epoch time).

The calculation in `calculate_interval` subtracts monotonic seconds from calendar epoch seconds. The debug log `"Got ping response in %d ms"` prints nonsensical values (often exceeding billions of milliseconds or truncating negatively).

**Remediation Design:**
Update ping response timing in `zookeeper_process` to use `get_system_time`, measuring elapsed duration consistently against the monotonic clock.

**Compatibility and Verification:**
Restores accurate millisecond ping latency reporting in debug logs. Public interfaces remain unchanged. Verification involves enabling debug logging and verifying that reported ping latency matches network round-trip time.

### Log messages emitted in forked child processes retain parent process ID

In `log_message` (`zk_log.c:136-181`), the process identifier is cached in a static variable upon initial invocation (`static int pid = 0;`).

In pre-fork architectures, child worker processes inherit the cached PID from the parent process. All log entries emitted by child processes report the parent's PID, preventing log aggregation systems from correlating log events with specific processes.

**Remediation Design:**
Retrieve `getpid()` dynamically on each emitted log message, removing static PID caching.

**Compatibility and Verification:**
Accurately attributes log lines in forked processes. Public interfaces remain unchanged. Verification involves logging in a child process following `fork()` and verifying process identifier fidelity.

### zookeeper_init with comma-delimited strings returns NULL with stale errno

When an application passes a string containing only delimiters (e.g., `",,"`), `count_hosts` returns a positive count, bypassing early validation. In `resolve_hosts` (`zookeeper.c:870, 1029`), `strtok_r` finds no valid tokens, the loop exits with `avec->count == 0`, and the function returns `ZSYSTEMERROR` without setting `errno`.

`zookeeper_init` returns `NULL`, but `errno` remains unassigned or retains stale values from prior library calls (such as `getlogin` in `log_env`), misleading callers inspecting `strerror(errno)`.

**Remediation Design:**
In `resolve_hosts`, set `errno = EINVAL` whenever initial token parsing yields no valid address components.

**Compatibility and Verification:**
Ensures predictable `errno` reporting upon initialization syntax errors. Public interfaces remain unchanged. Verification involves initializing with `",,"` and checking that `errno` is set to `EINVAL`.

## Build System Defects

### CMake syntax error drops specified OpenSSL search path and misinterprets boolean build flags

In `CMakeLists.txt:209`, the variable assignment contains a syntax error: `set(OPENSSL_ROOT_DIR,${WITH_OPENSSL})`. The misplaced comma causes CMake to parse `OPENSSL_ROOT_DIR,${WITH_OPENSSL}` as a single variable name with an empty value, leaving `OPENSSL_ROOT_DIR` unset. By contrast, the Cyrus SASL configuration block at line 71 uses standard whitespace separation.

Furthermore, condition checks at lines 69 and 207 compare configuration variables strictly against the literal string `"OFF"`. As a result, standard CMake boolean falsy representations—including `no`, `off`, `FALSE`, and `0`—fail to match and are evaluated as truthy.

On environments where OpenSSL resides in non-standard prefixes (such as custom Homebrew cellars or opt directories), configuring with `-DWITH_OPENSSL=/opt/openssl` warns and silently falls back to standard system paths or LibreSSL, ignoring the explicit path. Conversely, explicitly attempting to disable features via `-DWITH_OPENSSL=no` or `-DWITH_CYRUS_SASL=no` fails to disable them, causing CMake to auto-detect and link against discovered system libraries unexpectedly.

**Remediation Design:**
1. Correct the assignment in `CMakeLists.txt:209` to standard CMake syntax: `set(OPENSSL_ROOT_DIR "${WITH_OPENSSL}")`.
2. Normalize boolean condition handling at lines 69 and 207 using idiomatic CMake boolean evaluation (`if(NOT WITH_OPENSSL)` or `if(WITH_OPENSSL STREQUAL "OFF" OR NOT WITH_OPENSSL)`), ensuring all standard falsy values disable the respective dependency.
3. Validate path values appropriately when distinguishing between enabled flags (`ON`) and user-supplied filesystem directory paths.

**Compatibility and Verification:**
Build configurations specifying explicit OpenSSL roots will correctly link against the targeted library. Configurations passing conventional false values (`no`, `off`, `0`) will now properly disable TLS and SASL support rather than enabling it. The shared library ABI is affected only by whether `HAVE_OPENSSL_H` is defined. CI testing should incorporate configuration runs passing custom `-DWITH_OPENSSL=/custom/path` and falsy arguments (`-DWITH_OPENSSL=OFF`, `-DWITH_OPENSSL=no`) to verify both discovery and disabling logic.

### Autotools probe for SOCK_CLOEXEC fails on modern C compilers

In `configure.ac:231-243`, the configure probe for `SOCK_CLOEXEC` evaluates target platform support using `AC_TRY_RUN` with an outdated C test snippet utilizing implicit `int main()`. On modern C99/C11/C17 compilers (such as GCC 14+ and Clang 15+), implicit `int` declarations are treated as fatal compilation errors.

Because Autoconf treats compilation failure identically to runtime probe failure, `has_sock_cloexec` evaluates to `no`. Lines 250-253 emit a misleading warning claiming that `SOCK_CLOEXEC` is undefined on the platform, silently discarding `--with-sock-cloexec`. Consequently, socket file descriptors are created without `SOCK_CLOEXEC` and inadvertently leak across child `fork()` and `exec()` invocations. (Note that the multi-threaded self-pipe in `mt_adaptor.c:244` also lacks `FD_CLOEXEC` flags, compounding socket descriptor leakage).

A parallel issue exists in the IPv6 feature probe (`configure.ac:202-211`), which also omits standard includes (`<stdlib.h>`) and uses implicit `main()` signatures. While this affects only `USEIPV6` test compilation flags rather than core library runtime code, it causes test suites (`tests/TestClient.cc` and `tests/TestSASLAuth.cc`) to silently skip IPv6 validation scenarios. Furthermore, CMake's build uses `check_symbol_exists` (`CMakeLists.txt:61`), creating behavioral divergence between Autotools and CMake builds on the same host.

**Remediation Design:**
1. Modernize `configure.ac` probes to conform to standard ISO C, including required system headers (`<stdlib.h>`, `<sys/socket.h>`) and using explicit `int main(void)` signatures.
2. Replace `AC_TRY_RUN` with compile-time checks (`AC_CHECK_DECLS` or compile/link checks) to detect `SOCK_CLOEXEC`, enabling reliable detection in cross-compilation environments.
3. Explicitly set `FD_CLOEXEC` on the internal notification pipe descriptors created in `mt_adaptor.c:244`.

**Compatibility and Verification:**
Ensures `--with-sock-cloexec` functions as intended on modern toolchains, preventing descriptor leaks into child processes. Public ABI is unchanged. Verification involves running `./configure --with-sock-cloexec` under GCC 14+ or Clang and inspecting generated `config.h` definitions, as well as verifying that IPv6 test suites run under `make check`.

### Static library archives export unnamespaced internal global symbols

The static archives (`libzookeeper_st.a` and `libzookeeper_mt.a`) expose approximately one hundred internal global functions and variables that are not part of the public API and are never declared in installed public headers. Examples include highly generic identifiers such as `hash` (`src/hashtable/hashtable.c:57`), `do_io`, `get_xid`, `flush_send_queue`, `api_prolog`, `adaptor_init`, `addrvec_next`, `hashtable_insert`, `deliverWatchers`, and `MAX_RW_TIMEOUT`.

While the shared library (`libzookeeper_mt.so` / `.dylib`) hides these symbols via link-stage symbol regex export maps (`Makefile.am:49`), static archives retain all non-static object symbols. Applications statically linking ZooKeeper that define their own functions or globals with common names (such as `hash`) encounter duplicate symbol link errors. More hazardous is archive link ordering: if the application symbol satisfies the unresolved reference first, the client library's internal hash table may silently invoke an application-defined hashing function with incompatible signatures and semantics, precipitating memory corruption or erratic hashtable behavior.

**Remediation Design:**
1. Audit all internal symbols across `src/`: mark single-translation-unit functions and variables with static linkage (`static`).
2. Prefix all remaining internal symbols that require cross-unit visibility with a private library namespace (e.g., `zk_` or `zoo_internal_`).
3. Update internal test hook wrappers (`tests/wrappers.opt`) that intercept functions like `flush_send_queue`, `get_xid`, `deliverWatchers`, and `activateWatcher` to match namespaced identifiers.
4. Alternatively, use objcopy symbol localization or incremental linking (`ld -r`) during static archive construction to localize internal symbols before final packaging.

**Compatibility and Verification:**
Prevents symbol collisions in statically linked applications. Public API and shared library ABI are unaffected. Releases should document the cleanup of undeclared static symbols in case legacy applications inappropriately relied on unexported internals. Verification consists of compiling a static test application defining generic functions like `hash(const char *)` and linking against `libzookeeper_mt.a`.

### Cygwin legacy host resolution branch fails compilation

In `zookeeper.c:890-944`, an obsolete conditional compilation branch guarded by `__CYGWIN__` fails compilation. The implementation references undeclared identifiers `addrs` and `addr`, attempts to access a nonexistent member `addrs->list` on `addrvec_t`, and attempts to mutate `zh->addrs.count` through a pointer to constant `const zhandle_t *`.

The refactoring of the address resolution pipeline to use `addrvec_t` was never applied to this legacy Cygwin block. Modern Cygwin environments provide standard POSIX `getaddrinfo` support, rendering this divergent code path unnecessary and unbuildable.

**Remediation Design:**
Remove the obsolete `#if defined(__CYGWIN__)` conditional blocks in `zookeeper.c:890-944` and the associated macro guard on `getaddrinfo_errno` (`zookeeper.c:784`), allowing Cygwin to utilize the standard POSIX `getaddrinfo` resolution implementation shared across all other POSIX platforms.

**Compatibility and Verification:**
Restores the ability to compile the C client on Cygwin platforms. Standard POSIX platform builds and client ABI remain completely unaffected. Verification involves compiling `zookeeper.c` with `-D__CYGWIN__` or running a dedicated Cygwin CI build step.

## Windows Platform Defects

The Windows port of the ZooKeeper C client relies on platform-specific emulation layers in `winport.c` and conditional branches within the I/O multiplexer and network adapters. Several architectural defects in thread emulation, socket descriptor management, and I/O event polling impact correctness and stability on Win32 and Win64 platforms.

### winport pthread emulation flaws and global close override corrupt runtime state

The POSIX compatibility layer in `winport.c` exhibits several critical implementation bugs across thread synchronization and file descriptor handling:
1. **Thread-specific storage pointer assignment:** In `pthread_key_create` (`winport.c:136-166`), the allocated key pointer is assigned to the local pass-by-value function argument (`key = newkey`) rather than dereferencing the caller-supplied pointer (`*key = *newkey`). The caller's key variable remains uninitialized (or zero), leaking both the heap allocation and the allocated Win32 TLS slot. Consequently, `zk_log.c:44-78` invokes `TlsGetValue(0)` and `TlsSetValue(0)`. If another library or module in the host process owns TLS slot 0, `get_format_log_buffer` overwrites its data, while `pthread_key_delete` attempts `free(&key)` on a stack address.
2. **Missing return statements in condition variable emulation:** `pthread_cond_wait` and `pthread_cond_broadcast` (`winport.c:207-243`) declare integer return types but omit return statements, leading to undefined behavior if callers inspect return codes.
3. **CRT namespace collision on close:** `winport.c:254-258` exports a global function `int close(SOCKET fd)` that forwards directly to `closesocket(fd)`. When statically linking `zookeeper.lib`, this definition overrides the C Runtime (CRT) standard library `close()` for the entire host process. Any application code calling `close()` on standard file descriptors (such as those returned by `_open()` or `_pipe()`) passes CRT file descriptors to `closesocket()`, failing with `WSAENOTSOCK` and permanently leaking open file handles.
4. **Missing constructor execution under MSVC:** In `include/winconfig.h`, GCC `__attribute__(x)` extensions are macro-defined to empty expressions. Consequently, GCC constructor attributes on `prepareTSDKeys` and the `get_xid` generator do not execute at load time under MSVC, leaving thread-specific keys uninitialized unless explicitly initialized.

**Remediation Design:**
1. Fix `pthread_key_create` to assign through the caller's pointer (`*key = *newkey`) and properly invoke `TlsFree` on cleanup. Remove the invalid `free(&key)` invocation in `pthread_key_delete`.
2. Add explicit `return 0;` on successful completion in `pthread_cond_wait` and `pthread_cond_broadcast`.
3. Eliminate the global `close` symbol override from `winport.c`. Replace internal calls to `close()` across Windows source paths with explicit calls to `closesocket()`.
4. Provide explicit initialization hooks for thread-specific data (`prepareTSDKeys`) and `get_xid` under MSVC (such as via `DllMain` process attach or explicit internal initialization guards in `zookeeper_init`).

**Compatibility and Verification:**
Eliminates CRT file descriptor hijacking in Windows applications and prevents TLS memory corruption. Removal of the global `close()` function is an application-visible symbol change that should be highlighted in release notes. Verification involves static linking tests verifying that CRT `_open()` and `close()` operate independently of socket operations.

### Windows I/O multiplexer omits exceptfds and retains stale interest on timeout

In `mt_adaptor.c:412-462`, the Windows implementation of `do_io` does not pass an `exceptfds` set to `select()`. Under Winsock, asynchronous non-blocking connection failures (such as TCP connection refusal) are signaled exclusively via the `exceptfds` set rather than `writefds`. Because `exceptfds` is omitted, connection rejections are not detected when reported by the networking stack.

Furthermore, when `select()` times out (`rc == 0`), the Windows I/O loop fails to clear `interest`. It proceeds to invoke `zookeeper_process` passing whatever interest flags were requested by `zookeeper_interest`, behaving as if the socket signaled readiness. During connection establishment, this requested interest includes `ZOOKEEPER_WRITE`.

When `check_events` (`zookeeper.c:3079-3118`) queries `SO_ERROR` prematurely upon a timeout, a socket still undergoing TCP handshake returns error 0 (in-progress). The client erroneously concludes the connection has established and calls `prime_connection`, invoking `send()` against an unconnected socket. The call fails with `WSAENOTCONN`, aborting the connection attempt prematurely at the first heartbeat tick ($T/3$) rather than waiting for normal connection timeout ($2T/3$). Under TLS (`ZOO_SSL_CONNECTING_STATE`), this false readiness initiates OpenSSL handshakes over an unconnected socket.

**Remediation Design:**
1. Populate and pass an `exceptfds` set containing the socket descriptor to `select()` during `ZOO_CONNECTING_STATE` and `ZOO_SSL_CONNECTING_STATE`.
2. Map an exception notification on the connecting socket directly to write readiness (`ZOOKEEPER_WRITE`) before calling `zookeeper_process`, allowing `SO_ERROR` extraction logic to process the socket failure immediately.
3. Reset `interest = 0` whenever `select()` returns 0 (timeout), preventing spurious readiness processing.

**Compatibility and Verification:**
Ensures immediate failure reporting on refused connections and prevents premature connection termination for handshakes taking longer than $T/3$ seconds. Public API and ABI remain unchanged. Verification involves testing connection behavior against refused ports (expecting prompt failure) and unroutable endpoints (expecting proper timeout at $2T/3$).

### Windows builds with Cyrus SASL fail to link due to missing getpass symbol

When Cyrus SASL is detected on Windows systems, CMake includes `zk_sasl.c` in the build (`CMakeLists.txt:66-79, 190-192`). In `zk_sasl.c:439-444, 572`, the `getpassphrase` helper invokes `getpass()` to read interactive authentication credentials without console echo.

Because the Microsoft Visual C++ runtime does not provide `getpass()`, linking fails with an unresolved external symbol error for `getpass`, preventing ZooKeeper builds with SASL capability on Windows.

**Remediation Design:**
Implement a platform-specific fallback implementation for `_WIN32` using standard Win32 console APIs or CRT `_getch()`. The helper reads characters without console echo into a fixed buffer until encountering carriage return or line feed, masking input securely and providing parity with POSIX `getpass()`.

**Compatibility and Verification:**
Enables Cyrus SASL compilation on Windows platforms without modifying public API or ABI. Verification involves compiling the client with SASL enabled on Windows under MSVC and validating interactive credential prompting.

### Windows diagnostics pass pointer handles to select and corrupt IPv6 address formatting

Two diagnostic and inspection routines on Windows contain fundamental type-mapping errors:
1. **Invalid socket handle in readability checks:** In `isSocketReadable` (`zookeeper.c:3500-3507`), the Windows branch executes `FD_SET(zh->fd, &rfds)`. On Windows with socket abstraction structures enabled, `zh->fd` is a pointer to `zsock_t` rather than a `SOCKET` descriptor. When debug logging is enabled, `select()` receives an invalid pointer handle, fails with `WSAENOTSOCK`, and resets socket latency calculations to zero.
2. **Truncated IPv6 formatting:** In `format_endpoint_info` (`zookeeper.c:5383-5385`), the Windows code path invokes `inet_ntoa` against `sin6_addr` when formatting IPv6 endpoints. `inet_ntoa` interprets the input as a 32-bit IPv4 `in_addr`, reading only the first four bytes of the 128-bit IPv6 address and producing an erroneous dotted-decimal IPv4 string (for example, displaying `[2001:db8::1]:2181` as `32.1.13.184`). All diagnostic logs and return values from `zoo_get_current_server` reflect this corrupted address string.

**Remediation Design:**
1. In `isSocketReadable`, pass the underlying descriptor `zh->fd->sock` to `FD_SET`.
2. In `format_endpoint_info`, replace `inet_ntoa` with `inet_ntop(AF_INET6, ...)` or `getnameinfo`, conforming to the formatting pipeline used across POSIX platforms.

**Compatibility and Verification:**
Corrects debug-level socket latency tracking and ensures accurate IPv6 address reporting from `zoo_get_current_server` on Windows. Public API and ABI are unchanged. Verification involves validating `zoo_get_current_server` output against known IPv6 endpoints on Windows.

### Windows loopback signaling socket pair vulnerable to local connection hijacking and descriptor leaks

In `mt_adaptor.c:125-164`, `create_socket_pair` creates a loopback TCP socket pair to emulate the POSIX notification pipe used to wake the I/O thread. The implementation binds a listener to `INADDR_LOOPBACK` with port 0 (ephemeral port allocation) and enables `SO_REUSEADDR`. It initiates a non-blocking connect from a client socket (`fds[0]`) and accepts the incoming connection on `fds[1]`.

The function performs no verification that the connection returned by `accept()` originated from its own client socket. On a multi-tenant or shared workstation, any concurrent local process scanning loopback ports or connecting to the ephemeral listener between `listen()` and `connect()` can be accepted as `fds[1]`. If hijacked, wake signaling bytes sent via `wakeup_io_thread` are delivered to the foreign process, while the client's own socket remains unaccepted or orphaned. The I/O thread misses immediate notifications and remains blocked until periodic heartbeat timeouts. Furthermore, the return values of `socket()` and `getsockname()` are unchecked, and `fds[0]` leaks if `connect()` fails.

**Remediation Design:**
1. Remove `SO_REUSEADDR` from the loopback listener socket; port 0 dynamic allocation does not require address reuse and enabling it increases hijacking exposure.
2. Following `accept()`, query `getpeername()` on the accepted socket and verify that its remote port and address match the local ephemeral port and address of `fds[0]` obtained via `getsockname()`. If an extraneous connection is detected, close it immediately and re-invoke `accept()`.
3. Check return codes on all socket creation and binding calls, ensuring `fds[0]` is closed if connection establishment fails.

**Compatibility and Verification:**
Hardens internal thread signaling on Windows without altering public APIs or ABI. Verification involves launching concurrent local connection attempts against the ephemeral port during socket pair initialization and verifying that only the matching local client socket is accepted.

## Preserved Architectural Behaviors and Intentional Semantics

Certain client behaviors appear counter-intuitive, hazardous, or non-standard upon initial inspection, yet represent established architectural contracts, protocol design decisions, or backward compatibility requirements. Modifying these behaviors within maintenance releases would break existing production deployments. These items are cataloged below along with their technical context and operational rationale.

### Omission of TLS hostname validation

During TLS initialization, the client configures peer verification via `SSL_VERIFY_PEER` and loads default and configured certificate authority stores (`zookeeper.c:2876-2891, 2935`), but does not configure server name verification (`SSL_set1_host` or `X509_VERIFY_PARAM_set1_host`). Consequently, the client accepts any valid certificate signed by a recognized CA, regardless of whether the certificate's subject alternative names (SAN) match the connection host or IP address.

**Operational Rationale:**
While omitting server name verification presents impersonation risks in untrusted network topologies, altering this default would break a substantial portion of existing ZooKeeper deployments. Many production clusters connect using raw IP addresses or internal domain aliases that do not match the hostnames provisioned in server certificates. Furthermore, the C client API does not currently provide mechanisms to supply per-endpoint server name indications (SNI) or expected hostnames.

**Architectural Recommendation:**
Hostname verification must not be enabled unconditionally in maintenance releases. Instead, an opt-in configuration mechanism (such as a new initialization API or extended connection string option) should be introduced, allowing applications to explicitly mandate hostname verification when certificate infrastructures support it. Security documentation should clearly articulate this operational requirement.

### ZCLOSING completion error semantics during client shutdown

When an application invokes `zookeeper_close`, pending completion callbacks are drained and invoked with `ZCLOSING` via `free_completions`. However, `free_completions` does not discard operations already queued in the outbound `to_send` buffer. Instead, the final shutdown sequence enqueues a `CLOSE_OP` request behind all pending outbound operations and flushes the entire pipeline to the server (`zookeeper.c:4014-4048`). Consequently, operations whose callbacks reported `ZCLOSING` may still be delivered to and executed by the ensemble.

**Operational Rationale:**
The public API documentation (`include/zookeeper.h:880-883`) explicitly specifies that `zookeeper_close` flushes outstanding send requests before severing the connection. Applications frequently rely on this guarantee to enqueue final state updates immediately prior to terminating the handle. Purging unsent buffers during shutdown would silently drop mutations. 

Furthermore, network disconnections (`ZCONNECTIONLOSS`) exhibit identical ambiguity regarding whether a submitted request was committed by the quorum. `ZCLOSING` indicates that the client runtime will not wait for or report the server's reply; it does not guarantee that the operation was not executed.

**Architectural Recommendation:**
Preserve current flush-and-close pipeline semantics. Clarify public API documentation to explicitly state that `ZCLOSING` denotes the cessation of response processing by the client handle, not atomic cancellation of outbound requests.

### Single-threaded child process session termination following fork

In the single-threaded client adapter (`libzookeeper_st`), client handles maintain no background worker threads. When an application forks and invokes `zookeeper_close` within the child process, the handle proceeds directly through the final close sequence (`zookeeper.c:4027-4057`), transmitting a `CLOSE_OP` over the inherited socket descriptor before freeing the handle. This terminates the ZooKeeper session on the server, purging all associated ephemeral nodes while the parent process may still be actively using the session. Under TLS, transmitting the close record over the shared socket descriptor also corrupts the parent's TLS record stream sequence.

**Operational Rationale:**
In Unix process models, child processes frequently inherit client handles during daemonization or worker spawning, where the child takes ownership of the application lifecycle after the parent process terminates. In those architectures, the child must be capable of gracefully closing the session. Introducing automatic guards that prohibit child processes from closing handles would result in orphaned server-side sessions persisting until session timeout.

**Architectural Recommendation:**
Retain standard handle teardown semantics. Document operational requirements for multi-process applications: child processes that do not intend to own the inherited ZooKeeper session must not invoke `zookeeper_close` on shared handles. If handle cleanup in the child is required without terminating the ensemble session, the child should close only the underlying socket descriptor without sending protocol shutdown frames, or applications should initialize ZooKeeper handles strictly post-fork.

### process global random seed mutation during address resolution

During server address resolution, unless deterministic ordering is explicitly configured, `resolve_hosts` reseeds process global pseudo-random number generators (`srandom` and `srand48`) using entropy read from `/dev/urandom`, subsequently drawing random values via `random()` and `drand48()` to shuffle ensemble addresses (`zookeeper.c:753-782, 1035-1038`, `addrvec.c:192-203`). Because address resolution occurs periodically, these libc generators are reseeded repeatedly throughout the application lifetime.

**Operational Rationale:**
Many applications historically relied on client-side address shuffling to achieve uniform connection distribution across ensemble nodes without explicitly initializing process entropy. Unconditionally removing the reseed logic could leave applications that do not initialize libc generators with identical, deterministic pseudo-random sequences, causing connection storms on identical quorum nodes upon synchronized restarts.

**Architectural Recommendation:**
The reseeding behavior is retained to maintain uniform connection distribution across existing deployments. However, future architectural revisions should replace process global libc PRNG functions with an internal, thread-safe pseudo-random number generator (such as a splitmix or xoshiro generator maintained within the client handle state), isolating library randomization from application level PRNG state. Note that the critical crash bug associated with `/dev/urandom` descriptor exhaustion (assert failure under debug builds or infinite retry loops under `NDEBUG`) is addressed separately as an operational defect in Section 3.
