using System;
using System.Collections.Generic;
using System.Text;

namespace MiscUtil
{
    /// <summary>
    /// Type of buffer returned by CachingBufferManager.
    /// </summary>
    class CachedBuffer : IBuffer
    {
        readonly byte[] data;
        volatile bool available;
        readonly bool clearOnDispose;

        internal CachedBuffer(int size, bool clearOnDispose)
        {
            data = new byte[size];
            this.clearOnDispose = clearOnDispose;
        }

        internal bool Available
        {
            get { return available; }
            set { available = value; }
        }

        public byte[] Bytes
        {
            get { return data; }
        }

        public void Dispose()
        {
            if (clearOnDispose)
            {
                Array.Clear(data, 0, data.Length);
            }
            available = true;
        }
    }
}
