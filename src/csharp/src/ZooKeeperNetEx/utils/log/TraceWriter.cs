using System.Diagnostics;

namespace org.apache.utils
{
    internal static class TraceWriter
    {
        public static bool LogToTrace = true;
        public static void Write(string msg, TraceLevel traceLevel)
        {
            if (!LogToTrace) return;
            switch (traceLevel)
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
