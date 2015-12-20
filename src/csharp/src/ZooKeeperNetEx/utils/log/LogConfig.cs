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

using System.Collections.Generic;
using System.Diagnostics;

namespace org.apache.utils
{
    internal class LogConfig
    {
        public static readonly LogConfig Default = new LogConfig(null, null, null, null);
        public readonly TraceLevel LogLevel = TraceLevel.Warning;
        public readonly Dictionary<string, TraceLevel> LogOverrides = new Dictionary<string, TraceLevel>();
        public readonly bool LogToFile = true;
        public readonly bool LogToTrace = true;

        public LogConfig(bool? logToTrace, bool? logToFile, TraceLevel? logLevel, Dictionary<string, TraceLevel> logOverrides)
        {
            if (logToTrace != null) LogToTrace = (bool) logToTrace;
            if (logToFile != null) LogToFile = (bool) logToFile;
            if (logLevel != null) LogLevel = (TraceLevel) logLevel;
            if (logOverrides != null) LogOverrides = logOverrides;
        }
    }
}