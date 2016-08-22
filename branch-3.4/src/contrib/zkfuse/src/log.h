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

#ifndef __LOG_H__
#define __LOG_H__

#define ZKFUSE_NAMESPACE zkfuse
#define START_ZKFUSE_NAMESPACE namespace ZKFUSE_NAMESPACE {
#define END_ZKFUSE_NAMESPACE   }
#define USING_ZKFUSE_NAMESPACE using namespace ZKFUSE_NAMESPACE;

#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>

#include <log4cxx/logger.h> 
#include <log4cxx/propertyconfigurator.h> 
#include <log4cxx/helpers/exception.h> 
using namespace log4cxx; 
using namespace log4cxx::helpers;

#define PRINTIP(x) ((uint8_t*)&x)[0], ((uint8_t*)&x)[1], \
                   ((uint8_t*)&x)[2], ((uint8_t*)&x)[3]

#define IPFMT "%u.%u.%u.%u"

#define DECLARE_LOGGER(varName) \
extern LoggerPtr varName;

#define DEFINE_LOGGER(varName, logName) \
static LoggerPtr varName = Logger::getLogger( logName );

#define MAX_BUFFER_SIZE 20000

#define SPRINTF_LOG_MSG(buffer, fmt, args...) \
    char buffer[MAX_BUFFER_SIZE]; \
    snprintf( buffer, MAX_BUFFER_SIZE, fmt, ##args );

// older versions of log4cxx don't support tracing
#ifdef LOG4CXX_TRACE
#define LOG_TRACE(logger, fmt, args...) \
    if (logger->isTraceEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_TRACE( logger, __tmp ); \
    }
#else
#define LOG_TRACE(logger, fmt, args...) \
    if (logger->isDebugEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_DEBUG( logger, __tmp ); \
    }
#endif

#define LOG_DEBUG(logger, fmt, args...) \
    if (logger->isDebugEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_DEBUG( logger, __tmp ); \
    }

#define LOG_INFO(logger, fmt, args...) \
    if (logger->isInfoEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_INFO( logger, __tmp ); \
    }

#define LOG_WARN(logger, fmt, args...) \
    if (logger->isWarnEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_WARN( logger, __tmp ); \
    }

#define LOG_ERROR(logger, fmt, args...) \
    if (logger->isErrorEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_ERROR( logger, __tmp ); \
    }

#define LOG_FATAL(logger, fmt, args...) \
    if (logger->isFatalEnabled()) { \
        SPRINTF_LOG_MSG( __tmp, fmt, ##args ); \
        LOG4CXX_FATAL( logger, __tmp ); \
    }

#ifdef DISABLE_TRACE
#   define TRACE(logger, x)
#else   
#   define TRACE(logger, x) \
class Trace { \
 public: \
    Trace(const void* p) : _p(p) { \
        LOG_TRACE(logger, "%s %p Enter", __PRETTY_FUNCTION__, p); \
    } \
    ~Trace() { \
        LOG_TRACE(logger, "%s %p Exit", __PRETTY_FUNCTION__, _p); \
    } \
    const void* _p; \
} traceObj(x);
#endif  /* DISABLE_TRACE */
    
#endif  /* __LOG_H__ */

