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

#include "zk_hashtable.h"
#include "zk_adaptor.h"
#include "hashtable/hashtable.h"
#include "hashtable/hashtable_itr.h"
#include <string.h>
#include <stdlib.h>
#include <assert.h>

#ifdef THREADED
#include <pthread.h>
#endif

struct _zk_hashtable {
    struct hashtable* ht;
#ifdef THREADED
    pthread_mutex_t lock;
#endif
};

hashtable_impl* getImpl(zk_hashtable* ht){
    return ht->ht;
}

typedef struct _watcher_object_list_t {
    watcher_object_t* head;
} watcher_object_list_t;

watcher_object_t* getFirstWatcher(zk_hashtable* ht,const char* path)
{
    watcher_object_list_t* wl=hashtable_search(ht->ht,(void*)path);
    if(wl!=0)
        return wl->head;
    return 0;
}

watcher_object_t* clone_watcher_object(watcher_object_t* wo)
{
    watcher_object_t* res=calloc(1,sizeof(watcher_object_t));
    res->watcher=wo->watcher;
    res->context=wo->context;
    return res;
}

static unsigned int string_hash_djb2(void *str) 
{
    unsigned int hash = 5381;
    int c;

    while ((c = *(const char*)str++))
        hash = ((hash << 5) + hash) + c; /* hash * 33 + c */

    return hash;
}

static int string_equal(void *key1,void *key2)
{
    return strcmp((const char*)key1,(const char*)key2)==0;
}

watcher_object_t* create_watcher_object(watcher_fn watcher,void* ctx)
{
    watcher_object_t* wo=calloc(1,sizeof(watcher_object_t));
    wo->watcher=watcher;
    wo->context=ctx;
    return wo;
}

static watcher_object_list_t* create_watcher_object_list(watcher_object_t* head) 
{
    watcher_object_list_t* wl=calloc(1,sizeof(watcher_object_list_t));
    wl->head=head;
    return wl;
}

static void destroy_watcher_object_list(watcher_object_list_t* list)
{
    if(list==0)
        return;
    watcher_object_t* e=list->head;
    while(e!=0){
        watcher_object_t* this=e;
        e=e->next;
        free(this);
    }
    free(list);
}

zk_hashtable* create_zk_hashtable()
{
    struct _zk_hashtable *ht=calloc(1,sizeof(struct _zk_hashtable));
#ifdef THREADED
    pthread_mutex_init(&ht->lock, 0);
#endif
    ht->ht=create_hashtable(32,string_hash_djb2,string_equal);
    return ht;
}

int get_element_count(zk_hashtable *ht)
{
    int res;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    res=hashtable_count(ht->ht);    
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    return res;
}

int get_watcher_count(zk_hashtable* ht,const char* path)
{
    int res=0;
    watcher_object_list_t* wl;
    watcher_object_t* wo;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    wl=hashtable_search(ht->ht,(void*)path);
    if(wl==0)
        goto done;
    wo=wl->head;
    while(wo!=0){
        res++;
        wo=wo->next;
    }
done:
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    return res;    
}

static void do_clean_hashtable(zk_hashtable* ht)
{
    struct hashtable_itr *it;
    int hasMore;
    if(hashtable_count(ht->ht)==0)
        return;
    it=hashtable_iterator(ht->ht);
    do{
        watcher_object_list_t* w=hashtable_iterator_value(it);
        destroy_watcher_object_list(w);
        hasMore=hashtable_iterator_remove(it);
    }while(hasMore);
    free(it);
}

void clean_zk_hashtable(zk_hashtable* ht)
{
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    do_clean_hashtable(ht);    
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif    
}

void destroy_zk_hashtable(zk_hashtable* ht)
{
    if(ht!=0){
        do_clean_hashtable(ht);
        hashtable_destroy(ht->ht,0);
#ifdef THREADED
        pthread_mutex_destroy(&ht->lock);
#endif
        free(ht);
    }
}

