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

#include <log.h>

#include "thread.h"

DEFINE_LOGGER( LOG, "Thread" )

START_ZKFUSE_NAMESPACE

void Thread::Create(void* ctx, ThreadFunc func)
{
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, _stackSize);
    int ret = pthread_create(&mThread, &attr, func, ctx);
    if(ret != 0) {
        LOG_FATAL( LOG, "pthread_create failed: %s", strerror(errno) );
    }
    // pthread_attr_destroy(&attr); 
    _ctx = ctx;
    _func = func;
}

END_ZKFUSE_NAMESPACE
