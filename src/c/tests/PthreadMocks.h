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

#ifndef PTHREADMOCKS_H_
#define PTHREADMOCKS_H_

#include <pthread.h>
#include <string.h>
#include <errno.h>

#include "src/zk_adaptor.h"

#include "Util.h"
#include "MocksBase.h"
#include "LibCSymTable.h"
#include "ThreadingUtil.h"

// an ABC for pthreads
class MockPthreadsBase: public Mock
{
public:
    MockPthreadsBase(){mock_=this;}
    virtual ~MockPthreadsBase(){mock_=0;}
    
    virtual int pthread_create(pthread_t * t, const pthread_attr_t *a,
            void *(*f)(void *), void *d) =0;
    virtual int pthread_join(pthread_t t, void ** r) =0;
    virtual int pthread_detach(pthread_t t) =0;
    virtual int pthread_cond_broadcast(pthread_cond_t *c) =0;
    virtual int pthread_cond_destroy(pthread_cond_t *c) =0;
    virtual int pthread_cond_init(pthread_cond_t *c, const pthread_condattr_t *a) =0;
    virtual int pthread_cond_signal(pthread_cond_t *c) =0;
    virtual int pthread_cond_timedwait(pthread_cond_t *c,
            pthread_mutex_t *m, const struct timespec *t) =0;
    virtual int pthread_cond_wait(pthread_cond_t *c, pthread_mutex_t *m) =0;
    virtual int pthread_mutex_destroy(pthread_mutex_t *m) =0;
    virtual int pthread_mutex_init(pthread_mutex_t *m, const pthread_mutexattr_t *a) =0;
    virtual int pthread_mutex_lock(pthread_mutex_t *m) =0;
    virtual int pthread_mutex_trylock(pthread_mutex_t *m) =0;
    virtual int pthread_mutex_unlock(pthread_mutex_t *m) =0;
    
    static MockPthreadsBase* mock_;
};

// all pthread functions simply return an error code
// and increment their invocation counter. No actual threads are spawned.
class MockPthreadsNull: public MockPthreadsBase
{
public:
    MockPthreadsNull():
    pthread_createReturns(0),pthread_createCounter(0),
    pthread_joinReturns(0),pthread_joinCounter(0),pthread_joinResultReturn(0),
    pthread_detachReturns(0),pthread_detachCounter(0),
    pthread_cond_broadcastReturns(0),pthread_cond_broadcastCounter(0),
    pthread_cond_destroyReturns(0),pthread_cond_destroyCounter(0),
    pthread_cond_initReturns(0),pthread_cond_initCounter(0),
    pthread_cond_signalReturns(0),pthread_cond_signalCounter(0),
    pthread_cond_timedwaitReturns(0),pthread_cond_timedwaitCounter(0),
    pthread_cond_waitReturns(0),pthread_cond_waitCounter(0),
    pthread_mutex_destroyReturns(0),pthread_mutex_destroyCounter(0),
    pthread_mutex_initReturns(0),pthread_mutex_initCounter(0),
    pthread_mutex_lockReturns(0),pthread_mutex_lockCounter(0),
    pthread_mutex_trylockReturns(0),pthread_mutex_trylockCounter(0),
    pthread_mutex_unlockReturns(0),pthread_mutex_unlockCounter(0)
    {
        memset(threads,0,sizeof(threads));
    }
    
    short threads[512];
    
    int pthread_createReturns;
    int pthread_createCounter;
    virtual int pthread_create(pthread_t * t, const pthread_attr_t *a,
            void *(*f)(void *), void *d){
        char* p=(char*)&threads[pthread_createCounter++];
        p[0]='i'; // mark as created
        *t=(pthread_t)p;
        return pthread_createReturns; 
    }
    int pthread_joinReturns;
    int pthread_joinCounter;
    void* pthread_joinResultReturn;
    virtual int pthread_join(pthread_t t, void ** r){
        pthread_joinCounter++;
        if(r!=0)
            *r=pthread_joinResultReturn;
        char* p=(char*)t;
        p[0]='x';p[1]+=1;
        return pthread_joinReturns; 
    }
    int pthread_detachReturns;
    int pthread_detachCounter;        
    virtual int pthread_detach(pthread_t t){
        pthread_detachCounter++;
        char* p=(char*)t;
        p[0]='x';p[1]+=1;        
        return pthread_detachReturns;
    }

