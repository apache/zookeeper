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

using System.Diagnostics;

namespace org.apache.utils
{
    /// <summary>
    ///     The Log Writer class is a convenient wrapper around the .Net Trace class.
    /// </summary>
    internal class LogWriterToTrace : LogWriterBase
    {
        /// <summary>Write the log message for this log.</summary>
        protected override void WriteLogMessage(string msg, TraceLevel severity)
        {
            switch (severity)
            {
                case TraceLevel.Off:
                    break;
                case TraceLevel.Error:
                    Trace.TraceError(msg);
                    break;
                case TraceLevel.Warning:
                    Trace.TraceWarning(msg);
                    break;
                case TraceLevel.Info:
                    Trace.TraceInformation(msg);
                    break;
                case TraceLevel.Verbose:
                    Trace.WriteLine(msg);
                    break;
            }
            Trace.Flush();
        }
    }
}