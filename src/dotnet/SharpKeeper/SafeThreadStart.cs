using System;

namespace SharpKeeper
{
    using log4net;

    public class SafeThreadStart
    {
        private readonly Action action;
        private static readonly ILog LOG = LogManager.GetLogger(typeof(SafeThreadStart));

        public SafeThreadStart(Action action)
        {
            this.action = action;
        }

        public void Run()
        {
            try
            {
                action();
            }
            catch (Exception e)
            {
                LOG.Error("Unhandled exception in background thread", e);
            }            
        }
    }
}