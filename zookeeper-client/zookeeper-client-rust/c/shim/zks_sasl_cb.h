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
 * Cyrus SASL Callback Integration Interface.
 *
 * Defines the internal bridge between the legacy authentication callback handling
 * and the Cyrus SASL engine wrapper. This header encapsulates Cyrus-specific type
 * definitions and memory management conventions, restricted strictly to the SASL
 * compilation units to prevent Cyrus headers from leaking into the broader C shim
 * translation units.
 */

#ifndef ZKS_SASL_CB_H_
#define ZKS_SASL_CB_H_

#ifndef ZKS_SASL_GLUE
#error "zks_sasl_cb.h is for the two SASL glue units only"
#endif

#include "zks_internal.h"

#ifndef HAVE_CYRUS_SASL_H
#error "the SASL glue units need HAVE_CYRUS_SASL_H"
#endif

#include <sasl/sasl.h>

/* Maximum permitted password length matching the legacy C client specification. */
#define ZSASL_MAX_PASSWORD_LEN 1023

/*
 * Arithmetic overflow detection helpers leveraging compiler built in primitives.
 * Expressions return non-zero on overflow, guaranteeing safe buffer dimensioning.
 * ZKS_CLEANUP binds automatic RAII cleanup functions upon scope exit.
 */
#define ZKS_ADD_OVF(a, b, r) __builtin_add_overflow((a), (b), (r))
#define ZKS_MUL_OVF(a, b, r) __builtin_mul_overflow((a), (b), (r))
#define ZKS_CLEANUP(fn) __attribute__((cleanup(fn)))

/*
 * Secret ingestion sink callback. Receives credential byte slices and marshals
 * them into session-scoped storage suitable for consumption by Cyrus SASL.
 */
typedef int (*zks_secret_sink)(void *arg, const unsigned char *data, size_t len,
                               sasl_secret_t **out);

/*
 * Evaluates password retrieval callbacks without holding synchronization locks,
 * feeding secret bytes directly into the provided sink while preserving the
 * immutable state of the shared callback descriptor.
 */
ZKS_HIDDEN int zks_getsecret_unlocked(sasl_conn_t *conn, void *context, zks_secret_sink sink,
                                      void *sink_arg, sasl_secret_t **psecret);

/*
 * Canonical function pointer type representing generic Cyrus SASL callback routines.
 */
typedef int (*zks_sasl_proc)(void);

/*
 * Safely converts typed SASL callback function pointers to the generic representation
 * through intermediate void(*)(void) casting, satisfying strict compiler function cast warnings.
 */
#define ZKS_SASL_PROC(f) ((zks_sasl_proc)(void (*)(void))(f))

/* Returns non-zero if the candidate callback matches the default library password provider. */
ZKS_HIDDEN int zks_is_library_pass_cb(zks_sasl_proc proc);

#endif
