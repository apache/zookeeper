#ifndef ZK_LOG_H_
#define ZK_LOG_H_

#include <zookeeper.h>

extern ZOOAPI ZooLogLevel logLevel;
#define LOGSTREAM stderr

#define LOG_ERROR(x) \
    log_message(LOG_LEVEL_DEBUG,__LINE__,__func__,format_log_message x)
#define LOG_WARN(x) if(logLevel>=LOG_LEVEL_WARN) \
    log_message(LOG_LEVEL_WARN,__LINE__,__func__,format_log_message x)
#define LOG_INFO(x) if(logLevel>=LOG_LEVEL_INFO) \
    log_message(LOG_LEVEL_INFO,__LINE__,__func__,format_log_message x)
#define LOG_DEBUG(x) if(logLevel==LOG_LEVEL_DEBUG) \
    log_message(LOG_LEVEL_DEBUG,__LINE__,__func__,format_log_message x)

void setCurrentLogLevel(ZooLogLevel level);

void log_message(ZooLogLevel curLevel, int line,const char* funcName,
    const char* message);

const char* format_log_message(const char* format,...);

#endif /*ZK_LOG_H_*/
