namespace SharpKeeper
{
    using System;
    using System.Threading;

    public static class ZooThreadPool
    {
        public static bool QueueUserWorkItem(Logger logger, Action workItem)
        {
            return ThreadPool.QueueUserWorkItem(state =>
            {
                try
                {
                    workItem();
                }
                catch(ThreadInterruptedException e)
                {
                    return;
                } 
                catch(Exception e)
                {
                    logger.Error("Exception occurred while processing", e);    
                }
            });
        }
    }
}
