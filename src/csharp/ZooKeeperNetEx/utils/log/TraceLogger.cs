/*
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and 
associated documentation files (the ""Software""), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

namespace org.apache.utils
{
    /// <summary>
    /// The TraceLogger class is a convenient wrapper around the .Net Trace class.
    /// It provides more flexible configuration than the Trace class.
    /// </summary>
    public class TraceLogger
    {
        internal static readonly string[] SeverityTable = { "OFF  ", "ERROR  ", "WARNING", "INFO   ", "VERBOSE "};

        private static TraceLevel traceLevel = TraceLevel.Warning;
        private static FileInfo logOutputFile;

        /// <summary>
        /// The set of <see cref="LogWriterBase"/> references to write log events to. 
        /// If any .NET trace listeners are defined in app.config, then <see cref="LogWriterToTrace"/> 
        /// is automatically added to this list to forward the log output to those trace listeners.
        /// </summary>
        private static readonly ConcurrentBag<LogWriterBase> LogConsumers = new ConcurrentBag<LogWriterBase>();

        // http://www.csharp-examples.net/string-format-datetime/
        // http://msdn.microsoft.com/en-us/library/system.globalization.datetimeformatinfo.aspx
        private const string TIME_FORMAT = "HH:mm:ss.fff 'GMT'"; // Example: 09:50:43.341 GMT
        private const string DATE_FORMAT = "yyyy-MM-dd " + TIME_FORMAT; // Example: 2010-09-02 09:50:43.341 GMT - Variant of UniversalSorta­bleDateTimePat­tern

        private TraceLevel severity;
        private bool useCustomSeverityLevel;

        private readonly string logName;
        private static readonly object[] emptyObjectsArray = new object[0];
        private static readonly object lockable = new object();

        private static readonly List<Tuple<string, TraceLevel>> traceLevelOverrides = new List<Tuple<string, TraceLevel>>();

        private static readonly ConcurrentDictionary<string, TraceLogger> loggerCache = new ConcurrentDictionary<string, TraceLogger>();
      
        /// <summary>
        /// The current severity level for this TraceLogger.
        /// Log entries will be written if their severity is (logically) equal to or greater than this level.
        /// If it is not explicitly set, then a default value will be calculated based on the logger's type and name.
        /// Note that changes to the global default settings will be propagated to existing loggers that are using the default severity.
        /// </summary>
        private TraceLevel SeverityLevel
        {
            get
            {
                if (useCustomSeverityLevel) return severity;

                severity = GetDefaultSeverityForLog(logName);
                return severity;
            }
        }

        public void debugFormat(string format, params object[] args) 
        {
            Log(TraceLevel.Verbose, format, args, null);
        }

        public void debug(object message, Exception e = null) 
        {
            Log(TraceLevel.Verbose, message.ToString(), emptyObjectsArray, e);
        }

        public void warn(object message, Exception e = null) 
        {
            Log(TraceLevel.Warning, message.ToString(), emptyObjectsArray, e);
        }

        public void info(object message, Exception e = null) 
        {
            Log(TraceLevel.Info, message.ToString(), emptyObjectsArray, e);
        }

        public void error(object message, Exception e = null) {
            Log(TraceLevel.Error, message.ToString(), emptyObjectsArray, e);
        }

        public bool isDebugEnabled()
        {
            return SeverityLevel >= TraceLevel.Verbose;
        }
        
        /// <summary>
        /// Constructs a TraceLogger with the given name and type.
        /// </summary>
        /// <param name="source">The name of the source of log entries for this TraceLogger.
        /// Typically this is the full name of the class that is using this TraceLogger.</param>
        private TraceLogger(string source)
        {
            logName = source;
            useCustomSeverityLevel = CheckForSeverityOverride();
        }

        /// <summary>
        /// Whether the TraceLogger infrastructure has been previously initialized.
        /// </summary>
        private static bool IsInitialized;

        #pragma warning disable 1574
        /// <summary>
        /// Initialize the TraceLogger subsystem in this process / app domain with the specified configuration settings.
        /// </summary>
        /// <param name="config">Configuration settings to be used for initializing the TraceLogger susbystem state.</param>
        #pragma warning restore 1574
        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
        public static void Initialize(ZooKeeperConfiguration config) 
        {
            if (config == null) config = new ZooKeeperConfiguration();

            lock (lockable)
            {
                if (IsInitialized) return; // Already initialized
                
                traceLevel = config.DefaultTraceLevel;
                SetTraceLevelOverrides(config.TraceLevelOverrides);

                if (!string.IsNullOrEmpty(config.TraceFileName))
                {
                    try
                    {
                        logOutputFile = new FileInfo(config.TraceFileName);
                        var l = new LogWriterToFile(logOutputFile);
                        LogConsumers.Add(l);
                    }
                    catch (Exception exc)
                    {
                        Trace.Listeners.Add(new DefaultTraceListener());
                        Trace.TraceError("Error opening trace file {0} -- Using DefaultTraceListener instead -- Exception={1}", logOutputFile, exc);
                    }
                }

                if (Trace.Listeners.Count > 0)
                {
                    // Plumb in log consumer to write to Trace listeners
                    var traceLogConsumer = new LogWriterToTrace();
                    LogConsumers.Add(traceLogConsumer);
                }

                IsInitialized = true;
            }
        }

        private static TraceLevel GetDefaultSeverityForLog(string source)
        {
            lock (lockable)
            {
                if (traceLevelOverrides.Count > 0)
                {
                    foreach (var o in traceLevelOverrides)
                    {
                        if (source.StartsWith(o.Item1))
                        {
                            return o.Item2;
                        }
                    }
                }
            }

            return traceLevel;
        }

        private bool CheckForSeverityOverride()
        {
            lock (lockable)
            {
                if (traceLevelOverrides.Count <= 0) return false;

                foreach (var o in traceLevelOverrides)
                {
                    if (!logName.StartsWith(o.Item1, StringComparison.Ordinal)) continue;

                    severity = o.Item2;
                    useCustomSeverityLevel = true;
                    return true;
                }
            }
            return false;
        }
        
        /// <summary>
        /// Find existing or create new TraceLogger with the specified name
        /// </summary>
        /// <param name="loggerName">Name of the TraceLogger to find</param>
        /// <returns>TraceLogger associated with the specified name</returns>
        private static TraceLogger GetLogger(string loggerName) 
        {
            return loggerCache.GetOrAdd(loggerName, new TraceLogger(loggerName));
        }

        internal static TraceLogger GetLogger(Type loggedType) 
        {
            return GetLogger(loggedType.Name);
        }
        
        /// <summary>
        /// Set new trace level overrides for particular loggers, beyond the default log levels.
        /// Any previous trace levels for particular TraceLogger's will be discarded.
        /// </summary>
        /// <param name="overrides">The new set of log level overrided to use.</param>
        private static void SetTraceLevelOverrides(IList<Tuple<string, TraceLevel>> overrides)
        {
            List<TraceLogger> loggers;
            lock (lockable)
            {
                traceLevelOverrides.Clear();
                traceLevelOverrides.AddRange(overrides);
                if (traceLevelOverrides.Count > 0)
                {
                    traceLevelOverrides.Sort(new TraceOverrideComparer());
                }
                loggers =  new List<TraceLogger>(loggerCache.Values);
            }
            foreach (var logger in loggers)
            {
                logger.CheckForSeverityOverride();
            }
        }

        private void Log(TraceLevel sev, string format, object[] args, Exception exception)
        {
            if (sev > SeverityLevel)
            {
                return;
            }
            WriteLogMessage(sev, format, args, exception);
        }

        private static string FormatMessageText(string format, object[] args)
        {
            // avoids exceptions if format string contains braces in calls that were not
            // designed to use format strings
            return (args == null || args.Length == 0) ? format : String.Format(format, args);
        }

        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
        private void WriteLogMessage(TraceLevel sev, string format, object[] args, Exception exception)
        {
            string message = FormatMessageText(format, args);

            foreach (LogWriterBase consumer in LogConsumers)
            {
                try
                {
                    consumer.Log(sev, logName, message, exception);
                }
                catch (Exception exc)
                {
                    Console.WriteLine("Exception while passing a log message to log consumer. TraceLogger type:{0}, name:{1}, severity:{2}, message:{3}, message exception:{4}, log consumer exception:{5}",
                        consumer.GetType().FullName, logName, sev, message, exception, exc);
                }
            }
        }

        /// <summary>
        /// Utility function to convert a <c>DateTime</c> object into printable data format used by the TraceLogger subsystem.
        /// </summary>
        /// <returns>Formatted string representation of the input data, in the printable format used by the TraceLogger subsystem.</returns>
        public static string PrintDate(DateTime date)
        {
            return date.ToString(DATE_FORMAT, CultureInfo.InvariantCulture);
        }
        
        /// <summary>
        /// Utility function to convert an exception into printable format, including expanding and formatting any nested sub-expressions.
        /// </summary>
        /// <param name="exception">The exception to be printed.</param>
        /// <returns>Formatted string representation of the exception, including expanding and formatting any nested sub-expressions.</returns>
        public static string PrintException(Exception exception)
        {
            return exception == null ? String.Empty : PrintException_Helper(exception, 0, true);
        }
        
        private static string PrintException_Helper(Exception exception, int level, bool includeStackTrace)
        {
            if (exception == null) return String.Empty;
            var sb = new StringBuilder();
            sb.Append(PrintOneException(exception, level, includeStackTrace));
            if (exception is ReflectionTypeLoadException)
            {
                Exception[] loaderExceptions =
                    ((ReflectionTypeLoadException)exception).LoaderExceptions;
                if (loaderExceptions == null || loaderExceptions.Length == 0)
                {
                    sb.Append("No LoaderExceptions found");
                }
                else
                {
                    foreach (Exception inner in loaderExceptions)
                    {
                        // call recursively on all loader exceptions. Same level for all.
                        sb.Append(PrintException_Helper(inner, level + 1, includeStackTrace));
                    }
                }
            }
            else if (exception is AggregateException)
            {
                var innerExceptions = ((AggregateException)exception).InnerExceptions;
                if (innerExceptions == null) return sb.ToString();

                foreach (Exception inner in innerExceptions)
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
            return sb.ToString();
        }

        private static string PrintOneException(Exception exception, int level, bool includeStackTrace)
        {
            if (exception == null) return String.Empty;
            string stack = String.Empty;
            if (includeStackTrace && exception.StackTrace != null)
                stack = String.Format(Environment.NewLine + exception.StackTrace);
            
            string message = exception.Message;
            
            return String.Format(Environment.NewLine + "Exc level {0}: {1}: {2}{3}",
                level,
                ((object)exception).GetType(),
                message,
                stack);
        }
        
        /// <summary>
        /// This custom comparer lets us sort the TraceLevelOverrides list so that the longest prefix comes first
        /// </summary>
        private class TraceOverrideComparer : Comparer<Tuple<string, TraceLevel>>
        {
            public override int Compare(Tuple<string, TraceLevel> x, Tuple<string, TraceLevel> y)
            {
                return y.Item1.Length.CompareTo(x.Item1.Length);
            }
        }
    }
}
