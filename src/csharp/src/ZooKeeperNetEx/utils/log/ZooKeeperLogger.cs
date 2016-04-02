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
    internal class ZooKeeperLogger
    {
        internal static readonly ZooKeeperLogger Instance = new ZooKeeperLogger();
        internal ILogConsumer CustomLogConsumer;
        internal TraceLevel LogLevel = TraceLevel.Warning;
        private readonly LogWriter logWriter = new LogWriter();
        internal bool LogToFile
        {
            get { return logWriter.LogToFile; }
            set { logWriter.LogToFile = value; }
        }

        internal bool LogToTrace
        {
            get { return logWriter.LogToTrace; }
            set { logWriter.LogToTrace = value; }
        }

        internal string LogFileName
        {
            get { return logWriter.FileName; }
        }

        internal void Log(TraceLevel sev, string className, string message, Exception exception = null)
        {
            if (sev > LogLevel)
            {
                return;
            }

            logWriter.Log(sev, className, message, exception);
            var logConsumer = CustomLogConsumer;
            if (logConsumer == null) return;
            try
            {
                logConsumer.Log(sev, className, message, exception);
            }
            catch (Exception e)
            {
                Trace.TraceError(
                    $@"Exception while passing a log message to log consumer. TraceLogger type:{logConsumer.GetType().FullName},
                       name:{className}, severity:{sev}, message:{message}, message exception:{exception}, log consumer exception:{e}");
            }
        }
    }
}