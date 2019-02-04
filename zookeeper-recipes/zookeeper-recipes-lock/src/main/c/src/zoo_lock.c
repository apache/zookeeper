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

#ifdef DLL_EXPORT
#define USE_STATIC_LIB
#endif

#if defined(__CYGWIN__)
#define USE_IPV6
#endif

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <zookeeper_log.h>
#include <time.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <limits.h>
#include <zoo_lock.h>
#include <stdbool.h>
#ifdef HAVE_SYS_UTSNAME_H
#include <sys/utsname.h>
#endif

#ifdef HAVE_GETPWUID_R
#include <pwd.h>
#endif

#define IF_DEBUG(x) if (logLevel==ZOO_LOG_LEVEL_DEBUG) {x;}

 
ZOOAPI int zkr_lock_init(zkr_lock_mutex_t* mutex, zhandle_t* zh,
                      char* path, struct ACL_vector *acl) {
    mutex->zh = zh;
    mutex->path = path;
    mutex->acl = acl;
    mutex->completion = NULL;
    mutex->cbdata = NULL;
    mutex->id = NULL;
    mutex->ownerid = NULL;
    mutex->isOwner = 0;
    pthread_mutex_init(&(mutex->pmutex), NULL);
    return 0;
}

ZOOAPI int zkr_lock_init_cb(zkr_lock_mutex_t *mutex, zhandle_t* zh,
                         char *path, struct ACL_vector *acl,
                         zkr_lock_completion completion, void* cbdata) {
    mutex->zh = zh;
    mutex->path = path;
    mutex->acl = acl;
    mutex->completion = completion;
    mutex->cbdata = cbdata;
    mutex->isOwner = 0;
    mutex->ownerid = NULL;
    mutex->id = NULL;
    pthread_mutex_init(&(mutex->pmutex), NULL);
    return 0;
}

static int _zkr_lock_unlock_nolock(zkr_lock_mutex_t *mutex) {
    zhandle_t *zh = mutex->zh;
    if (mutex->id != NULL) {
        int len = strlen(mutex->path) + strlen(mutex->id) + 2;
        char buf[len];
        sprintf(buf, "%s/%s", mutex->path, mutex->id);
        int ret = 0;
        int count = 0;
        struct timespec ts;
        ts.tv_sec = 0;
        ts.tv_nsec = (.5)*1000000;
        ret = ZCONNECTIONLOSS;
        while (ret == ZCONNECTIONLOSS && (count < 3)) {
            ret = zoo_delete(zh, buf, -1);
            if (ret == ZCONNECTIONLOSS) {
                LOG_DEBUG(LOGCALLBACK(zh), ("connectionloss while deleting the node"));
                nanosleep(&ts, 0);
                count++;
            }
        }
        if (ret == ZOK || ret == ZNONODE) {
            zkr_lock_completion completion = mutex->completion;
            if (completion != NULL) {
                completion(1, mutex->cbdata);
            }

            free(mutex->id);
            mutex->id = NULL;
            return 0;
        }
        LOG_WARN(LOGCALLBACK(zh), ("not able to connect to server - giving up"));
        return ZCONNECTIONLOSS;
    }

	return ZSYSTEMERROR;
}
/**
 * unlock the mutex
 */
ZOOAPI int zkr_lock_unlock(zkr_lock_mutex_t *mutex) {
	int ret = 0;
    pthread_mutex_lock(&(mutex->pmutex));
    ret = _zkr_lock_unlock_nolock(mutex);
    pthread_mutex_unlock(&(mutex->pmutex));
    return ret;
}

static void free_String_vector(struct String_vector *v) {
    if (v->data) {
        int32_t i;
        for (i=0; i<v->count; i++) {
            free(v->data[i]);
        }
        free(v->data);
        v->data = 0;
    }
}

static int strcmp_suffix(const char *str1, const char *str2) {
    return strcmp(strrchr(str1, '-')+1, strrchr(str2, '-')+1);
}

static int vstrcmp(const void* str1, const void* str2) {
    const char **a = (const char**)str1;
    const char **b = (const char**) str2;
    return strcmp_suffix(*a, *b);
} 

static void sort_children(struct String_vector *vector) {
    qsort( vector->data, vector->count, sizeof(char*), &vstrcmp);
}
        
