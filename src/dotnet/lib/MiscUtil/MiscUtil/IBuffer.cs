using System;

namespace MiscUtil
{
    /// <summary>
    /// Interface encapsulating a byte array which may be managed
    /// by an IBufferManager implementation. When the buffer is
    /// disposed, some implementations of this interface may
    /// return the buffer to the IBufferManager which created
    /// it. Note that an IBuffer *must* always be disposed after use,
    /// or some implementations may leak memory. The buffer must
    /// not be used after being disposed, likewise the byte array must
    /// not be used after the buffer is disposed.
    /// </summary>
    public interface IBuffer : IDisposable
    {
        /// <summary>
        /// Returns the byte array encapsulated by this buffer.
        /// Note that depending on the buffer manager providing
        /// the buffer, the array may or may not be cleared (i.e.
        /// with every byte 0) to start with.
        /// </summary>
        byte[] Bytes { get; }
    }
}
