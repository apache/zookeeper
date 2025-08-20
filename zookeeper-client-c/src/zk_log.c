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

#if !defined(DLL_EXPORT) && !defined(USE_STATIC_LIB)
#  define USE_STATIC_LIB
#endif

#include "zookeeper_log.h"
#ifndef WIN32
#include <unistd.h>
#else
typedef DWORD pid_t;
#include <process.h> /* for getpid */
#endif

#include <stdarg.h>
#include <time.h>

#define TIME_NOW_BUF_SIZE 1024
#define FORMAT_LOG_BUF_SIZE 4096

#ifdef THREADED
#ifndef WIN32
#include <pthread.h>
#else 
#include "winport.h"
#endif

static pthread_key_t time_now_buffer;
static pthread_key_t format_log_msg_buffer;

void freeBuffer(void* p){
    if(p) free(p);
}

__attribute__((constructor)) void prepareTSDKeys() {
    pthread_key_create (&time_now_buffer, freeBuffer);
    pthread_key_create (&format_log_msg_buffer, freeBuffer);
}

char* getTSData(pthread_key_t key,int size){
    char* p=pthread_getspecific(key);
    if(p==0){
        int res;
        p=calloc(1,size);
        res=pthread_setspecific(key,p);
        if(res!=0){
            fprintf(stderr,"Failed to set TSD key: %d",res);
        }
    }
    return p;
}

char* get_time_buffer(){
    return getTSData(time_now_buffer,TIME_NOW_BUF_SIZE);
}

char* get_format_log_buffer(){  
    return getTSData(format_log_msg_buffer,FORMAT_LOG_BUF_SIZE);
}
#else
char* get_time_buffer(){
    static char buf[TIME_NOW_BUF_SIZE];
    return buf;    
}

char* get_format_log_buffer(){
    static char buf[FORMAT_LOG_BUF_SIZE];
    return buf;
}

#endif

ZooLogLevel logLevel=ZOO_LOG_LEVEL_INFO;

static FILE* logStream=0;
FILE* zoo_get_log_stream(){
    if(logStream==0)
        logStream=stderr;
    return logStream;
}

void zoo_set_log_stream(FILE* stream){
    logStream=stream;
}

static const char* time_now(char* now_str){
    struct timeval tv;
    struct tm lt;
    time_t now = 0;
    size_t len = 0;
    
    gettimeofday(&tv,0);

    now = tv.tv_sec;
    localtime_r(&now, &lt);

    // clone the format used by logback ISO8601DateFormat
    // specifically: "yyyy-MM-dd HH:mm:ss,SSS"

    len = strftime(now_str, TIME_NOW_BUF_SIZE,
                          "%Y-%m-%d %H:%M:%S",
                          &lt);

    len += snprintf(now_str + len,
                    TIME_NOW_BUF_SIZE - len,
                    ",%03d",
                    (int)(tv.tv_usec/1000));

    return now_str;
}

void log_message(log_callback_fn callback, ZooLogLevel curLevel,
    int line, const char* funcName, const char* format, ...)
{
    static const char* dbgLevelStr[]={"ZOO_INVALID","ZOO_ERROR","ZOO_WARN",
            "ZOO_INFO","ZOO_DEBUG"};
    static pid_t pid=0;
    va_list va;
    int ofs = 0;
#ifdef THREADED
    unsigned long int tid = 0;
#endif
#ifdef WIN32
    char timebuf [TIME_NOW_BUF_SIZE];
    const char* time = time_now(timebuf);
#else
    const char* time = time_now(get_time_buffer());
#endif

    char* buf = get_format_log_buffer();
    if(!buf)
    {
        fprintf(stderr, "log_message: Unable to allocate memory buffer");
        return;
    }

    if(pid==0)
    {
        pid=getpid();
    }


#ifndef THREADED

    // pid_t is long on Solaris
    ofs = snprintf(buf, FORMAT_LOG_BUF_SIZE,
                   "%s:%ld:%s@%s@%d: ", time, (long)pid,
                   dbgLevelStr[curLevel], funcName, line);
#else

    #ifdef WIN32
        tid = (unsigned long int)(pthread_self().thread_id);
    #else
        tid = (unsigned long int)(pthread_self());
    #endif

    ofs = snprintf(buf, FORMAT_LOG_BUF_SIZE-1,
                   "%s:%ld(0x%lx):%s@%s@%d: ", time, (long)pid, tid,
                   dbgLevelStr[curLevel], funcName, line);
#endif

    // Now grab the actual message out of the variadic arg list
    va_start(va, format);
    vsnprintf(buf+ofs, FORMAT_LOG_BUF_SIZE-1-ofs, format, va);
    va_end(va);

    if (callback)
    {
        callback(buf);
    } else {
        fprintf(zoo_get_log_stream(), "%s\n", buf);
        fflush(zoo_get_log_stream());
    }
}

void zoo_set_debug_level(ZooLogLevel level)
{
    if(level==0){
        // disable logging (unit tests do this)
        logLevel=(ZooLogLevel)0;
        return;
    }
    if(level<ZOO_LOG_LEVEL_ERROR)level=ZOO_LOG_LEVEL_ERROR;
    if(level>ZOO_LOG_LEVEL_DEBUG)level=ZOO_LOG_LEVEL_DEBUG;
    logLevel=level;
}

