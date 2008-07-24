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

#ifndef ZK_HASHTABLE_H_
#define ZK_HASHTABLE_H_

#include <zookeeper.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _zk_hashtable zk_hashtable;

/**
 * The function must return a non-zero value if the watcher object can be activated
 * as a result of the server response. Normally, a watch can only be activated
 * if the server returns a success code (ZOK). However in the case when zoo_exists() 
 * returns a ZNONODE code the watcher should be activated nevertheless.
 */
typedef int (*result_checker_fn)(int rc);

/**
 * A watcher object gets temporarily stored with the completion entry until 
 * the server response comes back at which moment the watcher object is moved
 * to the active watchers map.
 */
typedef struct _watcher_registration {
    watcher_fn watcher;
    result_checker_fn checker;
    void* context;
    zk_hashtable* activeMap; // the map to add the watcher to upon activation
    const char* path;
} watcher_registration_t;


typedef struct _watcher_object {
    watcher_fn watcher;
    void* context;
    struct _watcher_object* next;
} watcher_object_t;

watcher_object_t* create_watcher_object(watcher_fn watcher,void* ctx);
watcher_object_t* clone_watcher_object(watcher_object_t* wo);

zk_hashtable* create_zk_hashtable();
void clean_zk_hashtable(zk_hashtable* ht);
void destroy_zk_hashtable(zk_hashtable* ht);
zk_hashtable* combine_hashtables(zk_hashtable* ht1,zk_hashtable* ht2);
/**
 * \brief first, merges all watchers for path from ht1 and ht2 to a new hashtable and 
 * then removes the path entries from ht1 and ht2 
 */
zk_hashtable* move_merge_watchers(zk_hashtable* ht1,zk_hashtable* ht2,const char* path);

/**
 * The hashtable takes ownership of the watcher object instance.
 * 
 * \return 1 if the watcher object was succesfully inserted, 0 otherwise
 */
int insert_watcher_object(zk_hashtable* ht, const char* path, watcher_object_t* wo);
/**
 * \brief searches the entire hashtable for the watcher object
 * 
 * \return 1 if the watcher object found in the table, 0 otherwise
 */
int contains_watcher(zk_hashtable* ht,watcher_object_t* wo);
int get_element_count(zk_hashtable* ht);
int get_watcher_count(zk_hashtable* ht,const char* path);
/**
 * \brief delivers all watchers in the hashtable
 */
void deliver_session_event(zk_hashtable* ht,zhandle_t* zh,int type,int state);
/**
 * \brief delivers all watchers for path and then removes the path entry 
 * from the hashtable
 */
void deliver_znode_event(zk_hashtable* ht,zhandle_t* zh,const char* path,int type,int state);

/**
 * zookeeper uses this function to deliver watcher callbacks
 */
void deliverWatchers(zhandle_t* zh,int type,int state, const char* path);
/**
 * check if the completion has a watcher object associated
 * with it. If it does, move the watcher object to the map of
 * active watchers (only if the checker allows to do so)
 */
void activateWatcher(watcher_registration_t* reg, int rc);

/* the following functions are for testing only */
typedef struct hashtable hashtable_impl;
hashtable_impl* getImpl(zk_hashtable* ht);
watcher_object_t* getFirstWatcher(zk_hashtable* ht,const char* path);

#ifdef __cplusplus
}
#endif

#endif /*ZK_HASHTABLE_H_*/
