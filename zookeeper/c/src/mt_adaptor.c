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
#ifndef THREADED
#define THREADED
#endif

#define BUILD_LIB

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "zk_adaptor.h"
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <sys/time.h>
#include <signal.h>
#include <sys/select.h>


void lock_buffer_list(buffer_head_t *l)
{
    pthread_mutex_lock(&l->lock);
}
void unlock_buffer_list(buffer_head_t *l)
{
    pthread_mutex_unlock(&l->lock);
}
void lock_completion_list(completion_head_t *l)
{
    pthread_mutex_lock(&l->lock);
}
void unlock_completion_list(completion_head_t *l)
{
    pthread_cond_broadcast(&l->cond);
    pthread_mutex_unlock(&l->lock);
}
struct sync_completion *alloc_sync_completion(void)
{
    struct sync_completion *sc = (struct sync_completion*)calloc(1, sizeof(struct sync_completion));
    if (sc) {
       pthread_cond_init(&sc->cond, 0); 
       pthread_mutex_init(&sc->lock, 0);
    }
    return sc;
}
int wait_sync_completion(struct sync_completion *sc)
{
    pthread_mutex_lock(&sc->lock);
    while (!sc->complete) {
        pthread_cond_wait(&sc->cond, &sc->lock);
    }
    pthread_mutex_unlock(&sc->lock);
    return 0;
}

void free_sync_completion(struct sync_completion *sc)
{
    if (sc) {
        pthread_mutex_destroy(&sc->lock);
        pthread_cond_destroy(&sc->cond);
        free(sc);
    }
}

void notify_sync_completion(struct sync_completion *sc)
{
    pthread_mutex_lock(&sc->lock);
    sc->complete = 1;
    pthread_cond_broadcast(&sc->cond);
    pthread_mutex_unlock(&sc->lock);
}

int process_async(int outstanding_sync)
{
    return 0;
}
struct adaptor_threads {
    pthread_t io;
    pthread_t completion;
};

void *do_io(void *);
void *do_completion(void *);

int adaptor_init(zhandle_t *zh)
{
    struct adaptor_threads *adaptor_threads = calloc(1, sizeof(*adaptor_threads)); 
    if (!adaptor_threads) {
        return -1;
    }
    zh->adaptor_priv = adaptor_threads;
    pthread_create(&adaptor_threads->io, 0, do_io, zh);
    pthread_create(&adaptor_threads->completion, 0, do_completion, zh);
    return 0;
}

void adaptor_finish(zhandle_t *zh)
{
    struct adaptor_threads *adaptor_threads = zh->adaptor_priv;
      
    if (zh->state >= 0) {
        fprintf(stderr, "Forcibly setting state to CLOSED\n");
        zh->state = -1;
    }
    pthread_kill(adaptor_threads->io, SIGUSR1);
    pthread_mutex_lock(&zh->completions_to_process.lock);
    pthread_cond_broadcast(&zh->completions_to_process.cond);
    pthread_mutex_unlock(&zh->completions_to_process.lock);
    pthread_join(adaptor_threads->io, 0);
    pthread_join(adaptor_threads->completion, 0);
}

int adaptor_send_queue(zhandle_t *zh, int timeout)
{
    struct adaptor_threads *adaptor_threads = zh->adaptor_priv;
    pthread_kill(adaptor_threads->io, SIGUSR1);
    return 0;
}

static void nothing(int num) {}

/* These two are declared here because we will run the event loop
 * and not the client */
int zookeeper_interest(zhandle_t *zh, int *fd, int *interest,
        struct timeval *tv);
int zookeeper_process(zhandle_t *zh, int events);


void *do_io(void *v)
{
    zhandle_t *zh = (zhandle_t*)v;
    int fd;
    int interest;
    struct timeval tv;
    fd_set rfds;
    fd_set wfds;
    fd_set efds;
    sigset_t nosigusr1;
    sigset_t sigusr1;
    struct timespec timeout;
    sigemptyset(&nosigusr1);
    sigaddset(&nosigusr1, SIGUSR1);
    sigemptyset(&sigusr1);
    pthread_sigmask(SIG_SETMASK, &nosigusr1, 0);
    signal(SIGUSR1, nothing);
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    FD_ZERO(&efds);
    while(zh->state >= 0) {
        int result;
        zookeeper_interest(zh, &fd, &interest, &tv);
        if (fd != -1) {
            if (interest&ZOOKEEPER_READ) {
                    FD_SET(fd, &rfds);
            } else {
                    FD_CLR(fd, &rfds);
            }
            if (interest&ZOOKEEPER_WRITE) {
                    FD_SET(fd, &wfds);
            } else {
                    FD_CLR(fd, &wfds);
            }
        }
        timeout.tv_sec = tv.tv_sec;
        timeout.tv_nsec = tv.tv_usec*1000;
        result = pselect((fd == -1 ? 0 : fd+1), &rfds, &wfds, &efds, &timeout, &sigusr1);
        interest = 0;
        if (fd != -1) {
            if (FD_ISSET(fd, &rfds)) {
                interest |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wfds)) {
                interest |= ZOOKEEPER_WRITE;
            }
        }
        result = zookeeper_process(zh, interest);
    }
    return 0;
}

void *do_completion(void *v)
{
    zhandle_t *zh = v;
    while(zh->state >= 0) {
        pthread_mutex_lock(&zh->completions_to_process.lock);
                while(!zh->completions_to_process.head && zh->state >= 0) {
            pthread_cond_wait(&zh->completions_to_process.cond, &zh->completions_to_process.lock);
        }
        pthread_mutex_unlock(&zh->completions_to_process.lock);
        process_completions(zh);
    }
    return 0;
}

int inc_nesting_level(nesting_level_t* nl,int i)
{
    int v;
    pthread_mutex_lock(&nl->lock);
    nl->level+=(i<0?-1:(i>0?1:0));
    v=nl->level;
    pthread_mutex_unlock(&nl->lock);
    return v;
}
