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

#ifndef ZK_LOG_H_
#define ZK_LOG_H_

#include <zookeeper.h>

#ifdef __cplusplus
extern "C" {
#endif

extern ZOOAPI ZooLogLevel logLevel;
#define LOGCALLBACK(_zh) zoo_get_log_callback(_zh)
#define LOGSTREAM NULL

#define LOG_ERROR(_cb, ...) if(logLevel>=ZOO_LOG_LEVEL_ERROR) \
    log_message(_cb, ZOO_LOG_LEVEL_ERROR, __LINE__, __func__, __VA_ARGS__)
#define LOG_WARN(_cb, ...) if(logLevel>=ZOO_LOG_LEVEL_WARN) \
    log_message(_cb, ZOO_LOG_LEVEL_WARN, __LINE__, __func__, __VA_ARGS__)
#define LOG_INFO(_cb, ...) if(logLevel>=ZOO_LOG_LEVEL_INFO) \
    log_message(_cb, ZOO_LOG_LEVEL_INFO, __LINE__, __func__, __VA_ARGS__)
#define LOG_DEBUG(_cb, ...) if(logLevel==ZOO_LOG_LEVEL_DEBUG) \
    log_message(_cb, ZOO_LOG_LEVEL_DEBUG, __LINE__, __func__, __VA_ARGS__)

ZOOAPI void log_message(log_callback_fn callback, ZooLogLevel curLevel,
    int line, const char* funcName, const char* format, ...);

FILE* zoo_get_log_stream();

#ifdef __cplusplus
}
#endif

#endif /*ZK_LOG_H_*/
