namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class EndOfStreamException : IOException
    {
        public EndOfStreamException(String msg) : base(msg)
        {
        }

        public String toString()
        {
            return "EndOfStreamException: " + Message;
        }
    }
}