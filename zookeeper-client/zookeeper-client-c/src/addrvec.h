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

#ifndef ADDRVEC_H_
#define ADDRVEC_H_

#ifndef WIN32
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#else
#include <WinSock2.h>
#include <stdint.h>
#endif

/**
 * This structure represents a list of addresses. It stores the count of the
 * number of elements that have been inserted via calls to addrvec_append and
 * addrvec_append_addrinfo. It also has a capacity field for the number of 
 * addresses it has the ability to hold without needing to be enlarged.
 */
typedef struct _addrvec {
    unsigned int next;                        // next index to use
    unsigned int count;                       // number of addresses in this list
    unsigned int capacity;                    // number of address this list can hold
    struct sockaddr_storage *data;   // list of addresses
} addrvec_t;

/**
 * Initialize an addrvec by clearing out all its state.
 */
void addrvec_init(addrvec_t *avec);

/**
 * Free any memory used internally by an addrvec
 */
void addrvec_free(addrvec_t *avec);

/**
 * Allocate an addrvec with a default capacity (16)
 */
int addrvec_alloc(addrvec_t *avec);

/**
 * Allocates an addrvec with a specified capacity
 */
int addrvec_alloc_capacity(addrvec_t *avec, uint32_t capacity);

/**
 * Grow an addrvec by the specified amount. This will increase the capacity
 * of the vector and not the contents.
 */
int addrvec_grow(addrvec_t *avec, uint32_t grow_amount);

/**
 * Similar to addrvec_grow but uses a default growth amount of 16.
 */
int addrvec_grow_default(addrvec_t *avec);

/**
 * Check if an addrvec contains the specificed sockaddr_storage value.
 * \returns 1 if it contains the value and 0 otherwise.
 */
int addrvec_contains(const addrvec_t *avec, const struct sockaddr_storage *addr);

/**
 * Append the given sockaddr_storage pointer into the addrvec. The contents of
 * the given 'addr' are copied into the addrvec via memcpy.
 */
int addrvec_append(addrvec_t *avec, const struct sockaddr_storage *addr);

/**
 * Append the given addrinfo pointer into the addrvec. The contents of the given
 * 'addrinfo' are copied into the addrvec via memcpy.
 */
int addrvec_append_addrinfo(addrvec_t *avec, const struct addrinfo *addrinfo);

/**
 * Shuffle the addrvec so that it's internal list of addresses are randomized.
 * Uses random() and assumes it has been properly seeded.
 */
void addrvec_shuffle(addrvec_t *avec);

/**
 * Determine if the addrvec has a next element (e.g. it's safe to call addrvec_next)
 * 
 * \returns 1 if it has a next element and 0 otherwise
 */
int addrvec_hasnext(const addrvec_t *avec);

/**
 * Determine if the addrvec is at the end or not. Specifically, this means a
 * subsequent call to addrvec_next will loop around to the start again.
 */
int addrvec_atend(const addrvec_t *avec);

/**
 * Get the next entry from the addrvec and update the associated index.
 *
 * If next is NULL, the index will still be updated.
 * 
 * If the current index points at (or after) the last element in the vector then
 * it will loop back around and start at the beginning of the list.
 */
void addrvec_next(addrvec_t *avec, struct sockaddr_storage *next);

/**
 * Retrieves the next entry from the addrvec but doesn't update the index.
 */
void addrvec_peek(addrvec_t *avec, struct sockaddr_storage *next);

/**
 * Compare two addrvecs for equality. 
 * 
 * \returns 1 if the contents of the two lists are identical and and 0 otherwise.
 */
int addrvec_eq(const addrvec_t *a1, const addrvec_t *a2);

#endif // ADDRVEC_H



