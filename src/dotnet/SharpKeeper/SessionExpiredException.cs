namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class SessionExpiredException : IOException
    {
        public SessionExpiredException(string msg) : base(msg)
        {
        }
    }
}