static char* child_floor(char **sorted_data, int len, char *element) {
    char* ret = NULL;
    int targetpos = -1, s = 0, e = len -1;

    while ( targetpos < 0 && s <= e ) {
		int const i = s + (e - s) / 2;
		int const cmp = strcmp_suffix(sorted_data[i], element);
		if (cmp < 0) {
			s = i + 1;
		} else if (cmp == 0) {
			targetpos = i;
		} else {
			e = i - 1;
		}
	}

	if (targetpos > 0) {
		ret = sorted_data[targetpos - 1];
	}

    return ret;
}

static void lock_watcher_fn(zhandle_t* zh, int type, int state,
                            const char* path, void *watcherCtx) {
    //callback that we registered 
    //should be called
    zkr_lock_lock((zkr_lock_mutex_t*) watcherCtx);
}

/**
 * get the last name of the path
 */
static char* getName(char* str) {
    char* name = strrchr(str, '/');
    if (name == NULL) 
        return NULL;
    return strdup(name + 1);
}

/**
 * just a method to retry get children
 */
static int retry_getchildren(zhandle_t *zh, char* path, struct String_vector *vector, 
                             struct timespec *ts, int retry) {
    int ret = ZCONNECTIONLOSS;
    int count = 0;
    while (ret == ZCONNECTIONLOSS && count < retry) {
        ret = zoo_get_children(zh, path, 0, vector);
        if (ret == ZCONNECTIONLOSS) {
            LOG_DEBUG(LOGCALLBACK(zh), ("connection loss to the server"));
            nanosleep(ts, 0);
            count++;
        }
    }
    return ret;
}

/** see if our node already exists
 * if it does then we dup the name and 
 * return it
 */
static char* lookupnode(struct String_vector *vector, char *prefix) {
    char *ret = NULL;
    if (vector->data) {
        int i = 0;
        for (i = 0; i < vector->count; i++) {
            char* child = vector->data[i];
            if (strncmp(prefix, child, strlen(prefix)) == 0) {
                ret = strdup(child);
                break;
            }
        }
    }
    return ret;
}

/** retry zoo_wexists
 */
static int retry_zoowexists(zhandle_t *zh, char* path, watcher_fn watcher, void* ctx,
                            struct Stat *stat, struct timespec *ts, int retry) {
    int ret = ZCONNECTIONLOSS;
    int count = 0;
    while (ret == ZCONNECTIONLOSS && count < retry) {
        ret = zoo_wexists(zh, path, watcher, ctx, stat);
        if (ret == ZCONNECTIONLOSS) {
            LOG_DEBUG(LOGCALLBACK(zh), ("connectionloss while setting watch on my predecessor"));
            nanosleep(ts, 0);
            count++;
        }
    }
    return ret;
}
                        
/**
 * the main code that does the zookeeper leader 
 * election. this code creates its own ephemeral 
 * node on the given path and sees if its the first
 * one on the list and claims to be a leader if and only
 * if its the first one of children in the paretn path
 */
