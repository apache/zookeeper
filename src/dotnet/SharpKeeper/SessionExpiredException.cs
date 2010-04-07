namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class SessionExpiredException : IOException
    {
        public SessionExpiredException(String msg) : base(msg)
        {
        }
    }
}