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

#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <errno.h>
#ifdef WIN32
#define random rand /* replace POSIX random with Windows rand */
#include <winsock2.h> /* must always be included before ws2tcpip.h */
#include <ws2tcpip.h> /* for sockaddr_storage */
#include "winport.h"
#endif

#include "addrvec.h"

#define ADDRVEC_DEFAULT_GROW_AMOUNT 16

void addrvec_init(addrvec_t *avec)
{
    assert(avec);
    avec->next = 0;
    avec->count = 0;
    avec->capacity = 0;
    avec->data = NULL;
}

void addrvec_free(addrvec_t *avec)
{
    if (avec == NULL)
    {
        return;
    }

    avec->next = 0;
    avec->count = 0;
    avec->capacity = 0;
    if (avec->data) {
        free(avec->data);
        avec->data = NULL;
    }
}

int addrvec_alloc(addrvec_t *avec)
{
    addrvec_init(avec);
    return addrvec_grow_default(avec);
}

int addrvec_alloc_capacity(addrvec_t* avec, uint32_t capacity)
{
    addrvec_init(avec);
    return addrvec_grow(avec, capacity);
}

int addrvec_grow(addrvec_t *avec, uint32_t grow_amount)
{
    unsigned int old_capacity = 0;
    struct sockaddr_storage *old_data = NULL;
    assert(avec);

    if (grow_amount == 0)
    {
        return 0;
    }

    // Save off old data and capacity in case there is a realloc failure
    old_capacity = avec->capacity;
    old_data = avec->data;

    avec->capacity += grow_amount;
    avec->data = realloc(avec->data, sizeof(*avec->data) * avec->capacity);
    if (avec->data == NULL)
    {
        avec->capacity = old_capacity;
        avec->data = old_data;
        errno = ENOMEM;
        return 1;
    }

    return 0;
}

int addrvec_grow_default(addrvec_t *avec)
{
    return addrvec_grow(avec, ADDRVEC_DEFAULT_GROW_AMOUNT);
}

static int addrvec_grow_if_full(addrvec_t *avec)
{
    assert(avec);
    if (avec->count == avec->capacity)
    {
        int rc = addrvec_grow_default(avec);
        if (rc != 0)
        {
            return rc;
        }
    }

    return 0;
}

int addrvec_contains(const addrvec_t *avec, const struct sockaddr_storage *addr)
{
    uint32_t i = 0;
    if (!avec || !addr)
    { 
        return 0;
    }

    for (i = 0; i < avec->count; i++)
    {
        if(memcmp(&avec->data[i], addr, INET_ADDRSTRLEN) == 0)
            return 1;
    }

    return 0;
}

int addrvec_append(addrvec_t *avec, const struct sockaddr_storage *addr)
{
    int rc = 0;
    assert(avec);
    assert(addr);

    rc = addrvec_grow_if_full(avec);
    if (rc != 0)
    {
        return rc;
    }

    // Copy addrinfo into address list
    memcpy(avec->data + avec->count, addr, sizeof(*addr));
    ++avec->count;

    return 0;
}

int addrvec_append_addrinfo(addrvec_t *avec, const struct addrinfo *addrinfo)
{
    int rc = 0;
    assert(avec);
    assert(addrinfo);

    rc = addrvec_grow_if_full(avec);
    if (rc != 0)
    {
        return rc;
    }

    // Copy addrinfo into address list
    memcpy(avec->data + avec->count, addrinfo->ai_addr, addrinfo->ai_addrlen);
    ++avec->count;

    return 0;
}

void addrvec_shuffle(addrvec_t *avec)
{
    int i = 0;
    for (i = avec->count - 1; i > 0; --i) {
        long int j = random()%(i+1);
        if (i != j) {
            struct sockaddr_storage t = avec->data[i];
            avec->data[i] = avec->data[j];
            avec->data[j] = t;
        }
    }
}

int addrvec_hasnext(const addrvec_t *avec)
{
    return avec->count > 0 && (avec->next < avec->count);
}

int addrvec_atend(const addrvec_t *avec)
{
    return avec->count > 0 && avec->next >= avec->count;
}

void addrvec_next(addrvec_t *avec, struct sockaddr_storage *next)
{
    int index;

    // If we're at the end of the list, then reset index to start
    if (addrvec_atend(avec)) {
        avec->next = 0;
    }

    if (!addrvec_hasnext(avec)) {
        if (next) {
            memset(next, 0, sizeof(*next));
        }

        return;
    }

    index = avec->next++;

    if (next) {
        *next = avec->data[index];
    }
}

void addrvec_peek(addrvec_t *avec, struct sockaddr_storage *next)
{
    int index = avec->next;

    if (avec->count == 0) {
        memset(next, 0, sizeof(*next));
        return;
    }

    if (addrvec_atend(avec)) {
        index = 0;
    }

    *next = avec->data[index];
}


int addrvec_eq(const addrvec_t *a1, const addrvec_t *a2)
{
    uint32_t i = 0;
    if (a1->count != a2->count)
    {
        return 0;
    }

    for (i = 0; i < a1->count; ++i)
    {
        if (!addrvec_contains(a2, &a1->data[i]))
            return 0;
    }

    return 1;
}
