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
 * Symbol namespace isolation probe. Defines application-owned global functions and
 * variables sharing names with internal globals leaked by the legacy C static
 * archives. While static linking against the legacy C library can fail with
 * duplicate-symbol errors, the Rust archive exports only the public ABI surface,
 * allowing the application's definitions to link and execute without interference.
 */

int hash(void *key);
int do_io(void *zh);
int get_xid(void);
int flush_send_queue(void *zh, int timeout);
int adaptor_init(void *zh);
void adaptor_finish(void *zh);
int api_prolog(void *zh);
int api_epilog(void *zh, int rc);
int addrvec_init(void *avec);
void *hashtable_search(void *h, void *k);
void *create_hashtable(unsigned int minsize);
void deliverWatchers(void *zh, int type, int state, char *path, void **list);
void enter_critical(void *zh);
long get_system_time(void);
int send_ping(void *zh);
void process_completions(void *zh);
int zklink_own_check(void);

/* Writable globals colliding with legacy read-only internal constants. */
int MAX_RW_TIMEOUT = 4242;
int MIN_RW_TIMEOUT = 4343;
double max_load_factor = 0.25;

static int calls;

int hash(void *key)
{
    (void)key;
    calls++;
    return 101;
}

int do_io(void *zh)
{
    (void)zh;
    calls++;
    return 102;
}

int get_xid(void)
{
    calls++;
    return 103;
}

int flush_send_queue(void *zh, int timeout)
{
    (void)zh;
    calls++;
    return 104 + timeout;
}

int adaptor_init(void *zh)
{
    (void)zh;
    calls++;
    return 105;
}

void adaptor_finish(void *zh)
{
    (void)zh;
    calls++;
}

int api_prolog(void *zh)
{
    (void)zh;
    calls++;
    return 106;
}

int api_epilog(void *zh, int rc)
{
    (void)zh;
    calls++;
    return rc;
}

int addrvec_init(void *avec)
{
    (void)avec;
    calls++;
    return 107;
}

void *hashtable_search(void *h, void *k)
{
    (void)k;
    calls++;
    return h;
}

void *create_hashtable(unsigned int minsize)
{
    calls++;
    return minsize == 108 ? &calls : 0;
}

void deliverWatchers(void *zh, int type, int state, char *path, void **list)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)list;
    calls++;
}

void enter_critical(void *zh)
{
    (void)zh;
    calls++;
}

long get_system_time(void)
{
    calls++;
    return 109;
}

int send_ping(void *zh)
{
    (void)zh;
    calls++;
    return 110;
}

void process_completions(void *zh)
{
    (void)zh;
    calls++;
}

/*
 * Invokes each application-defined symbol once and verifies that all return
 * values match this translation unit and that no calls originated from the
 * ZooKeeper client library.
 */
int zklink_own_check(void)
{
    /* Verify that no unexpected calls occurred prior to this check. */
    int bad = calls != 0;

    bad += hash(0) != 101;
    bad += do_io(0) != 102;
    bad += get_xid() != 103;
    bad += flush_send_queue(0, 1) != 105;
    bad += adaptor_init(0) != 105;
    adaptor_finish(0);
    bad += api_prolog(0) != 106;
    bad += api_epilog(0, 7) != 7;
    bad += addrvec_init(0) != 107;
    bad += hashtable_search(&bad, 0) != &bad;
    bad += create_hashtable(108) != &calls;
    deliverWatchers(0, 0, 0, 0, 0);
    enter_critical(0);
    bad += get_system_time() != 109;
    bad += send_ping(0) != 110;
    process_completions(0);
    bad += MAX_RW_TIMEOUT != 4242 || MIN_RW_TIMEOUT != 4343 || max_load_factor != 0.25;
    /* Exactly 16 invocations were made above; any excess indicates library interference. */
    bad += calls != 16;
    calls = 0;
    return bad;
}
