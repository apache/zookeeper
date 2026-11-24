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
 * Minimal stub header replacing include/zookeeper.h for cross validation builds.
 * Exposes the exact public type definitions and protocol constants required to
 * compile extracted legacy C client sources.
 */
#ifndef ZOOKEEPER_H_
#define ZOOKEEPER_H_

#include <stddef.h>

typedef struct _zhandle zhandle_t;

typedef void (*watcher_fn)(zhandle_t *zh, int type,
        int state, const char *path, void *watcherCtx);

typedef enum {
  ZWATCHTYPE_CHILD = 1,
  ZWATCHTYPE_DATA = 2,
  ZWATCHTYPE_ANY = 3
} ZooWatcherType;

enum ZOO_ERRORS {
  ZOK = 0,
  ZSYSTEMERROR = -1,
  ZBADARGUMENTS = -8
};

#define ZOO_SESSION_EVENT (-1)
#define ZOO_NOTCONNECTED_STATE 999

#endif
