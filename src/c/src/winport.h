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

/**
 * This header file is to port pthread lib , sockets and other utility methods on windows.
 * Specifically the threads function, mutexes, keys, and socket initialization.
 */

#ifndef WINPORT_H_
#define WINPORT_H_

#ifdef WIN32
#include <winconfig.h>
#include <errno.h>
#include <process.h>
#include <stdlib.h>
#include <malloc.h>


typedef int ssize_t;
typedef HANDLE pthread_mutex_t;

struct pthread_t_
{
  HANDLE thread_handle;
  DWORD  thread_id;
};

typedef struct pthread_t_ pthread_t;
typedef int pthread_mutexattr_t;       
typedef int pthread_condattr_t;        
typedef int pthread_attr_t; 
#define PTHREAD_MUTEX_RECURSIVE 0

int pthread_mutex_lock(pthread_mutex_t* _mutex );
int pthread_mutex_unlock( pthread_mutex_t* _mutex );
int pthread_mutex_init(pthread_mutex_t* _mutex, void* ignoredAttr);
int pthread_mutex_destroy(pthread_mutex_t* _mutex);
int pthread_create(pthread_t *thread, const pthread_attr_t *attr, unsigned  (__stdcall* start_routine)(void* a), void *arg);
int pthread_equal(pthread_t t1, pthread_t t2);
pthread_t pthread_self();
int pthread_join(pthread_t _thread, void** ignore);
int pthread_detach(pthread_t _thread);

void pthread_mutexattr_init(pthread_mutexattr_t* ignore);
void pthread_mutexattr_settype(pthread_mutexattr_t* ingore_attr, int ignore);
void pthread_mutexattr_destroy(pthread_mutexattr_t* ignore_attr);


// http://www.cs.wustl.edu/~schmidt/win32-cv-1.html
 
typedef struct 
{
       int waiters_count_;
    // Number of waiting threads.

    CRITICAL_SECTION waiters_count_lock_;
    // Serialize access to <waiters_count_>.

    HANDLE sema_;
       // Semaphore used to queue up threads waiting for the condition to
    // become signaled. 

    HANDLE waiters_done_;
    // An auto-reset event used by the broadcast/signal thread to wait
    // for all the waiting thread(s) to wake up and be released from the
    // semaphore. 

    size_t was_broadcast_;
    // Keeps track of whether we were broadcasting or signaling.  This
    // allows us to optimize the code if we're just signaling.
}pthread_cond_t;
       
int pthread_cond_init (pthread_cond_t *cv,const pthread_condattr_t * ignore);
int pthread_cond_destroy(pthread_cond_t *cond);
int pthread_cond_signal (pthread_cond_t *cv);
int pthread_cond_broadcast (pthread_cond_t *cv);
int pthread_cond_wait (pthread_cond_t *cv, pthread_mutex_t *external_mutex);


struct pthread_key_t_
{
  DWORD key;
  void (*destructor) (void *);  
};

typedef struct pthread_key_t_ pthread_key_t;
int pthread_key_create(pthread_key_t *key, void (*destructor)(void *) );
int pthread_key_delete(pthread_key_t key);
void *pthread_getspecific(pthread_key_t key);
int pthread_setspecific(pthread_key_t key, const void *value);

inline int gettimeofday(struct timeval *tp, void *tzp) {
        int64_t now = 0;
        if (tzp != 0) { errno = EINVAL; return -1; }
        GetSystemTimeAsFileTime( (LPFILETIME)&now );
        tp->tv_sec = (long)(now / 10000000 - 11644473600LL);
        tp->tv_usec = (now / 10) % 1000000;
        return 0;
}
int close(SOCKET fd);
int Win32WSAStartup();
void Win32WSACleanup();
#endif //WIN32



#endif //WINPORT_H_
