namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class SessionTimeoutException : IOException
    {
        public SessionTimeoutException(String msg) : base(msg)
        {
        }
    }
}