// searches for a watcher object instance in a watcher object list;
// two watcher objects are equal if their watcher function and context pointers
// are equal
static watcher_object_t* search_watcher(watcher_object_list_t* wl,watcher_object_t* wo)
{
    watcher_object_t* wobj=wl->head;
    while(wobj!=0){
        if(wobj->watcher==wo->watcher && wobj->context==wo->context)
            return wobj;
        wobj=wobj->next;
    }
    return 0;
}

static int do_insert_watcher_object(zk_hashtable *ht, const char *path, watcher_object_t* wo)
{
    int res=1;
    watcher_object_list_t* wl;
    wl=hashtable_search(ht->ht,(void*)path);
    if(wl==0){
        int res;
        /* inserting a new path element */
        res=hashtable_insert(ht->ht,strdup(path),create_watcher_object_list(wo));
        assert(res);
    }else{
        /* path already exists; check if the watcher already exists */
        if(search_watcher(wl,wo)==0){
            wo->next=wl->head;
            wl->head=wo; // insert the new watcher at the head
        }else
            res=0; // the watcher already exists -- do not insert!
    }
    return res;    
}

int insert_watcher_object(zk_hashtable *ht, const char *path, watcher_object_t* wo)
{
    int res;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    res=do_insert_watcher_object(ht,path,wo);
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    return res;
}

static void copy_watchers(zk_hashtable* dst,const char* path,watcher_object_list_t* wl)
{
    if(wl==0)
        return;
    watcher_object_t* wo=wl->head;
    while(wo!=0){
        int res;
        watcher_object_t* cloned=clone_watcher_object(wo);
        res=do_insert_watcher_object(dst,path,cloned);
        // was it a duplicate?
        if(res==0)
            free(cloned); // yes, didn't get inserted
        wo=wo->next;
    }
}

static void copy_table(zk_hashtable* dst,zk_hashtable* src)
{
    struct hashtable_itr *it;
    int hasMore;
    if(hashtable_count(src->ht)==0)
        return;
    it=hashtable_iterator(src->ht);
    do{
        copy_watchers(dst,hashtable_iterator_key(it),hashtable_iterator_value(it));
        hasMore=hashtable_iterator_advance(it);
    }while(hasMore);
    free(it);
}

zk_hashtable* combine_hashtables(zk_hashtable *ht1,zk_hashtable *ht2)
{
    zk_hashtable* newht=create_zk_hashtable();
#ifdef THREADED
    pthread_mutex_lock(&ht1->lock);
    pthread_mutex_lock(&ht2->lock);
#endif
    copy_table(newht,ht1);
    copy_table(newht,ht2);
#ifdef THREADED
    pthread_mutex_unlock(&ht2->lock);
    pthread_mutex_unlock(&ht1->lock);
#endif    
    return newht;
}

zk_hashtable* move_merge_watchers(zk_hashtable *ht1,zk_hashtable *ht2,const char *path)
{
    watcher_object_list_t* wl;
    zk_hashtable* newht=create_zk_hashtable();
#ifdef THREADED
    pthread_mutex_lock(&ht1->lock);
    pthread_mutex_lock(&ht2->lock);
#endif
    // copy watchers from table 1
    wl=hashtable_remove(ht1->ht,(void*)path);
    copy_watchers(newht,path,wl);
    destroy_watcher_object_list(wl);
    // merge all watchers from tabe 2
    wl=hashtable_remove(ht2->ht,(void*)path);
    copy_watchers(newht,path,wl);
    destroy_watcher_object_list(wl);
    
#ifdef THREADED
    pthread_mutex_unlock(&ht2->lock);
    pthread_mutex_unlock(&ht1->lock);
#endif    
    return newht;
}

