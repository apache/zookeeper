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

#ifndef __MUTEX_H__
#define __MUTEX_H__

#include <pthread.h>
#include <errno.h>
#include <sys/time.h>

#include "log.h"

START_ZKFUSE_NAMESPACE

class Cond;

class Mutex {
    friend class Cond;
  public:
    Mutex() {
        pthread_mutexattr_init( &m_mutexAttr );
        pthread_mutexattr_settype( &m_mutexAttr, PTHREAD_MUTEX_RECURSIVE_NP );
        pthread_mutex_init( &mutex, &m_mutexAttr );
    }
    ~Mutex() {
        pthread_mutex_destroy(&mutex);
        pthread_mutexattr_destroy( &m_mutexAttr );
    }
    void Acquire() { Lock(); }
    void Release() { Unlock(); }
    void Lock() {
        pthread_mutex_lock(&mutex);
    }
    int  TryLock() {
        return pthread_mutex_trylock(&mutex);
    }
    void Unlock() {
        pthread_mutex_unlock(&mutex);
    }
  private:
    pthread_mutex_t mutex;
    pthread_mutexattr_t m_mutexAttr;
};

class AutoLock {
  public:
    AutoLock(Mutex& mutex) : _mutex(mutex) {
        mutex.Lock();
    }
    ~AutoLock() {
        _mutex.Unlock();
    }
  private:
    friend class AutoUnlockTemp;
    Mutex& _mutex;
};

class AutoUnlockTemp {
  public:
    AutoUnlockTemp(AutoLock & autoLock) : _autoLock(autoLock) {
        _autoLock._mutex.Unlock();
    }
    ~AutoUnlockTemp() {
        _autoLock._mutex.Lock();
    }
  private:
    AutoLock & _autoLock;
};

class Cond {
  public:
    Cond() {
        static pthread_condattr_t attr;
        static bool inited = false;
        if(!inited) {
            inited = true;
            pthread_condattr_init(&attr);
        }
        pthread_cond_init(&_cond, &attr);
    }
    ~Cond() {
        pthread_cond_destroy(&_cond);
    }

    void Wait(Mutex& mutex) {
        pthread_cond_wait(&_cond, &mutex.mutex);
    }

    bool Wait(Mutex& mutex, long long int timeout) {
        struct timeval now;
        gettimeofday( &now, NULL );
        struct timespec abstime;
        int64_t microSecs = now.tv_sec * 1000000LL + now.tv_usec;
        microSecs += timeout * 1000;
        abstime.tv_sec = microSecs / 1000000LL;
        abstime.tv_nsec = (microSecs % 1000000LL) * 1000;
        if (pthread_cond_timedwait(&_cond, &mutex.mutex, &abstime) == ETIMEDOUT) {
            return false;
        } else {
            return true;
        }
    }
    
    void Signal() {
        pthread_cond_signal(&_cond);
    }

  private:
    pthread_cond_t            _cond;
};

/**
 * A wrapper class for {@link Mutex} and {@link Cond}.
 */
class Lock {
    public:
        
        void lock() {
            m_mutex.Lock();
        }
        
        void unlock() {
            m_mutex.Unlock();
        }
        
        void wait() {
            m_cond.Wait( m_mutex );
        }

        bool wait(long long int timeout) {
            return m_cond.Wait( m_mutex, timeout );
        }
        
        void notify() {
            m_cond.Signal();
        }

    private:
        
        /**
         * The mutex.
         */
        Mutex m_mutex;
        
        /**
         * The condition associated with this lock's mutex.
         */
        Cond m_cond;         
};

END_ZKFUSE_NAMESPACE
        
#endif /* __MUTEX_H__ */

