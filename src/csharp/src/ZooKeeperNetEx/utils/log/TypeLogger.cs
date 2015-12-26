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
using System.Diagnostics;

namespace org.apache.utils
{
    internal class TypeLogger<T> : ILogProducer
    {
        public static readonly ILogProducer Instance = new TypeLogger<T>();

        private static readonly string className = typeof (T).Name;

        public void debugFormat(string format, params object[] args)
        {
            // avoids exceptions if format string contains braces in calls that were not
            // designed to use format strings
            var message = args == null || args.Length == 0 ? format : string.Format(format, args);

            log(TraceLevel.Verbose, message);
        }

        public void debug(object message, Exception e = null)
        {
            log(TraceLevel.Verbose, message, e);
        }

        public void warn(object message, Exception e = null)
        {
            log(TraceLevel.Warning, message, e);
        }

        public void info(object message, Exception e = null)
        {
            log(TraceLevel.Info, message, e);
        }

        public void error(object message, Exception e = null)
        {
            log(TraceLevel.Error, message, e);
        }

        public bool isDebugEnabled()
        {
            return ZooKeeperLogger.Instance.LogLevel == TraceLevel.Verbose;
        }

        private void log(TraceLevel traceLevel, object message, Exception e = null)
        {
            ZooKeeperLogger.Instance.Log(traceLevel, className, message.ToString(), e);
        }
    }
}