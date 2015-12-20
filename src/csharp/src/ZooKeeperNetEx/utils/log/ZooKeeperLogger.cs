/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All rights reserved.
 * 
 */

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using org.apache.utils.log;

namespace org.apache.utils
{
    internal class ZooKeeperLogger
    {
        internal static readonly ZooKeeperLogger Instance = new ZooKeeperLogger();
        private readonly ConcurrentBag<ILogConsumer> logConsumers = new ConcurrentBag<ILogConsumer>();
        private readonly Dictionary<string, TraceLevel> logOverrides;
        private readonly TraceLevel defaultLogLevel;

        private ZooKeeperLogger()
        {
            var logConfig = LogConfigLoader.LoadFromFileOrDefault();

            if (logConfig.LogToFile) logConsumers.Add(new LogWriterToFile());
            if (logConfig.LogToTrace) logConsumers.Add(new LogWriterToTrace());
            defaultLogLevel = logConfig.LogLevel;

            logOverrides = logConfig.LogOverrides.ToDictionary(x => x.ClassName, x => x.LogLevel);
        }

        internal TraceLevel GetLogLevel(string className)
        {
            TraceLevel logLevel;
            return logOverrides.TryGetValue(className, out logLevel) ? logLevel : defaultLogLevel;
        }

        internal void AddLogConsumer(ILogConsumer logConsumer)
        {
            logConsumers.Add(logConsumer);
        }
        
        internal void Log(TraceLevel sev, string className, string message, Exception exception = null)
        {
            if (sev > GetLogLevel(className))
            {
                return;
            }

            foreach (var consumer in logConsumers)
            {
                try
                {
                    consumer.Log(sev, className, message, exception);
                }
                catch (Exception e)
                {
                    Trace.TraceError(
                        $@"Exception while passing a log message to log consumer. TraceLogger 
                                    type:{
                            consumer.GetType().FullName}, name:{className}, severity:{sev
                            }, 
                                    message:{message}, message exception:{exception
                            }, 
                                    log consumer exception:{e}");
                }
            }
        }
    }
}