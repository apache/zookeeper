/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define BUILD_LIB
#include "zk_adaptor.h"

void lock_buffer_list(buffer_head_t *l)
{
}
void unlock_buffer_list(buffer_head_t *l)
{
}
void lock_completion_list(completion_head_t *l)
{
}
void unlock_completion_list(completion_head_t *l)
{
}
struct sync_completion *alloc_sync_completion(void)
{
    return (struct sync_completion*)calloc(1, sizeof(struct sync_completion));
}
int wait_sync_completion(struct sync_completion *sc)
{
    return 0;
}

void free_sync_completion(struct sync_completion *sc)
{
    free(sc);
}

void notify_sync_completion(struct sync_completion *sc)
{
}

int process_async(int outstanding_sync)
{
    return outstanding_sync == 0;
}

int adaptor_init(zhandle_t *zh)
{
    return 0;
}

void adaptor_finish(zhandle_t *zh)
{}

int flush_send_queue(zhandle_t *, int);

int adaptor_send_queue(zhandle_t *zh, int timeout)
{
    return flush_send_queue(zh, timeout);
}

int inc_nesting_level(nesting_level_t* nl,int i)
{
    nl->level+=(i<0?-1:(i>0?1:0));
    return nl->level;
}