int contains_watcher(zk_hashtable *ht,watcher_object_t* wo)
{
    struct hashtable_itr *it=0;
    int res=0;
    int hasMore;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    if(hashtable_count(ht->ht)==0)
        goto done;
    it=hashtable_iterator(ht->ht);
    do{
        watcher_object_list_t* w=hashtable_iterator_value(it);
        if(search_watcher(w,wo)!=0){
            res=1;
            goto done;
        }
        hasMore=hashtable_iterator_advance(it);
    }while(hasMore);
done:
    if(it!=0)
        free(it);
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    return res;
}

static void do_foreach_watcher(watcher_object_t* wo,zhandle_t* zh,
        const char* path,int type,int state)
{
    while(wo!=0){
        wo->watcher(zh,type,state,path,wo->context);
        wo=wo->next;
    }    
}

void deliver_session_event(zk_hashtable* ht,zhandle_t* zh,int type,int state)
{
    struct hashtable_itr *it;
    int hasMore;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    if(hashtable_count(ht->ht)==0)
        goto done;
    it=hashtable_iterator(ht->ht);
    do{
        watcher_object_t* wo=((watcher_object_list_t*)hashtable_iterator_value(it))->head;
        // session events are delivered with the path set to null
        do_foreach_watcher(wo,zh,0,type,state);
        hasMore=hashtable_iterator_advance(it);
    }while(hasMore);
    free(it);
done:
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    return;
}

void deliver_znode_event(zk_hashtable* ht,zhandle_t* zh,const char* path,int type,int state)
{
    watcher_object_list_t* wl;
#ifdef THREADED
    pthread_mutex_lock(&ht->lock);
#endif
    wl=hashtable_remove(ht->ht,(void*)path);
#ifdef THREADED
    pthread_mutex_unlock(&ht->lock);
#endif
    if(wl!=0){
        do_foreach_watcher(wl->head,zh,path,type,state);
        destroy_watcher_object_list(wl);
    }
}

void deliverWatchers(zhandle_t* zh,int type,int state, const char* path)
{
    zk_hashtable *ht;
    if(type==SESSION_EVENT){
        watcher_object_t defWatcher;
        if(state==CONNECTED_STATE){
            clean_zk_hashtable(zh->active_node_watchers);
            clean_zk_hashtable(zh->active_child_watchers);
            // unconditionally call back the default watcher only
            zh->watcher(zh,type,state,0,zh->context);
            return;
        }
        // process a disconnect/expiration
        // must merge node and child watchers first
        ht=combine_hashtables(zh->active_node_watchers,
                zh->active_child_watchers);
        // check if the default watcher is already present on the combined map 
        defWatcher.watcher=zh->watcher;
        defWatcher.context=zh->context;
        if(contains_watcher(ht,&defWatcher)==0)
            insert_watcher_object(ht,"",clone_watcher_object(&defWatcher));
        // deliver watcher callback to all registered watchers
        deliver_session_event(ht,zh,type,state);
        destroy_zk_hashtable(ht);
        // in anticipation of the watcher auto-reset feature we keep 
        // the watcher maps intact. 
        // (for now, we simply clean the maps on reconnect, see above)
        return;
    }
    switch(type){
    case CREATED_EVENT_DEF:
    case CHANGED_EVENT_DEF:
        // look up the watchers for the path and deliver them
        deliver_znode_event(zh->active_node_watchers,zh,path,type,state);
        break;
    case CHILD_EVENT_DEF:
        // look up the watchers for the path and deliver them
        deliver_znode_event(zh->active_child_watchers,zh,path,type,state);
        break;
    case DELETED_EVENT_DEF:
        // combine node and child watchers for the path and deliver them
        ht=move_merge_watchers(zh->active_child_watchers,
                zh->active_node_watchers,path);
        deliver_znode_event(ht,zh,path,type,state);
        destroy_zk_hashtable(ht);
        break;
    }
}

void activateWatcher(watcher_registration_t* reg, int rc)
{
    if(reg!=0){
        /* in multithreaded lib, this code is executed 
         * by the completion thread */
        if(reg->checker(rc)){
            insert_watcher_object(reg->activeMap,reg->path,
                    create_watcher_object(reg->watcher,reg->context));
        }
    }    
}
