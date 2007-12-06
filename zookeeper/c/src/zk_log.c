#ifndef DLL_EXPORT
#  define USE_STATIC_LIB
#endif

#include "zk_log.h"
#include <stdio.h>
#include <unistd.h>
#include <stdarg.h>

ZooLogLevel logLevel=LOG_LEVEL_INFO;

static const char* time_now(){
    static char now_str[128];
    struct timeval tv;
    
    gettimeofday(&tv,0);
    //sprintf(now_str,"%ld.%03d",tv.tv_sec,(int)(tv.tv_usec/1000));
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
    fprintf(LOGSTREAM, "%s:%d:%s@%s@%d: %s\n", time_now(),pid,
            dbgLevelStr[curLevel],funcName,line,message);
    fflush(LOGSTREAM);
}

const char* format_log_message(const char* format,...)
{
    static char buf[2048];
    va_list va;
    va_start(va,format);
    vsnprintf(buf, sizeof(buf)-1,format,va);
    va_end(va); 
    return buf;
}

void setCurrentLogLevel(ZooLogLevel level)
{
    if(level<LOG_LEVEL_ERROR)level=LOG_LEVEL_ERROR;
    if(level>LOG_LEVEL_DEBUG)level=LOG_LEVEL_DEBUG;
    logLevel=level;
}
