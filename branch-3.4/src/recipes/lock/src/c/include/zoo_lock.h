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
#ifndef ZOOKEEPER_LOCK_H_
#define ZOOKEEPER_LOCK_H_

#include <zookeeper.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif
/**
 * \brief the call back function called on status change of lock
 * 
 * the call back funtion is called with a rc of 0 if lock is acquired and 
 * with an rc of 1 if the lock is released
 * \param rc the value to let us know if its locked or unlocked
 * \param cbdata the callback data that we passed when initializing 
 * the zookeeper lock.
 */

typedef void (* zkr_lock_completion) (int rc, void* cbdata);

/** 
 * \file zoo_lock.h
 * \brief zookeeper recipe for locking and leader election.
 * this api implements a writelock on a given path in zookeeper.
 * this api can also be used for leader election.
 */

struct zkr_lock_mutex {
    zhandle_t *zh;
    char *path;
    struct ACL_vector *acl;
    char *id;
    void *cbdata;
    zkr_lock_completion completion;
    pthread_mutex_t pmutex;
    int isOwner;
    char* ownerid;
};

typedef struct zkr_lock_mutex zkr_lock_mutex_t;


/**
 * \brief initializing a zookeeper lock.
 *
 * this method instantiates the zookeeper mutex lock.
 * \param mutex the mutex to initialize
 * \param zh the zookeeper handle to use
 * \param path the path in zookeeper to use for locking
 * \param acl the acls to use in zookeeper.
 * \return return 0 if successful.
 */
ZOOAPI int zkr_lock_init(zkr_lock_mutex_t *mutex, zhandle_t* zh, 
                      char* path, struct ACL_vector *acl);

/**
 * \brief initializing a zookeeper lock.
 *
 *
 * this method instantiates the zookeeper mutex lock with
 * a completion function.
 * 
 * \param mutex the mutex to initialize
 * \param zh the zookeeper handle to use
 * \param path the path in zookeeper to use for locking
 * \param acl the acls to use in zookeeper.
 * \param completion the callback thats called when lock 
 * is acquired and released.
 * \param cbdata the callback method is called with data
 * \return return 0 if successful.
 */
ZOOAPI int zkr_lock_init_cb(zkr_lock_mutex_t *mutex, zhandle_t* zh,
                      char* path, struct ACL_vector *acl, 
                      zkr_lock_completion completion, void* cbdata);

/**
 * \brief lock the zookeeper mutex
 *
 * this method tries locking the mutex
 * \param mutex the zookeeper mutex
 * \return return 0 if there is no error. check 
 * with zkr_lock_isowner() if you have the lock
 */
ZOOAPI int zkr_lock_lock(zkr_lock_mutex_t *mutex);

/**
 * \brief unlock the zookeeper mutex
 *
 * this method unlocks the zookeeper mutex
 * \param mutex the zookeeper mutex
 * \return return 0 if there is not error in executing unlock.
 * else returns non zero
 */
ZOOAPI int zkr_lock_unlock(zkr_lock_mutex_t *mutex);

/**
 * \brief set the callback function for zookeeper mutex
 * 
 * this method sets the callback for zookeeper mutex
 * \param mutex the zookeeper mutex
 * \param callback the call back completion function
 */
ZOOAPI void zkr_lock_setcallback(zkr_lock_mutex_t *mutex, 
                           zkr_lock_completion completion);

/**
 * \brief get the callback function for zookeeper mutex
 *
 * this method gets the callback funtion for zookeeper mutex
 * \param mutex the zookeeper mutex
 * \return the lock completion function
 */
ZOOAPI zkr_lock_completion zkr_lock_getcallback(zkr_lock_mutex_t *mutex);

/**
 * \brief destroy the mutex 
 * this method free the mutex
 * \param mutex destroy the zookepeer lock.
 * \return return 0 if destroyed.
 */
ZOOAPI int zkr_lock_destroy(zkr_lock_mutex_t* mutex);

/**
 * \brief return the parent path this mutex is using
 * this method returns the parent path
 * \param mutex the mutex
 * \return return the parent path
 */
ZOOAPI char* zkr_lock_getpath(zkr_lock_mutex_t *mutex);

/**
 * \brief return if this mutex is owner of the lock
 * this method returns if its owner or not
 * \param mutex the mutex
 * \return return true if is owner and false if not
 */
ZOOAPI int zkr_lock_isowner(zkr_lock_mutex_t *mutex);

/**
 * \brief return the id for this mutex
 * this mutex retunrns the id string 
 * \param mutex the mutex
 * \return the id for this mutex
 */
ZOOAPI char* zkr_lock_getid(zkr_lock_mutex_t *mutex);

#ifdef __cplusplus
}
#endif
#endif  //ZOOKEEPER_LOCK_H_
