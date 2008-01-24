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

#ifndef THREADINGUTIL_H_
#define THREADINGUTIL_H_

#ifdef THREADED
#include "pthread.h"
#endif

// *****************************************************************************
// Threading primitives

// atomic post-increment; returns the previous value of the operand
int32_t atomic_post_incr(volatile int32_t* operand, int32_t incr);
// atomic fetch&store; returns the previous value of the operand
int32_t atomic_fetch_store(volatile int32_t *operand, int32_t value);

// a partial implementation of an atomic integer type
class AtomicInt{
public:
    explicit AtomicInt(int32_t init=0):v_(init){}
    AtomicInt(const AtomicInt& other):v_(other){}
    // assigment
    AtomicInt& operator=(const AtomicInt& lhs){
        atomic_fetch_store(&v_,lhs);
        return *this;
    }
    // pre-increment
    AtomicInt& operator++() {
        atomic_post_incr(&v_,1);
        return *this;
    }
    // pre-decrement
    AtomicInt& operator--() {
        atomic_post_incr(&v_,-1);
        return *this;
    }
    // post-increment
    AtomicInt operator++(int){
        return AtomicInt(atomic_post_incr(&v_,1));
    }
    // post-decrement
    AtomicInt operator--(int){
        return AtomicInt(atomic_post_incr(&v_,-1));
    }
    
    operator int() const{
        return atomic_post_incr(&v_,0);
    }
    int get() const{
        return atomic_post_incr(&v_,0);
    }
private:
    mutable int32_t v_;
};

#ifdef THREADED
// ****************************************************************************
class Mutex{
public:
    Mutex();
    ~Mutex();
    void acquire();
    void release();
private:
    Mutex(const Mutex&);
    Mutex& operator=(const Mutex&);
    struct Impl;
    Impl* impl_;
};

class MTLock{
public:
    MTLock(Mutex& m):m_(m){m.acquire();}
    ~MTLock(){m_.release();}
    Mutex& m_;
};

#define synchronized(m) MTLock __lock(m)

// ****************************************************************************
class Latch {
public:
    virtual ~Latch() {}
    virtual void await() =0;
    virtual void signalAndWait() =0;
    virtual void signal() =0;
};

class CountDownLatch: public Latch {
public:
    CountDownLatch(int count):count_(count) {
        pthread_cond_init(&cond_,0);
        pthread_mutex_init(&mut_,0);
    }
    virtual ~CountDownLatch() {
        pthread_mutex_lock(&mut_);
        if(count_!=0) {
            count_=0;
            pthread_cond_broadcast(&cond_);
        }
        pthread_mutex_unlock(&mut_);

        pthread_cond_destroy(&cond_);
        pthread_mutex_destroy(&mut_);
    }

    virtual void await() {
        pthread_mutex_lock(&mut_);
        awaitImpl();
        pthread_mutex_unlock(&mut_);
    }
    virtual void signalAndWait() {
        pthread_mutex_lock(&mut_);
        signalImpl();
        awaitImpl();
        pthread_mutex_unlock(&mut_);
    }
    virtual void signal() {
        pthread_mutex_lock(&mut_);
        signalImpl();
        pthread_mutex_unlock(&mut_);
    }
private:
    void awaitImpl() {
        while(count_!=0)
        pthread_cond_wait(&cond_,&mut_);
    }
    void signalImpl() {
        if(count_>0) {
            count_--;
            pthread_cond_broadcast(&cond_);
        }
    }
    int count_;
    pthread_mutex_t mut_;
    pthread_cond_t cond_;
};

#define VALIDATE_JOB(j) j.validate(__FILE__,__LINE__)

class TestJob {
public:
    typedef long JobId;
    TestJob():hasRun_(false),startLatch_(0),endLatch_(0) {}
    virtual ~TestJob() {
        join();
    }

    virtual void run() =0;
    virtual void validate(const char* file, int line) const =0;

    virtual void start(Latch* startLatch=0,Latch* endLatch=0) {
        startLatch_=startLatch;endLatch_=endLatch;
        hasRun_=true;
        pthread_create(&thread_, 0, thread, this);
    }
    virtual JobId getJobId() const {
        return (JobId)thread_;
    }
    virtual void join() {
        if(!hasRun_)
        return;
        if(!pthread_equal(thread_,pthread_self()))
        pthread_join(thread_,0);
        else
        pthread_detach(thread_);
    }
private:
    void awaitStart() {
        if(startLatch_==0) return;
        startLatch_->signalAndWait();
    }
    void signalFinished() {
        if(endLatch_==0) return;
        endLatch_->signal();
    }
    static void* thread(void* p) {
        TestJob* j=(TestJob*)p;
        j->awaitStart(); // wait for the start command
        j->run();
        j->signalFinished();
        return 0;
    }
    bool hasRun_;
    Latch* startLatch_;
    Latch* endLatch_;
    pthread_t thread_;
};
#else // THREADED
// single THREADED
class Mutex{
public:
    void acquire(){}
    void release(){}
};
#define synchronized(m)

#endif // THREADED

#endif /*THREADINGUTIL_H_*/
