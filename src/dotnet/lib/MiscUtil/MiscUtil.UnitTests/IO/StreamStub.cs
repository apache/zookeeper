using System;
using System.IO;
using System.Collections.Generic;

namespace MiscUtil.UnitTests.IO
{
    /// <summary>
    /// Stubbed stream to allow easy testing of StreamUtil
    /// </summary>
    class StreamStub : Stream
    {
        Queue<byte[]> dataToProvide = new Queue<byte[]>();
        int lastReadSize;

        public int LastReadSize
        {
            get { return lastReadSize; }
            set { lastReadSize = value; }
        }

        public void AddReadData(byte[] data)
        {
            dataToProvide.Enqueue(data);
        }

        public override bool CanRead
        {
            get { throw new Exception("The method or operation is not implemented."); }
        }

        public override bool CanSeek
        {
            get { throw new Exception("The method or operation is not implemented."); }
        }

        public override bool CanWrite
        {
            get { throw new Exception("The method or operation is not implemented."); }
        }

        public override void Flush()
        {
            throw new Exception("The method or operation is not implemented.");
        }

        public override long Length
        {
            get { throw new Exception("The method or operation is not implemented."); }
        }

        public override long Position
        {
            get
            {
                throw new Exception("The method or operation is not implemented.");
            }
            set
            {
                throw new Exception("The method or operation is not implemented.");
            }
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (dataToProvide.Count==0)
            {
                return 0;
            }
            lastReadSize = count;
            byte[] data = dataToProvide.Dequeue();
            Array.Copy(data, 0, buffer, offset, data.Length);
            return data.Length;
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            throw new Exception("The method or operation is not implemented.");
        }

        public override void SetLength(long value)
        {
            throw new Exception("The method or operation is not implemented.");
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            throw new Exception("The method or operation is not implemented.");
        }
    }
}
