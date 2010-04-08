using System;
using System.Collections.Generic;
using System.Text;

namespace MiscUtil
{
    /// <summary>
    /// Interface for classes which manage instances of
    /// IBuffer.
    /// </summary>
    public interface IBufferManager
    {
        /// <summary>
        /// Returns a buffer of the given size or greater.
        /// </summary>
        /// <param name="minimumSize">The minimum size of buffer to return</param>
        /// <exception cref="BufferAcquisitionException">This manager is unable
        /// to return a buffer of the appropriate size</exception>
        /// <exception cref="ArgumentOutOfRangeException">minimumSize is less than
        /// or equal to 0</exception>
        IBuffer GetBuffer(int minimumSize);
    }
}
