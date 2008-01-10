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

#include <zookeeper.h>
#include "Util.h"
#include "LibCSymTable.h"

#ifdef THREADED
#include <pthread.h>

struct Mutex::Impl{
    Impl(){
        LIBC_SYMBOLS.pthread_mutex_init(&mut_, 0);        
    }
    ~Impl(){
        LIBC_SYMBOLS.pthread_mutex_destroy(&mut_);        
    }
    pthread_mutex_t mut_;
};

Mutex::Mutex():impl_(new Impl) {}
Mutex::~Mutex() { delete impl_;}
void Mutex::acquire() {
    LIBC_SYMBOLS.pthread_mutex_lock(&impl_->mut_);
}
void Mutex::release() {
    LIBC_SYMBOLS.pthread_mutex_unlock(&impl_->mut_);
}
#endif

void millisleep(int ms){
    timespec ts;
    ts.tv_sec=ms/1000;
    ts.tv_nsec=(ms%1000)*1000000; // to nanoseconds
    nanosleep(&ts,0);
}

void activeWatcher(zhandle_t *zh, int type, int state, const char *path){
    if(zh==0 || zoo_get_context(zh)==0) return;
    WatcherAction* action=(WatcherAction*)zoo_get_context(zh);
        
    if(type==SESSION_EVENT && state==EXPIRED_SESSION_STATE)
        action->onSessionExpired(zh);
    // TODO: implement for the rest of the event types
}