    template<class T>
    static bool isInitialized(const T& t){
        return ((char*)t)[0]=='i';
    }
    template<class T>
    static bool isDestroyed(const T& t){
        return ((char*)t)[0]=='x';
    }
    template<class T>
    static int getDestroyCounter(const T& t){
        return ((char*)t)[1];
    }
    template<class T>
    static int getInvalidAccessCounter(const T& t){
        return ((char*)t)[2];
    }
    int pthread_cond_broadcastReturns;
    int pthread_cond_broadcastCounter;
    virtual int pthread_cond_broadcast(pthread_cond_t *c){
        pthread_cond_broadcastCounter++;
        if(isDestroyed(c))((char*)c)[2]++;
        return pthread_cond_broadcastReturns; 
    }
    int pthread_cond_destroyReturns;
    int pthread_cond_destroyCounter;
    virtual int pthread_cond_destroy(pthread_cond_t *c){
        pthread_cond_destroyCounter++;
        char* p=(char*)c;
        p[0]='x';p[1]+=1;
        return pthread_cond_destroyReturns; 
    }
    int pthread_cond_initReturns;
    int pthread_cond_initCounter;
    virtual int pthread_cond_init(pthread_cond_t *c, const pthread_condattr_t *a){
        pthread_cond_initCounter++;
        char* p=(char*)c;
        p[0]='i'; // mark as created
        p[1]=0;   // destruction counter
        p[2]=0;   // access after destruction counter
        return pthread_cond_initReturns; 
    }
    int pthread_cond_signalReturns;
    int pthread_cond_signalCounter;
    virtual int pthread_cond_signal(pthread_cond_t *c){
        pthread_cond_signalCounter++;
        if(isDestroyed(c))((char*)c)[2]++;
        return pthread_cond_signalReturns; 
    }
    int pthread_cond_timedwaitReturns;
    int pthread_cond_timedwaitCounter;
    virtual int pthread_cond_timedwait(pthread_cond_t *c,
            pthread_mutex_t *m, const struct timespec *t){
        pthread_cond_timedwaitCounter++;
        if(isDestroyed(c))((char*)c)[2]++;
        return pthread_cond_timedwaitReturns; 
    }
    int pthread_cond_waitReturns;
    int pthread_cond_waitCounter;
    virtual int pthread_cond_wait(pthread_cond_t *c, pthread_mutex_t *m){
        pthread_cond_waitCounter++;
        if(isDestroyed(c))((char*)c)[2]++;
        return pthread_cond_waitReturns; 
    }
    int pthread_mutex_destroyReturns;
    int pthread_mutex_destroyCounter;
    virtual int pthread_mutex_destroy(pthread_mutex_t *m){
        pthread_mutex_destroyCounter++;
        char* p=(char*)m;
        p[0]='x';p[1]+=1;
        return pthread_mutex_destroyReturns; 
    }
    int pthread_mutex_initReturns;
    int pthread_mutex_initCounter;
    virtual int pthread_mutex_init(pthread_mutex_t *m, const pthread_mutexattr_t *a){
        pthread_mutex_initCounter++;
        char* p=(char*)m;
        p[0]='i'; // mark as created
        p[1]=0;   // destruction counter
        p[2]=0;   // access after destruction counter
        return pthread_mutex_initReturns; 
    }
    int pthread_mutex_lockReturns;
    int pthread_mutex_lockCounter;
    virtual int pthread_mutex_lock(pthread_mutex_t *m){
        pthread_mutex_lockCounter++;
        if(isDestroyed(m))((char*)m)[2]++;
        return pthread_mutex_lockReturns; 
    }
    int pthread_mutex_trylockReturns;
    int pthread_mutex_trylockCounter;
    virtual int pthread_mutex_trylock(pthread_mutex_t *m){
        pthread_mutex_trylockCounter++;
        if(isDestroyed(m))((char*)m)[2]++;
        return pthread_mutex_trylockReturns; 
    }
    int pthread_mutex_unlockReturns;
    int pthread_mutex_unlockCounter;
    virtual int pthread_mutex_unlock(pthread_mutex_t *m){
        pthread_mutex_unlockCounter++;
        if(isDestroyed(m))((char*)m)[2]++;
        return pthread_mutex_unlockReturns; 
    }
};

