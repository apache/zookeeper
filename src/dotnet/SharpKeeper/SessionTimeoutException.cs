namespace SharpKeeper
{
    using System.IO;

    internal class SessionTimeoutException : IOException
    {
        public SessionTimeoutException(string msg) : base(msg)
        {
        }
    }
}