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

#ifndef ZOOKEEPER_QUEUE_H_
#define ZOOKEEPER_QUEUE_H_

#include <zookeeper.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif


/** 
 * \file zoo_queue.h
 * \brief zookeeper recipe for queues.
 */


struct zkr_queue {
    zhandle_t *zh;
    char *path;
    struct ACL_vector *acl;
    pthread_mutex_t pmutex;
    char *node_name;
    int node_name_length;
    char *cached_create_path;
};

typedef struct zkr_queue zkr_queue_t;


/**
 * \brief initializes a zookeeper queue
 *
 * this method instantiates a zookeeper queue
 * \param queue the zookeeper queue to initialize
 * \param zh the zookeeper handle to use
 * \param path the path in zookeeper to use for the queue 
 * \param acl the acl to use in zookeeper.
 * \return return 0 if successful.  
 */
ZOOAPI int zkr_queue_init(zkr_queue_t *queue, zhandle_t* zh, char* path, struct ACL_vector *acl);

/**
 * \brief adds an element to a zookeeper queue
 *
 * this method adds an element to the back of a zookeeper queue.
 * \param queue the zookeeper queue to add the element to
 * \param data a pointer to a data buffer
 * \param buffer_len the length of the buffer
 * \return returns 0 (ZOK) if successful, otherwise returns a zookeeper error code.
 */
ZOOAPI int zkr_queue_offer(zkr_queue_t *queue, const char *data, int buffer_len);

/**
 * \brief returns the head of a zookeeper queue 
 *
 * this method returns the head of a zookeeper queue without removing it.
 * \param queue the zookeeper queue to add the element to
 * \param buffer a pointer to a data buffer
 * \param buffer_len a pointer to the length of the buffer
 * \return returns 0 (ZOK) and sets *buffer_len to the length of data written if successful (-1 if the queue is empty). Otherwise it will set *buffer_len to -1 and return a zookeeper error code. 
 */
ZOOAPI int zkr_queue_element(zkr_queue_t *queue, char *buffer, int *buffer_len);

/**
 * \brief returns the head of a zookeeper queue 
 *
 * this method returns the head of a zookeeper queue without removing it.
 * \param queue the zookeeper queue to get the head of
 * \param buffer a pointer to a data buffer
 * \param buffer_len a pointer to the length of the buffer
 * \return returns 0 (ZOK) and sets *buffer_len to the length of data written if successful (-1 if the queue is empty). Otherwise it will set *buffer_len to -1 and return a zookeeper error code. 
 */
ZOOAPI int zkr_queue_remove(zkr_queue_t *queue, char *buffer, int *buffer_len);

/**
 * \brief removes and returns the head of a zookeeper queue, blocks if necessary 
 *
 * this method returns the head of a zookeeper queue without removing it.
 * \param queue the zookeeper queue to remove and return the head of 
 * \param buffer a pointer to a data buffer
 * \param buffer_len a pointer to the length of the buffer
 * \return returns 0 (ZOK) and sets *buffer_len to the length of data written if successful. Otherwise it will set *buffer_len to -1 and return a zookeeper error code. 
 */
ZOOAPI int zkr_queue_take(zhandle_t *zh, zkr_queue_t *queue, char *buffer, int *buffer_len);

/**
 * \brief destroys a zookeeper queue structure 
 *
 * this destroys a zookeeper queue structure, this is only a local operation and will not affect
 * the state of the queue on the zookeeper server.
 * \param queue the zookeeper queue to destroy
 */
void zkr_queue_destroy(zkr_queue_t *queue);


#ifdef __cplusplus
}
#endif
#endif  //ZOOKEEPER_QUEUE_H_
