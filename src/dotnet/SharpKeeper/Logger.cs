namespace SharpKeeper
{
    using System;
    using System.IO;

    public class Logger
    {
        public static Logger getLogger(Type type)
        {
            return null;
        }

        public void Error(string msg)
        {
            throw new NotImplementedException();
        }

        public void Warn(string msg)
        {
            throw new NotImplementedException();
        }

        public void Warn(string msg, Exception ioException)
        {
            throw new NotImplementedException();
        }

        public void info(string s)
        {
            throw new NotImplementedException();
        }

        public bool isDebugEnabled()
        {
            throw new NotImplementedException();
        }

        public void debug(string closeCalledOnAlreadyClosedClient)
        {
            throw new NotImplementedException();
        }

        public void debug(string closeCalledOnAlreadyClosedClient, Exception ioException)
        {
            throw new NotImplementedException();
        }

        public void Error(string errorInEventThread, Exception exception)
        {
            throw new NotImplementedException();
        }
    }
}