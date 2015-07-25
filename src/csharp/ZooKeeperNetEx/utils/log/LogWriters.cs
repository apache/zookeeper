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
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace org.apache.utils
{
    /// <summary>
    /// The Log Writer base class provides default partial implementation suitable for most specific log writer.
    /// </summary>
    internal abstract class LogWriterBase {
        /// <summary>
        /// The method to call during logging.
        /// This method should be very fast, since it is called synchronously during logging.
        /// </summary>
        /// <remarks>
        /// To customize functionality in a log writter derived from this base class, 
        /// you should override the <c>FormatLogMessage</c> and/or <c>WriteLogMessage</c> 
        /// methods rather than overriding this method directly.
        /// </remarks>
        /// <seealso cref="WriteLogMessage"/>
        /// <param name="severity">The severity of the message being traced.</param>
        /// <param name="caller">The name of the logger tracing the message.</param>
        /// <param name="message">The message to log.</param>
        /// <param name="exception">The exception to log. May be null.</param>
        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
        public void Log(TraceLevel severity, string caller, string message, Exception exception) 
        {
            string exc = TraceLogger.PrintException(exception);
            string msg1 = string.Format(CultureInfo.InvariantCulture,"[{0} {1,5}\t{2}\t{3}\t{4}]\t{5}",
                TraceLogger.PrintDate(DateTime.UtcNow),            //0
                Thread.CurrentThread.ManagedThreadId,   //1
                TraceLogger.SeverityTable[(int)severity],    //2
                caller,                                 //3
                message,                                //4
                exc);      //5
            var msg = msg1;

            try
            {
                WriteLogMessage(msg, severity);
            }
            catch (Exception e)
            {
                Trace.TraceError("Error writing log message {0} -- Exception={1}", msg, e);
            }
        }

        /// <summary>
        /// The method to call during logging to write the log message by this log.
        /// </summary>
        /// <param name="msg">Message string to be writter</param>
        /// <param name="severity">The severity level of this message</param>
        protected abstract void WriteLogMessage(string msg, TraceLevel severity);
    }

    /// <summary>
    /// The Log Writer class is a convenient wrapper around the .Net Trace class.
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

    /// <summary>
    /// This Log Writer class is an Log Consumer wrapper class which writes to a specified log file.
    /// </summary>
    internal class LogWriterToFile : LogWriterBase
    {
        private readonly StreamWriter logOutput;
        private readonly AsyncManualResetEvent logEvent = new AsyncManualResetEvent();
        private readonly Task logTask;
        private readonly ConcurrentQueue<string> logQueue = new ConcurrentQueue<string>();

        /// <summary>
        /// Constructor, specifying the file to send output to.
        /// </summary>
        /// <param name="logFile">The log file to be written to.</param>
        public LogWriterToFile(FileInfo logFile)
        {
            bool fileExists = File.Exists(logFile.FullName);
            logOutput = fileExists ? logFile.AppendText() : logFile.CreateText();
            logFile.Refresh(); // Refresh the cached view of FileInfo
            logTask = startLogTask();
        }

        /// <summary>Write the log message for this log.</summary>
        protected override void WriteLogMessage(string msg, TraceLevel severity) 
        {
            logQueue.Enqueue(msg);
            logEvent.Set();
        }

        private async Task startLogTask() 
        {
            while (true) 
            {
                await logEvent.WaitAsync().ConfigureAwait(false);
                logEvent.Reset();
                string msg;
                while (logQueue.TryDequeue(out msg)) 
                {
                    await logOutput.WriteLineAsync(msg).ConfigureAwait(false);
                }
            }
        }
    }
}
