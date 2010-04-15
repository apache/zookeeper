namespace SharpKeeper
{
    using System;
    using System.IO;

    internal class EndOfStreamException : IOException
    {
        public EndOfStreamException(string msg) : base(msg)
        {
        }

        public override string ToString()
        {
            return "EndOfStreamException: " + Message;
        }
    }
}