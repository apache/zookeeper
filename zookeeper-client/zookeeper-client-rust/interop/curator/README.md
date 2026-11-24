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

# Apache Curator Interoperability Suite for Rust Recipes

This standalone Maven project validates protocol and znode layout
interoperability between the `apache-zookeeper-recipes` Rust crate
(`DistributedLock` and `LeaderLatch`) and Apache Curator (`InterProcessMutex`
and `LeaderLatch`). Because both implementations share Curator's protected
ephemeral-sequential node format and sorting semantics, Java and Rust processes
can contend on identical ZooKeeper paths.

This project has no parent POM and is invoked by CI or via
`dev/run-curator-interop.sh` (or `-Prust-integration` in the Maven module).

## Architecture

- `RustPeer`: launches and manages the `interop_peer` Rust example binary as a
  child process, communicating via a line-oriented command/response protocol
  over `stdin` and `stdout`.
- `CuratorInteropTest`: JUnit 5 test suite exercising concurrent Java Curator
  and Rust recipe participants against a live ZooKeeper server or ensemble.
  Includes `CutProxy`, an in-process ZooKeeper wire-frame TCP proxy that injects
  deterministic connection drops before or after specific `create` or `delete`
  operations.

## Peer Line Protocol

`interop_peer <connect-string>` establishes a ZooKeeper session on a dedicated
runtime thread, prints `READY` to `stdout`, and processes newline-delimited
commands from `stdin` sequentially. Diagnostics are written to `stderr`.

| Command | Response | Description |
|---|---|---|
| `LOCK <path> <ms> [<data>]` | `OK <node>`, `TIMEOUT`, `ERR <e>` | Acquires and retains lock. |
| `TRYLOCK <path> [<data>]` | `OK <node-name>`, `NONE`, `ERR <e>` | nonblocking `try_acquire`. |
| `UNLOCK` | `OK`, `ERR <e>` | Calls `release` on the held lock guard. |
| `DROP_LOCK` | `OK`, `ERR <e>` | Drops lock guard, triggering background delete. |
| `ABANDON_LOCK <path> <ms>` | `OK`, `ERR <e>` | Starts `acquire` and drops future after `<ms>`. |
| `LATCH <path> <id>` | `OK <node-name>`, `ERR <e>` | Starts and retains a `LeaderLatch`. |
| `AWAIT_LEADER <ms>` | `OK`, `TIMEOUT`, `ERR <e>` | Calls `await_leadership` with a timeout. |
| `IS_LEADER` | `OK true`, `OK false` | Queries `is_leader`. |
| `PARTICIPANTS` | `OK <id>:<0 or 1> ...`, `ERR <e>` | Lists participants with leadership flags. |
| `CLOSE_LATCH` | `OK`, `ERR <e>` | Closes the active `LeaderLatch`. |
| `SESSION` | `OK <id-hex> <password-hex>` | Reports active session ID and password in hex. |
| `QUIT` | `OK` | Closes the client session and terminates the peer process. |

Optional `<data>` specifies UTF-8 payload bytes written to the lock znode
(empty by default), allowing Java tests to identify the Rust lock holder.

`ERR` responses carry the Rust `Debug` representation of the error (or
`ERR BadCommand <line>` on malformed input). Participant IDs must not contain
spaces or colons (`:`).

## Test Scenarios

1. `lockIsExclusiveAndFairAcrossLanguages`: Two Curator `InterProcessMutex`
   instances and two Rust peers contend on a shared lock path, verifying mutual
   exclusion and FIFO acquisition order across languages.
2. `latchOrderAndParticipantsAcrossLanguages`: Curator and Rust `LeaderLatch`
   participants elect leaders in creation order and cross-read participant IDs.
3. `rustCreateRecoveryLeavesNoOrphan`: `CutProxy` drops the peer connection
   during participant node creation (both before request delivery and before
   response delivery), verifying protected-node adoption without orphan znodes.
4. `createsAndNodesMatchCurator`: Compares captured wire `CreateRequest` frames
   and created znodes between Rust and Curator 5.7.1 byte-for-byte (container
   parent creation, protected node naming, payload bytes, ACLs, and flags).
5. `lockOrderCountsForeignChildren`: Verifies that non-standard sibling znodes
   participate in lock ordering identically to Curator's `LockInternals` sort.
6. `latchOrderCountsForeignChildren`: Verifies identical foreign-child sorting
   semantics for `LeaderLatch`.
7. `abandonedRustAcquireLeavesNoNode`: Cancelling an in-flight Rust lock
   acquisition cleans up its ephemeral-sequential node and unblocks subsequent
   Curator waiters.
8. `killedRustHolderFreesTheLockAtExpiry`: Forcibly terminating a Rust lock
   holder (`SIGKILL`) retains the lock while the session remains valid and
   transfers ownership to Curator upon session expiration.
9. `lostRustDeleteIsRetriedInTheBackground`: `CutProxy` drops the connection
   when `DROP_LOCK` transmits its `delete` request; background guaranteed-delete
   recovery removes the node after reconnection and unblocks the waiting Curator
   participant.

## Running the Suite

`dev/run-curator-interop.sh` builds the Rust peer binary, starts standalone and
3-node ensemble clusters for each specified server version (`head` and `3.9.6`),
and runs the JUnit test suite:

```sh
cd zookeeper-client-rust
ZK_TEST_PORT_BASE=23400 dev/run-curator-interop.sh head 3.9.6
```

Set `ZK_INTEROP_REPEAT=<n>` to execute `<n>` iterations per topology.

To run manually against a single standalone server:

```sh
cd zookeeper-client-rust
cargo build --locked -p apache-zookeeper-recipes --example interop_peer
dev/test-server.sh start 23400 /tmp/zk-interop
mvn -B -f interop/curator/pom.xml test \
  -Dzk.connect=127.0.0.1:23400 \
  -Drust.peer.bin=$CARGO_TARGET_DIR/debug/examples/interop_peer
dev/test-server.sh stop 23400 /tmp/zk-interop
```

When `zk.connect` or the peer binary is omitted, all tests skip via JUnit
assumptions, allowing `mvn -B -f interop/curator/pom.xml test` to verify Java
compilation offline.
