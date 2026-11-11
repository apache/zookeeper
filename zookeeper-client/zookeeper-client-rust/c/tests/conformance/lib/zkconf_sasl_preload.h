/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Control and query interface for the SASL lifecycle preload interposer
 * (`zkconf_sasl_preload.c`). Scenarios resolve these entry points dynamically
 * via `dlsym(RTLD_DEFAULT, ...)`.
 *
 * Recorded event codes (in invocation order):
 *   N  `sasl_client_new`
 *   D  `sasl_dispose` on a non-NULL connection context
 *   C  `close` on a tracked server socket after an address query (`G` or `P`)
 *   G  `getsockname` on a tracked server socket
 *   P  `getpeername` on a tracked server socket
 *   *  application milestone markers appended via `zkconf_sasl_mark`
 */

#ifndef ZKCONF_SASL_PRELOAD_H_
#define ZKCONF_SASL_PRELOAD_H_

/* Copies the NUL-terminated sequence of recorded event codes into `buf`, returning the count. */
typedef int (*zkconf_sasl_events_fn)(char *buf, int cap);
/* Appends a synthetic milestone character `c` to the event sequence. */
typedef void (*zkconf_sasl_mark_fn)(char c);
/*
 * Injects `ENOBUFS` failures into the next `n` `getsockname`/`getpeername`
 * calls on server sockets.
 */
typedef void (*zkconf_sasl_fail_fn)(int n);
/* Queries the count of open server sockets (or whether a thread is blocked at the SASL gate). */
typedef int (*zkconf_sasl_open_fn)(void);
/*
 * Enables (`on=1`) or releases (`on=0`) a gate blocking the next
 * `sasl_client_new` call for up to 10 seconds; `zkconf_sasl_gated` returns 1
 * while a thread is waiting at the gate.
 */
typedef void (*zkconf_sasl_gate_fn)(int on);

#endif /* ZKCONF_SASL_PRELOAD_H_ */