// simulates the way zookeeper threads make use of api_prolog/epilog and
// 
class MockPthreadZKNull: public MockPthreadsNull
{
    typedef std::map<pthread_t,zhandle_t*> Map;
    Map map_;
public:
    virtual int pthread_create(pthread_t * t, const pthread_attr_t *a,
            void *(*f)(void *), void *d){
        int ret=MockPthreadsNull::pthread_create(t,a,f,d);
        zhandle_t* zh=(zhandle_t*)d;
        adaptor_threads* ad=(adaptor_threads*)zh->adaptor_priv;
        api_prolog(zh);
        ad->threadsToWait--;
        putValue(map_,*t,zh);
        return ret;
    }
    virtual int pthread_join(pthread_t t, void ** r){
        zhandle_t* zh=0;
        if(getValue(map_,t,zh))
            api_epilog(zh,0);
        return MockPthreadsNull::pthread_join(t,r);
    }
};

struct ThreadInfo{
    typedef enum {RUNNING,TERMINATED} ThreadState;
    
    ThreadInfo():
        destructionCounter_(0),invalidAccessCounter_(0),state_(RUNNING)
    {
    }
    
    ThreadInfo& incDestroyed() {
        destructionCounter_++;
        return *this;
    }
    ThreadInfo& incInvalidAccess(){
        invalidAccessCounter_++;
        return *this;
    }
    ThreadInfo& setTerminated(){
        state_=TERMINATED;
        return *this;
    }
    int destructionCounter_;
    int invalidAccessCounter_;
    ThreadState state_;
};

class CheckedPthread: public MockPthreadsBase
{
    // first => destruction counter
    // second => invalid access counter
    //typedef std::pair<int,int> Entry;
    typedef ThreadInfo Entry;
    typedef std::map<pthread_t,Entry> ThreadMap;
    static ThreadMap tmap_;
    static ThreadMap& getMap(const TypeOp<pthread_t>::BareT&){return tmap_;}
    typedef std::map<pthread_mutex_t*,Entry> MutexMap;
    static MutexMap mmap_;
    static MutexMap& getMap(const TypeOp<pthread_mutex_t>::BareT&){return mmap_;}
    typedef std::map<pthread_cond_t*,Entry> CVMap;
    static CVMap cvmap_;
    static CVMap& getMap(const TypeOp<pthread_cond_t>::BareT&){return cvmap_;}
    
    static Mutex mx;
    
    template<class T>
    static void markDestroyed(T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        if(getValue(getMap(Type()),t,e)){
            putValue(getMap(Type()),t,Entry(e).incDestroyed());
        }else{
            putValue(getMap(Type()),t,Entry().incDestroyed());
        }
    }
    template<class T>
    static void markCreated(T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        if(!getValue(getMap(Type()),t,e))
            putValue(getMap(Type()),t,Entry());
    }
    template<class T>
    static void checkAccessed(T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        if(getValue(getMap(Type()),t,e) && e.destructionCounter_>0)
            putValue(getMap(Type()),t,Entry(e).incInvalidAccess());
    }
    static void setTerminated(pthread_t t){
        Entry e;
        synchronized(mx);
        if(getValue(tmap_,t,e))
            putValue(tmap_,t,Entry(e).setTerminated());
    }
public:
    bool verbose;
    CheckedPthread():verbose(false){
        tmap_.clear();
        mmap_.clear();
        cvmap_.clear();
        mx.release();
    }
    template <class T>
    static bool isInitialized(const T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        return getValue(getMap(Type()),t,e) && e.destructionCounter_==0;
    }
    template <class T>
    static bool isDestroyed(const T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        return getValue(getMap(Type()),t,e) && e.destructionCounter_>0;
    }
    static bool isTerminated(pthread_t t){
        Entry e;
        synchronized(mx);
        return getValue(tmap_,t,e) && e.state_==ThreadInfo::TERMINATED;        
    }
    template <class T>
    static int getDestroyCounter(const T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        return getValue(getMap(Type()),t,e)?e.destructionCounter_:-1;
    }
    template<class T>
    static int getInvalidAccessCounter(const T& t){
        typedef typename TypeOp<T>::BareT Type;
        Entry e;
        synchronized(mx);
        return getValue(getMap(Type()),t,e)?e.invalidAccessCounter_:-1;
    }

