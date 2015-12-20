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
using System.Reflection;
using System.Text;

namespace org.apache.utils
{
    /// <summary>
    ///     The Log Writer base class provides default partial implementation suitable for most specific log writer.
    /// </summary>
    internal abstract class LogWriterBase : ILogConsumer
    {
        private static readonly string[] SEVERITY_TABLE = {"OFF  ", "ERROR  ", "WARNING", "INFO   ", "VERBOSE "};

        /// <summary>
        ///     The method to call during logging.
        ///     This method should be very fast, since it is called synchronously during logging.
        /// </summary>
        /// <param name="severity">The severity of the message being traced.</param>
        /// <param name="className">The name of the logger tracing the message.</param>
        /// <param name="message">The message to log.</param>
        /// <param name="exception">The exception to log. May be null.</param>
        public void Log(TraceLevel severity, string className, string message, Exception exception)
        {
            var exc = PrintException(exception);
            string msg = $"[{PrintDate()} \t{SEVERITY_TABLE[(int) severity]} \t{className} \t{message}] \t{exc}";

            WriteLogMessage(msg, severity);
        }

        /// <summary>
        ///     The method to call during logging to write the log message by this log.
        /// </summary>
        /// <param name="msg">Message string to be written</param>
        /// <param name="severity">The severity level of this message</param>
        protected abstract void WriteLogMessage(string msg, TraceLevel severity);

        /// <summary>
        ///     Utility function to convert a <c>DateTime</c> object into printable data format used by the TraceLogger subsystem.
        /// </summary>
        /// <returns>Formatted string representation of the input data, in the printable format used by the TraceLogger subsystem.</returns>
        private static string PrintDate()
        {
            // http://www.csharp-examples.net/string-format-datetime/
            // http://msdn.microsoft.com/en-us/library/system.globalization.datetimeformatinfo.aspx
            const string TIME_FORMAT = "HH:mm:ss.fff 'GMT'"; // Example: 09:50:43.341 GMT
            const string DATE_FORMAT = "yyyy-MM-dd " + TIME_FORMAT;
                // Example: 2010-09-02 09:50:43.341 GMT - Variant of UniversalSorta­bleDateTimePat­tern
            return DateTime.UtcNow.ToString(DATE_FORMAT);
        }

        /// <summary>
        ///     Utility function to convert an exception into printable format, including expanding and formatting any nested
        ///     sub-expressions.
        /// </summary>
        /// <param name="exception">The exception to be printed.</param>
        /// <returns>
        ///     Formatted string representation of the exception, including expanding and formatting any nested
        ///     sub-expressions.
        /// </returns>
        private static string PrintException(Exception exception)
        {
            return exception == null ? string.Empty : PrintException_Helper(exception, 0, true);
        }

        private static string PrintException_Helper(Exception exception, int level, bool includeStackTrace)
        {
            if (exception == null) return string.Empty;
            var sb = new StringBuilder();
            sb.Append(PrintOneException(exception, level, includeStackTrace));
            var reflectionException = exception as ReflectionTypeLoadException;
            if (reflectionException != null)
            {
                var loaderExceptions = reflectionException.LoaderExceptions;
                if (loaderExceptions == null || loaderExceptions.Length == 0)
                {
                    sb.Append("No LoaderExceptions found");
                }
                else
                {
                    foreach (var inner in loaderExceptions)
                    {
                        // call recursively on all loader exceptions. Same level for all.
                        sb.Append(PrintException_Helper(inner, level + 1, includeStackTrace));
                    }
                }
            }
            else
            {
                var aggregateException = exception as AggregateException;
                if (aggregateException != null)
                {
                    var innerExceptions = aggregateException.InnerExceptions;
                    if (innerExceptions == null) return sb.ToString();

                    foreach (var inner in innerExceptions)
                    {
                        // call recursively on all inner exceptions. Same level for all.
                        sb.Append(PrintException_Helper(inner, level + 1, includeStackTrace));
                    }
                }
                else if (exception.InnerException != null)
                {
                    // call recursively on a single inner exception.
                    sb.Append(PrintException_Helper(exception.InnerException, level + 1, includeStackTrace));
                }
            }
            return sb.ToString();
        }

        private static string PrintOneException(Exception exception, int level, bool includeStackTrace)
        {
            if (exception == null) return string.Empty;
            var stack = string.Empty;
            if (includeStackTrace && exception.StackTrace != null)
                stack = $"{Environment.NewLine}{exception.StackTrace}";

            var message = exception.Message;

            return $"{Environment.NewLine}Exc level {level}: {exception.GetType()}: {message}{stack}";
        }
    }
}