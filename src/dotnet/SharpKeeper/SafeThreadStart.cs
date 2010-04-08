using System;

namespace SharpKeeper
{
    public class SafeThreadStart
    {
        private readonly Action action;
        private static readonly Logger LOG = Logger.getLogger(typeof(SafeThreadStart));

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