    struct ThreadContext{
        typedef void *(*ThreadFunc)(void *);
        
        ThreadContext(ThreadFunc func,void* param):func_(func),param_(param){}
        ThreadFunc func_;
        void* param_;        
    };
    static void* threadFuncWrapper(void* v){
        ThreadContext* ctx=(ThreadContext*)v;
        pthread_t t=pthread_self();
        markCreated(t);
        void* res=ctx->func_(ctx->param_);
        setTerminated(pthread_self());
        delete ctx;
        return res;
    }
    virtual int pthread_create(pthread_t * t, const pthread_attr_t *a,
            void *(*f)(void *), void *d)
    {
        int ret=LIBC_SYMBOLS.pthread_create(t,a,threadFuncWrapper,
                new ThreadContext(f,d));
        if(verbose)
            TEST_TRACE(("thread created %p",*t));
        return ret;
    }
    virtual int pthread_join(pthread_t t, void ** r){
        if(verbose) TEST_TRACE(("thread joined %p",t));
        int ret=LIBC_SYMBOLS.pthread_join(t,r);
        if(ret==0)
            markDestroyed(t);
        return ret;
    }
    virtual int pthread_detach(pthread_t t){
        if(verbose) TEST_TRACE(("thread detached %p",t));
        int ret=LIBC_SYMBOLS.pthread_detach(t);
        if(ret==0)
            markDestroyed(t);
        return ret;
    }
    virtual int pthread_cond_broadcast(pthread_cond_t *c){
        checkAccessed(c);
        return LIBC_SYMBOLS.pthread_cond_broadcast(c);
    }
    virtual int pthread_cond_destroy(pthread_cond_t *c){
        markDestroyed(c);
        return LIBC_SYMBOLS.pthread_cond_destroy(c);
    }
    virtual int pthread_cond_init(pthread_cond_t *c, const pthread_condattr_t *a){
        markCreated(c);
        return LIBC_SYMBOLS.pthread_cond_init(c,a);
    }
    virtual int pthread_cond_signal(pthread_cond_t *c){
        checkAccessed(c);
        return LIBC_SYMBOLS.pthread_cond_signal(c);
    }
    virtual int pthread_cond_timedwait(pthread_cond_t *c,
            pthread_mutex_t *m, const struct timespec *t){
        checkAccessed(c);
        return LIBC_SYMBOLS.pthread_cond_timedwait(c,m,t);
    }
    virtual int pthread_cond_wait(pthread_cond_t *c, pthread_mutex_t *m){
        checkAccessed(c);
        return LIBC_SYMBOLS.pthread_cond_wait(c,m);
    }
    virtual int pthread_mutex_destroy(pthread_mutex_t *m){
        markDestroyed(m);
        return LIBC_SYMBOLS.pthread_mutex_destroy(m);
    }
    virtual int pthread_mutex_init(pthread_mutex_t *m, const pthread_mutexattr_t *a){
        markCreated(m);
        return LIBC_SYMBOLS.pthread_mutex_init(m,a);
    }
    virtual int pthread_mutex_lock(pthread_mutex_t *m){
        checkAccessed(m);
        return LIBC_SYMBOLS.pthread_mutex_lock(m);
    }
    virtual int pthread_mutex_trylock(pthread_mutex_t *m){
        checkAccessed(m);
        return LIBC_SYMBOLS.pthread_mutex_trylock(m);
    }
    virtual int pthread_mutex_unlock(pthread_mutex_t *m){
        checkAccessed(m);
        return LIBC_SYMBOLS.pthread_mutex_unlock(m);
    }    
};

#endif /*PTHREADMOCKS_H_*/

