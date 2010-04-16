namespace SharpKeeper
{
    using System.IO;

    internal class SessionExpiredException : IOException
    {
        public SessionExpiredException(string msg) : base(msg)
        {
        }
    }
}