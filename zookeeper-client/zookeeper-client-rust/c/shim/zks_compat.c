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
 * Compatibility stubs for legacy internal APIs and archive symbols.
 *
 * Implements historical synchronization hooks (zoo_lock_auth, zoo_unlock_auth)
 * originally exposed by the multithreaded C client adaptor, acquiring the per-handle
 * authentication mutex in threaded mode or acting as no-ops in single threaded mode.
 * Provides close_zsock for static archive linking compatibility.
 */

#include <unistd.h>

#include "zks_internal.h"

int zoo_lock_auth(zhandle_t *zh)
{
#ifdef THREADED
    return pthread_mutex_lock(&zh->auth_lock);
#else
    ZKS_UNUSED(zh);
    return 0;
#endif
}

int zoo_unlock_auth(zhandle_t *zh)
{
#ifdef THREADED
    return pthread_mutex_unlock(&zh->auth_lock);
#else
    ZKS_UNUSED(zh);
    return 0;
#endif
}

void close_zsock(zsock_t *fd)
{
    if (fd->sock != -1) {
        close(fd->sock);
        fd->sock = -1;
    }
}
