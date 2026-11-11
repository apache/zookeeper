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
 * Control and query interface for the `connect(2)` preload interposer
 * (`zkconf_preload.c`). Test cases resolve these symbols dynamically via
 * `dlsym(RTLD_DEFAULT, ...)` because the preload shared object is injected at
 * runtime rather than linked at build time.
 */

#ifndef ZKCONF_PRELOAD_H_
#define ZKCONF_PRELOAD_H_

#define ZKCONF_CONNECT_PASS 0
#define ZKCONF_CONNECT_EINPROGRESS 1
#define ZKCONF_CONNECT_IMMEDIATE 2

/* Selects the completion behavior applied to subsequent nonblocking `connect(2)` calls. */
void zkconf_connect_mode(int mode);
/* Returns the total number of `connect(2)` calls recorded since process start. */
int zkconf_connect_count(void);
/* Returns the monotonic timestamp (in milliseconds) of `connect(2)` call `index`, or -1. */
long zkconf_connect_time(int index);

#endif /* ZKCONF_PRELOAD_H_ */
