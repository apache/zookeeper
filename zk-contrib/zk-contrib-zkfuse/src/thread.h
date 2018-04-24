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

#ifndef __THREAD_H__
#define __THREAD_H__

#include <errno.h>
#include <string.h>
#include <assert.h>
#include <pthread.h>

#include "log.h"

START_ZKFUSE_NAMESPACE

class Thread {
  public:
    static const size_t defaultStackSize = 1024 * 1024;
    typedef void* (*ThreadFunc) (void*);
    Thread(size_t stackSize = defaultStackSize) 
      : _stackSize(stackSize), _ctx(NULL), _func(NULL) 
    {
        memset( &mThread, 0, sizeof(mThread) );
    }
    ~Thread() { }

    void Create(void* ctx, ThreadFunc func);
    void Join() {
        //avoid SEGFAULT because of unitialized mThread
        //in case Create(...) was never called
        if (_func != NULL) {
            pthread_join(mThread, 0);
        }
    }
  private:
    pthread_t mThread;  
    void *_ctx;
    ThreadFunc _func;
    size_t _stackSize;
};


template<typename T>
struct ThreadContext {
    typedef void (T::*FuncPtr) (void);
    ThreadContext(T& ctx, FuncPtr func) : _ctx(ctx), _func(func) {}
    void run(void) {
        (_ctx.*_func)();
    }
    T& _ctx;
    FuncPtr   _func;
};

template<typename T>
void* ThreadExec(void *obj) {
    ThreadContext<T>* tc = (ThreadContext<T>*)(obj);
    assert(tc != 0);
    tc->run();
    return 0;
}

template <typename T>
class CXXThread : public Thread {
  public:
    typedef void (T::*FuncPtr) (void);
    CXXThread(size_t stackSize = Thread::defaultStackSize) 
      : Thread(stackSize), ctx(0) {}
    ~CXXThread() { if (ctx) delete ctx; }

    void Create(T& obj, FuncPtr func) {
        assert(ctx == 0);
        ctx = new ThreadContext<T>(obj, func);
        Thread::Create(ctx, ThreadExec<T>);
    }

  private:
    ThreadContext<T>* ctx;
};

    
END_ZKFUSE_NAMESPACE

#endif /* __THREAD_H__ */

