using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace org.apache.utils
{
    internal class NonBlockingFileWriter
    {
        private readonly StreamWriterWrapper logOutput;

        private readonly Fenced<bool> isEnabled = new Fenced<bool>(true);

        private readonly ThreadSafeInt pendingMessages = new ThreadSafeInt(0);

        internal bool IsDisposed => logOutput.IsDisposed;
        internal bool IsEmpty => logQueue.IsEmpty;

        internal bool HasFailed => logOutput.HasFailed;
        
        internal bool ThrowWrite
        {
            get { return logOutput.ThrowWrite.Value; }
            set { logOutput.ThrowWrite.Value = value; }
        }

        private readonly ConcurrentQueue<string> logQueue = new ConcurrentQueue<string>();

        public NonBlockingFileWriter(string filename)
        {
            logOutput = new StreamWriterWrapper(filename);
        }

        public void Write(string str)
        {
            if (!isEnabled.Value && logOutput.IsDisposed) return;
            logQueue.Enqueue(str);
            if (pendingMessages.Increment() == 1)
            {
                startLogTask();
            }
        }

        public bool IsEnabled
        {
            get { return isEnabled.Value; }
            set { isEnabled.Value = value; }
        }

        private async void startLogTask()
        {
            do
            {
                string msg;
                if (logQueue.TryDequeue(out msg))
                {
                    if (isEnabled.Value && !logOutput.HasFailed)
                    {
                        await logOutput.WriteAsync(msg).ConfigureAwait(false);
                    }
                    else logOutput.Dispose();
                }
            } while (pendingMessages.Decrement() > 0);
        }

        private class StreamWriterWrapper
        {
            private readonly string fileName;
            private readonly Fenced<bool> isDisposed = new Fenced<bool>(true);
            private readonly Fenced<bool> hasFailed = new Fenced<bool>(false);
            internal readonly Fenced<bool> ThrowWrite = new Fenced<bool>(false);
            private StreamWriter streamWriter;

            public StreamWriterWrapper(string filename)
            {
                fileName = filename;
            }

            public async Task WriteAsync(string msg)
            {
                try
                {
                    if (isDisposed.Value)
                    {
                        isDisposed.Value = false;
                        streamWriter = GetOrCreateLogFile();
                    }
                    if (ThrowWrite.Value)
                    {
                        throw new InvalidOperationException();
                    }
                    await streamWriter.WriteLineAsync(msg).ConfigureAwait(false);
                    await streamWriter.FlushAsync().ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    hasFailed.Value = true;
                    Trace.TraceError("Error while writing to log, will not try again. Exception:" + e);
                    Dispose();
                }
            }

            public void Dispose()
            {
                if (!isDisposed.Value)
                {
                    isDisposed.Value = true;
                    streamWriter.Dispose();
                }
            }

            public bool IsDisposed => isDisposed.Value;

            public bool HasFailed => hasFailed.Value;

            private StreamWriter GetOrCreateLogFile()
            {
                var logFile = new FileInfo(fileName);
                return logFile.Exists ? logFile.AppendText() : logFile.CreateText();
            }
        }
    }
}