static int zkr_lock_operation(zkr_lock_mutex_t *mutex, struct timespec *ts) {
    zhandle_t *zh = mutex->zh;
    char *path = mutex->path;
    char *id = mutex->id;
    struct Stat stat;
    char* owner_id = NULL;
    int retry = 3;
    do {
        const clientid_t *cid = zoo_client_id(zh);
        // get the session id
        int64_t session = cid->client_id;
        char prefix[30];
        int ret = 0;
#if defined(__x86_64__)
        snprintf(prefix, 30, "x-%016lx-", session);
#else 
        snprintf(prefix, 30, "x-%016llx-", session);
#endif
        struct String_vector vectorst;
        vectorst.data = NULL;
        vectorst.count = 0;
        ret = ZCONNECTIONLOSS;
        ret = retry_getchildren(zh, path, &vectorst, ts, retry);
        if (ret != ZOK)
            return ret;
        struct String_vector *vector = &vectorst;
        mutex->id = lookupnode(vector, prefix);
        free_String_vector(vector);
        if (mutex->id == NULL) {
            int len = strlen(path) + strlen(prefix) + 2;
            char buf[len];
            char retbuf[len+20];
            snprintf(buf, len, "%s/%s", path, prefix);
            ret = ZCONNECTIONLOSS;
            ret = zoo_create(zh, buf, NULL, 0,  mutex->acl, 
                             ZOO_EPHEMERAL|ZOO_SEQUENCE, retbuf, (len+20));
            
            // do not want to retry the create since 
            // we would end up creating more than one child
            if (ret != ZOK) {
                LOG_WARN(LOGCALLBACK(zh), "could not create zoo node %s", buf);
                return ret;
            }
            mutex->id = getName(retbuf);
        }
        
        if (mutex->id != NULL) {
            ret = ZCONNECTIONLOSS;
            ret = retry_getchildren(zh, path, vector, ts, retry);
            if (ret != ZOK) {
                LOG_WARN(LOGCALLBACK(zh), ("could not connect to server"));
                return ret;
            }
            //sort this list
            sort_children(vector);
            owner_id = vector->data[0];
            mutex->ownerid = strdup(owner_id);
            id = mutex->id;
            char* lessthanme = child_floor(vector->data, vector->count, id);
            if (lessthanme != NULL) {
                int flen = strlen(mutex->path) + strlen(lessthanme) + 2;
                char last_child[flen];
                sprintf(last_child, "%s/%s",mutex->path, lessthanme);
                ret = ZCONNECTIONLOSS;
                ret = retry_zoowexists(zh, last_child, &lock_watcher_fn, mutex, 
                                       &stat, ts, retry);
                // cannot watch my predecessor i am giving up
                // we need to be able to watch the predecessor 
                // since if we do not become a leader the others 
                // will keep waiting
                if (ret != ZOK) {
                    free_String_vector(vector);
                    LOG_WARN(LOGCALLBACK(zh), ("unable to watch my predecessor"));
                    ret = _zkr_lock_unlock_nolock(mutex);
                    while (ret == 0) {
                        //we have to give up our leadership
                        // since we cannot watch out predecessor
                        ret = _zkr_lock_unlock_nolock(mutex);
                    }
                    return ret;
                }
                // we are not the owner of the lock
                mutex->isOwner = 0;
            }
            else {
                // this is the case when we are the owner 
                // of the lock
                if (strcmp(mutex->id, owner_id) == 0) {
                    LOG_DEBUG(LOGCALLBACK(zh), "got the zoo lock owner - %s", mutex->id);
                    mutex->isOwner = 1;
                    if (mutex->completion != NULL) {
                        mutex->completion(0, mutex->cbdata);
                    }
                    return ZOK;
                }
            }
            free_String_vector(vector);
            return ZOK;
        }
    } while (mutex->id == NULL);
    return ZOK;
}

ZOOAPI int zkr_lock_lock(zkr_lock_mutex_t *mutex) {
    pthread_mutex_lock(&(mutex->pmutex));
    zhandle_t *zh = mutex->zh;
    char *path = mutex->path;
    struct Stat stat;
    int exists = zoo_exists(zh, path, 0, &stat);
    int count = 0;
    struct timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = (.5)*1000000;
    // retry to see if the path exists and 
    // and create if the path does not exist
    while ((exists == ZCONNECTIONLOSS || exists == ZNONODE) && (count <4)) {
        count++;
        // retry the operation
        if (exists == ZCONNECTIONLOSS) 
            exists = zoo_exists(zh, path, 0, &stat);
        else if (exists == ZNONODE) 
            exists = zoo_create(zh, path, NULL, 0, mutex->acl, 0, NULL, 0);
        nanosleep(&ts, 0);
          
    }

    // need to check if we cannot still access the server 
    int check_retry = ZCONNECTIONLOSS;
    count = 0;
    while (check_retry != ZOK && count <4) {
        check_retry = zkr_lock_operation(mutex, &ts);
        if (check_retry != ZOK) {
            nanosleep(&ts, 0);
            count++;
        }
    }
    pthread_mutex_unlock(&(mutex->pmutex));
    return 0;
}

                    
ZOOAPI char* zkr_lock_getpath(zkr_lock_mutex_t *mutex) {
    return mutex->path;
}

ZOOAPI int zkr_lock_isowner(zkr_lock_mutex_t *mutex) {
    return (mutex->id != NULL && mutex->ownerid != NULL 
            && (strcmp(mutex->id, mutex->ownerid) == 0));
}

ZOOAPI char* zkr_lock_getid(zkr_lock_mutex_t *mutex) {
    return mutex->ownerid;
}

ZOOAPI int zkr_lock_destroy(zkr_lock_mutex_t* mutex) {
    if (mutex->id) 
        free(mutex->id);
    mutex->path = NULL;
    mutex->acl = NULL;
    mutex->completion = NULL;
    pthread_mutex_destroy(&(mutex->pmutex));
    mutex->isOwner = 0;
    if (mutex->ownerid) 
        free(mutex->ownerid);
    return 0;
}

