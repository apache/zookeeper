/*
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
 * Shared declarations across C cross validation harness translation units.
 */
#ifndef ZKX_HARNESS_H_
#define ZKX_HARNESS_H_

#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

/* Decodes a sockaddr_storage into IP bytes and port; returns 4, 6, or 0. */
int zkx_addr_decode(const struct sockaddr_storage *ss, unsigned char ip[16],
        unsigned short *port);

/* Populates a sockaddr_storage from an address family (4 or 6), IP bytes, and port. */
void zkx_addr_encode(struct sockaddr_storage *ss, int family,
        const unsigned char *ip, unsigned short port);

#endif
