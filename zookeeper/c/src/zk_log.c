#ifndef DLL_EXPORT
#  define USE_STATIC_LIB
#endif

#include "zk_log.h"
#include <stdio.h>
#include <unistd.h>
#include <stdarg.h>

#define TIME_NOW_BUF_SIZE 128
#define FORMAT_LOG_BUF_SIZE 2048

#ifdef THREADED
#include <pthread.h>

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

ZooLogLevel logLevel=LOG_LEVEL_INFO;

static const char* time_now(){
    struct timeval tv;
    char* now_str=get_time_buffer();
    if(!now_str)
        return "time_now(): Failed to allocate memory buffer";
    
    gettimeofday(&tv,0);
    sprintf(now_str,"%ld.%03d.%03d",tv.tv_sec,(int)(tv.tv_usec/1000),(int)(tv.tv_usec%1000));
    return now_str;
}

void log_message(ZooLogLevel curLevel,int line,const char* funcName,
    const char* message)
{
    static const char* dbgLevelStr[]={"ZOO_INVALID","ZOO_ERROR","ZOO_WARN",
            "ZOO_INFO","ZOO_DEBUG"};
    static pid_t pid=0;
    if(pid==0)pid=getpid();
#ifndef THREADED
    fprintf(LOGSTREAM, "%s:%d:%s@%s@%d: %s\n", time_now(),pid,
            dbgLevelStr[curLevel],funcName,line,message);
#else
    fprintf(LOGSTREAM, "%s:%d(0x%x):%s@%s@%d: %s\n", time_now(),pid,
            (unsigned long)pthread_self(),
            dbgLevelStr[curLevel],funcName,line,message);
#endif
    fflush(LOGSTREAM);
}

const char* format_log_message(const char* format,...)
{
    va_list va;
    char* buf=get_format_log_buffer();
    if(!buf)
        return "format_log_message: Unable to allocate memory buffer";
    
    va_start(va,format);
    vsnprintf(buf, FORMAT_LOG_BUF_SIZE-1,format,va);
    va_end(va); 
    return buf;
}

void setCurrentLogLevel(ZooLogLevel level)
{
    if(level==0){
        // disable logging (unit tests do this)
        logLevel=(ZooLogLevel)0;
        return;
    }
    if(level<LOG_LEVEL_ERROR)level=LOG_LEVEL_ERROR;
    if(level>LOG_LEVEL_DEBUG)level=LOG_LEVEL_DEBUG;
    logLevel=level;
}
