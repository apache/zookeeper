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

#include <zookeeper.h>
#include "zookeeper_log.h"
#include <errno.h>
#ifndef WIN32
#ifdef THREADED 
#include <pthread.h>
#endif
#else
#include "win32port.h"
#endif
#include <string.h>
#include <stdlib.h>

static zhandle_t *zh;

static int shutdownThisThing=0;

// *****************************************************************************
//
static pthread_cond_t cond=PTHREAD_COND_INITIALIZER;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;

static pthread_cond_t counterCond=PTHREAD_COND_INITIALIZER;
static pthread_mutex_t counterLock=PTHREAD_MUTEX_INITIALIZER;
static int counter; 



void ensureConnected(){
    pthread_mutex_lock(&lock);
    while (zoo_state(zh)!=ZOO_CONNECTED_STATE) {
        pthread_cond_wait(&cond,&lock);
    }
    pthread_mutex_unlock(&lock);
}

void incCounter(int delta){
    pthread_mutex_lock(&counterLock);
    counter+=delta;
    pthread_cond_broadcast(&counterCond);
    pthread_mutex_unlock(&counterLock);        
    
}
void setCounter(int cnt){
    pthread_mutex_lock(&counterLock);
    counter=cnt;
    pthread_cond_broadcast(&counterCond);
    pthread_mutex_unlock(&counterLock);        
    
}
void waitCounter(){
    pthread_mutex_lock(&counterLock);
    while (counter>0) {
        pthread_cond_wait(&counterCond,&counterLock);
    }
    pthread_mutex_unlock(&counterLock);    
}

void listener(zhandle_t *zzh, int type, int state, const char *path,void* ctx) {
    if(type == ZOO_SESSION_EVENT){
        if(state == ZOO_CONNECTED_STATE){
            pthread_mutex_lock(&lock);
            pthread_cond_broadcast(&cond);
            pthread_mutex_unlock(&lock);
        }
        setCounter(0);
    }
}

void create_completion(int rc, const char *name, const void *data) {
    incCounter(-1);
    if(rc!=ZOK){
        LOG_ERROR(("Failed to create a node rc=%d",rc));
    }
}

int doCreateNodes(const char* root, int count){
    char nodeName[1024];
    int i;
    for(i=0; i<count;i++){
        int rc = 0;
        snprintf(nodeName, sizeof(nodeName),"%s/%d",root,i);
        incCounter(1);
        rc=zoo_acreate(zh, nodeName, "first", 5, &ZOO_OPEN_ACL_UNSAFE, 0,
                            create_completion, 0);
        if(i%1000==0){
            LOG_INFO(("Created %s",nodeName));
        }
        if(rc!=ZOK) return rc;        
    }
    return ZOK;
}

int createRoot(const char* root){
    char realpath[1024];
    return zoo_create(zh,root,"root",4,&ZOO_OPEN_ACL_UNSAFE,0,realpath,sizeof(realpath)-1);
}

void write_completion(int rc, const struct Stat *stat, const void *data) {
    incCounter(-1);
    if(rc!=ZOK){
        LOG_ERROR(("Failed to write a node rc=%d",rc));
    }
}

int doWrites(const char* root, int count){
    char nodeName[1024];
    int i;
    counter=0;
    for(i=0; i<count;i++){
        int rc = 0;
        snprintf(nodeName, sizeof(nodeName),"%s/%d",root,i);
        incCounter(1);
        rc=zoo_aset(zh, nodeName, "second", 6,-1,write_completion, 0);
        if(rc!=ZOK) return rc;        
    }
    return ZOK;
}

void read_completion(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data) {
    incCounter(-1);    
    if(rc!=ZOK){
        LOG_ERROR(("Failed to read a node rc=%d",rc));
        return;
    }
    if(memcmp(value,"second",6)!=0){
        char buf[value_len+1];
        memcpy(buf,value,value_len);buf[value_len]=0;
        LOG_ERROR(("Invalid read, expected [second], received [%s]\n",buf));
        exit(1);
    }
}

