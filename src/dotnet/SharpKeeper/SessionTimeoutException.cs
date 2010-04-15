namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class SessionTimeoutException : IOException
    {
        public SessionTimeoutException(string msg) : base(msg)
        {
        }
    }
}