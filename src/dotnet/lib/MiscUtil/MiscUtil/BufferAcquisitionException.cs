using System;
using System.Collections.Generic;
using System.Text;

namespace MiscUtil
{
    /// <summary>
    /// Exception thrown to indicate that a buffer of the
    /// desired size cannot be acquired.
    /// </summary>
    public class BufferAcquisitionException : Exception
    {
        /// <summary>
        /// Creates an instance of this class with the given message.
        /// </summary>
        public BufferAcquisitionException(string message)
            : base(message)
        {
            // No-op
        }
    }
}