int doReads(const char* root, int count){
    char nodeName[1024];
    int i;
    counter=0;
    for(i=0; i<count;i++){
        int rc = 0;
        snprintf(nodeName, sizeof(nodeName),"%s/%d",root,i);
        incCounter(1);
        rc=zoo_aget(zh, nodeName,0,read_completion, 0);
        if(rc!=ZOK) return rc;        
    }
    return ZOK;
}

void delete_completion(int rc, const void *data) {
    incCounter(-1);    
}

int doDeletes(const char* root, int count){
    char nodeName[1024];
    int i;
    counter=0;
    for(i=0; i<count;i++){
        int rc = 0;
        snprintf(nodeName, sizeof(nodeName),"%s/%d",root,i);
        incCounter(1);
        rc=zoo_adelete(zh, nodeName,-1,delete_completion, 0);
        if(rc!=ZOK) return rc;        
    }
    return ZOK;
}

static int free_String_vector(struct String_vector *v) {
    if (v->data) {
        int32_t i;
        for(i=0;i<v->count; i++) {
            free(v->data[i]);
        }
        free(v->data);
        v->data = 0;
    }
    return 0;
}

static int deletedCounter;

int recursiveDelete(const char* root){
    struct String_vector children;
    int i;
    int rc=zoo_get_children(zh,root,0,&children);
    if(rc!=ZNONODE){
        if(rc!=ZOK){
            LOG_ERROR(("Failed to get children of %s, rc=%d",root,rc));
            return rc;
        }
        for(i=0;i<children.count; i++){
            int rc = 0;
            char nodeName[2048];
            snprintf(nodeName, sizeof(nodeName),"%s/%s",root,children.data[i]);
            rc=recursiveDelete(nodeName);
            if(rc!=ZOK){
                free_String_vector(&children);
                return rc;
            }
        }
        free_String_vector(&children);
    }
    if(deletedCounter%1000==0)
        LOG_INFO(("Deleting %s",root));
    rc=zoo_delete(zh,root,-1);
    if(rc!=ZOK){
        LOG_ERROR(("Failed to delete znode %s, rc=%d",root,rc));
    }else
        deletedCounter++;
    return rc;
}

void usage(char *argv[]){
    fprintf(stderr, "USAGE:\t%s zookeeper_host_list path #children\nor", argv[0]);
    fprintf(stderr, "\t%s zookeeper_host_list path clean\n", argv[0]);
    exit(0);
}

int main(int argc, char **argv) {
    int nodeCount;
    int cleaning=0;
    if (argc < 4) {
        usage(argv);
    }
    if(strcmp("clean",argv[3])==0){
        cleaning=1;
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_INFO);
    zoo_deterministic_conn_order(1); // enable deterministic order

    zh = zookeeper_init(argv[1], listener, 10000, 0, 0, 0);
    if (!zh)
        return errno;

    LOG_INFO(("Checking server connection..."));
    ensureConnected();
    if(cleaning==1){
        int rc = 0;
        deletedCounter=0;
        rc=recursiveDelete(argv[2]);
        if(rc==ZOK){
            LOG_INFO(("Succesfully deleted a subtree starting at %s (%d nodes)",
                    argv[2],deletedCounter));
            exit(0);
        }
        exit(1);
    }
    nodeCount=atoi(argv[3]);
    createRoot(argv[2]);
    while(1) {
        ensureConnected();
        LOG_INFO(("Creating children for path %s",argv[2]));
        doCreateNodes(argv[2],nodeCount);
        waitCounter();
        
        LOG_INFO(("Starting the write cycle for path %s",argv[2]));
        doWrites(argv[2],nodeCount);
        waitCounter();
        LOG_INFO(("Starting the read cycle for path %s",argv[2]));
        doReads(argv[2],nodeCount);
        waitCounter();

        LOG_INFO(("Starting the delete cycle for path %s",argv[2]));
        doDeletes(argv[2],nodeCount);
        waitCounter();
    }
    zookeeper_close(zh);
    return 0;
}
