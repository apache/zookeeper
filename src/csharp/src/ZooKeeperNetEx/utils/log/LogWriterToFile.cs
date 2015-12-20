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
using System.Diagnostics;
using System.IO;
using System.Net;

namespace org.apache.utils
{
    /// <summary>
    ///     This Log Writer class is an Log Consumer wrapper class which writes to a specified log file.
    /// </summary>
    internal class LogWriterToFile : LogWriterBase
    {
        private readonly Fenced<bool> isValid = new Fenced<bool>(false);
        private readonly AsyncManualResetEvent logEvent = new AsyncManualResetEvent();
        private readonly StreamWriter logOutput;
        private readonly ConcurrentQueue<string> logQueue = new ConcurrentQueue<string>();

        public LogWriterToFile()
        {
            const string dateFormat = "yyyy-MM-dd-HH.mm.ss.fffZ";
            var fileName = string.Format("ZK.{0}.{1}.log", Dns.GetHostName(), DateTime.UtcNow.ToString(dateFormat));

            try
            {
                var logFile = new FileInfo(fileName);
                logOutput = logFile.Exists ? logFile.AppendText() : logFile.CreateText();
                isValid.Value = true;
            }
            catch (Exception e)
            {
                Trace.TraceError("Unable to open log file, will not try again. Exception:" + e);
            }
            startLogTask();
        }

        /// <summary>Write the log message for this log.</summary>
        protected override void WriteLogMessage(string msg, TraceLevel severity)
        {
            if (isValid.Value)
            {
                logQueue.Enqueue(msg);
                logEvent.Set();
            }
        }

        private async void startLogTask()
        {
            try
            {
                while (true)
                {
                    await logEvent.WaitAsync().ConfigureAwait(false);
                    logEvent.Reset();
                    
                    string msg;
                    while (logQueue.TryDequeue(out msg))
                    {
                        await logOutput.WriteLineAsync(msg).ConfigureAwait(false);
                        await logOutput.FlushAsync().ConfigureAwait(false);
                    }
                }
            }
            catch (Exception e)
            {
                isValid.Value = false;
                logOutput.Dispose();
                Trace.TraceError("Error while writing to log, will not try again. Exception:" + e);
            }
        }
    }
}