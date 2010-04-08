using System;
using System.IO;
using System.Collections.Generic;
using System.Text;
using NUnit.Framework;
using MiscUtil.IO;

namespace MiscUtil.UnitTests.IO
{
    [TestFixture]
    public class StreamUtilTest
    {
        #region Copy
        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void CopyNullInput()
        {
            StreamUtil.Copy(null, new MemoryStream());
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void CopyNullOutput()
        {
            StreamUtil.Copy(new MemoryStream(), null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void CopyZeroBufferSize()
        {
            StreamUtil.Copy(new MemoryStream(), new MemoryStream(), 0);
        }

        [Test]
        [ExpectedException(typeof(ArgumentException))]
        public void CopyZeroLengthBuffer()
        {
            StreamUtil.Copy(new MemoryStream(), new MemoryStream(), new byte[0]);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void CopyNullByteBuffer()
        {
            StreamUtil.Copy(new MemoryStream(), new MemoryStream(), null as byte[]);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void CopyNullIBuffer()
        {
            StreamUtil.Copy(new MemoryStream(), new MemoryStream(), null as IBuffer);
        }

        [Test]
        public void CopyWithDataDefaults()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[]{1, 2, 3, 4, 5});
            input.AddReadData(new byte[]{6, 7, 8, 9, 10});
            MemoryStream output = new MemoryStream();
            StreamUtil.Copy(input, output);
            Assert.AreEqual(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, output.ToArray());
            Assert.AreEqual(8*1024, input.LastReadSize);
        }

        [Test]
        public void CopyWithBufferSize()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            MemoryStream output = new MemoryStream();
            StreamUtil.Copy(input, output, 6);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, output.ToArray());
            Assert.AreEqual(6, input.LastReadSize);
        }

        [Test]
        public void CopyWithIBuffer()
        {
            CachingBufferManager.Options options = new CachingBufferManager.Options();
            options.MinBufferSize = 7;
            CachingBufferManager manager = new CachingBufferManager(options);

            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            MemoryStream output = new MemoryStream();
            using (IBuffer buffer = manager.GetBuffer(10))
            {
                StreamUtil.Copy(input, output, buffer);
            }
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, output.ToArray());
            Assert.AreEqual(14, input.LastReadSize);
        }

        [Test]
        public void CopyWithByteBuffer()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            MemoryStream output = new MemoryStream();
            StreamUtil.Copy(input, output, new byte[8]);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, output.ToArray());
            Assert.AreEqual(8, input.LastReadSize);
        }
        #endregion

        #region ReadExactly
        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void ReadExactlyNullInput()
        {
            StreamUtil.ReadExactly(null, 10);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void ReadExactlyZeroBytesToRead()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            StreamUtil.ReadExactly(input, 0);
        }

        [Test]
        public void ReadExactlyExactlyRightAmountOfData()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual = StreamUtil.ReadExactly(input, 10);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);            
        }

        [Test]
        [ExpectedException(typeof(EndOfStreamException))]
        public void ReadExactlyNotEnoughData()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual = StreamUtil.ReadExactly(input, 11);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);
        }

        [Test]
        public void ReadExactlyExcessData()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9 });
            input.AddReadData(new byte[] { 10, 11, 12, 13, 14 });
            byte[] actual = StreamUtil.ReadExactly(input, 9);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, actual);
            Assert.AreEqual(4, input.LastReadSize);
        }
        #endregion

        #region ReadFully
        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void ReadFullyNullInput()
        {
            StreamUtil.ReadFully(null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void ReadFullyZeroBufferSize()
        {
            StreamUtil.ReadFully(new MemoryStream(), 0);
        }

        [Test]
        [ExpectedException(typeof(ArgumentException))]
        public void ReadFullyZeroLengthBuffer()
        {
            StreamUtil.ReadFully(new MemoryStream(), new byte[0]);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void ReadFullyNullByteBuffer()
        {
            StreamUtil.ReadFully(new MemoryStream(), null as byte[]);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void ReadFullyNullIBuffer()
        {
            StreamUtil.ReadFully(new MemoryStream(), null as IBuffer);
        }

        [Test]
        public void ReadFullyWithDataDefaults()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual = StreamUtil.ReadFully(input);
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);
            Assert.AreEqual(8*1024, input.LastReadSize);
        }

        [Test]
        public void ReadFullyWithBufferSize()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual = StreamUtil.ReadFully(input, 6); 
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);
            Assert.AreEqual(6, input.LastReadSize);
        }

        [Test]
        public void ReadFullyWithIBuffer()
        {
            CachingBufferManager.Options options = new CachingBufferManager.Options();
            options.MinBufferSize = 7;
            CachingBufferManager manager = new CachingBufferManager(options);

            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual;
            using (IBuffer buffer = manager.GetBuffer(10))
            {
                actual = StreamUtil.ReadFully(input, buffer);
            }
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);
            Assert.AreEqual(14, input.LastReadSize);
        }

        [Test]
        public void ReadFullyWithByteBuffer()
        {
            StreamStub input = new StreamStub();
            input.AddReadData(new byte[] { 1, 2, 3, 4, 5 });
            input.AddReadData(new byte[] { 6, 7, 8, 9, 10 });
            byte[] actual = StreamUtil.ReadFully(input, new byte[8]); 
            Assert.AreEqual(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, actual);
            Assert.AreEqual(8, input.LastReadSize);
        }

        [Test]
        public void ReadFullyWithOutCopying()
        {
            StreamStub input = new StreamStub();

            // The memory stream will expand to 256 bytes by default
            byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
            for (int i=0; i < 32; i++)
            {
                input.AddReadData(data);
            }
            byte[] actual = StreamUtil.ReadFully(input, new byte[8]);
            Assert.AreEqual(256, actual.Length);
            for (int i=0; i < 256; i++)
            {
                Assert.AreEqual (i%8, actual[i]);
            }
            Assert.AreEqual(8, input.LastReadSize);
        }
        #endregion
    }
}
