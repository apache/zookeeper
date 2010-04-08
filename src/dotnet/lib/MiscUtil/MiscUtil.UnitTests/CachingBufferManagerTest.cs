using System;
using System.Collections.Generic;
using System.Text;
using NUnit.Framework;

namespace MiscUtil.UnitTests
{
    [TestFixture]
    public class CachingBufferManagerTest
    {
        CachingBufferManager.Options options;
        CachingBufferManager subject;

        [SetUp]
        public void SetUp()
        {
            options = new CachingBufferManager.Options();
            subject = null;
        }

        [Test]
        public void ParameterLessConstructorUsesDefaultOptions()
        {
            subject = new CachingBufferManager();
            // Just test a single (easy to test!) property
            Assert.AreEqual(options.MinBufferSize, subject.GetBuffer(0).Bytes.Length);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void NonPositiveSizeRequestThrowsException()
        {
            subject = new CachingBufferManager(options);
            subject.GetBuffer(-1);
        }

        [Test]
        public void BufferOfExactSizeReturnedWhereAppropriate()
        {
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(2048))
            {
                Assert.AreEqual(2048, buffer.Bytes.Length);
            }
        }

        [Test]
        public void BufferOfLargerSizeReturnedWhereAppropriate()
        {
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(2049))
            {
                Assert.AreEqual(4096, buffer.Bytes.Length);
            }
        }

        [Test]
        public void BufferIsReusedAfterDispose()
        {
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(2049);
            buffer1.Dispose();
            IBuffer buffer2 = subject.GetBuffer(2050);
            Assert.AreSame(buffer1, buffer2);
        }

        [Test]
        public void BufferIsNotReusedWithoutDispose()
        {
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(2049);
            IBuffer buffer2 = subject.GetBuffer(2050);
            Assert.AreNotSame(buffer1, buffer2);
        }

        [Test]
        public void SmallScalingFactorAndMinBufferSizeWork()
        {
            // This could result in an infinite loop with a naive implementation!
            options.MinBufferSize = 1;
            options.ScalingFactor = 1.25;

            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(7))
            {
                Assert.IsTrue(buffer.Bytes.Length >= 7);
            }
        }

        [Test]
        public void MinBufferSizeHonoured()
        {
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(1))
            {
                Assert.IsTrue(buffer.Bytes.Length == options.MinBufferSize);
            }
        }

        [Test]
        public void MaxBufferSizeHonoured()
        {
            options.MaxBufferSize = 2000;
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(1025))
            {
                Assert.IsTrue(buffer.Bytes.Length == 2000);
            }
        }

        [Test]
        [ExpectedException(typeof(BufferAcquisitionException))]
        public void OverlyLargeRequestsRejected()
        {
            options.MaxBufferSize = 2000;
            subject = new CachingBufferManager(options);

            subject.GetBuffer(3000);
        }

        [Test]
        public void ReturnUncachedAction()
        {
            options.MaxBuffersPerSizeBand = 2;
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.ReturnUncached;
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(3000);
            IBuffer buffer2 = subject.GetBuffer(3000);
            IBuffer buffer3 = subject.GetBuffer(3000);

            Assert.AreNotSame(buffer1, buffer2);
            Assert.AreNotSame(buffer2, buffer3);
            Assert.AreNotSame(buffer1, buffer3);

            // Uncached buffer will be exactly the right size
            Assert.AreEqual(3000, buffer3.Bytes.Length);
        }

        [Test]
        public void ThrowExceptionAction()
        {
            options.MaxBuffersPerSizeBand = 2;
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.ThrowException;
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(3000);
            IBuffer buffer2 = subject.GetBuffer(3000);

            try
            {
                subject.GetBuffer(3000);
            }
            catch (BufferAcquisitionException)
            {
                // Expected
            }
        }

        [Test]
        public void UseBiggerActionReusingExisting()
        {
            options.MaxBuffersPerSizeBand = 2;
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.UseBigger;
            subject = new CachingBufferManager(options);

            // This buffer will be used later on
            IBuffer bigger = subject.GetBuffer(4098);
            bigger.Dispose();

            IBuffer buffer1 = subject.GetBuffer(3000);
            IBuffer buffer2 = subject.GetBuffer(3000);

            IBuffer buffer3 = subject.GetBuffer(3000);

            Assert.AreSame(bigger, buffer3);
        }

        [Test]
        public void UseBiggerActionForcingNew()
        {
            options.MaxBuffersPerSizeBand = 2;
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.UseBigger;
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(3000);
            IBuffer buffer2 = subject.GetBuffer(3000);

            IBuffer buffer3 = subject.GetBuffer(3000);
            Assert.AreEqual(8192, buffer3.Bytes.Length);
        }

        [Test]
        public void UseBiggerActionNoBiggerAvailable()
        {
            options.MaxBuffersPerSizeBand = 2;
            options.MaxBufferSize = 4096;
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.UseBigger;
            subject = new CachingBufferManager(options);

            IBuffer buffer1 = subject.GetBuffer(3000);
            IBuffer buffer2 = subject.GetBuffer(3000);

            // Fails over to returning an uncached version
            IBuffer buffer3 = subject.GetBuffer(3000);
            Assert.AreEqual(3000, buffer3.Bytes.Length);
        }

        [Test]
        public void ClearAfterUseHonoured()
        {
            options.ClearAfterUse=true;
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(10))
            {
                buffer.Bytes[0] = 1;
            }
            using (IBuffer buffer = subject.GetBuffer(10))
            {
                Assert.AreEqual(0, buffer.Bytes[0]);
            }
        }

        [Test]
        public void DoNotClearAfterUseHonoured()
        {
            options.ClearAfterUse = false;
            subject = new CachingBufferManager(options);

            using (IBuffer buffer = subject.GetBuffer(10))
            {
                buffer.Bytes[0] = 1;
            }
            using (IBuffer buffer = subject.GetBuffer(10))
            {
                Assert.AreEqual(1, buffer.Bytes[0]);
            }
        }


        #region Options tests
        [Test]
        public void MaxBufferSizePropertyValid()
        {
            options.MaxBufferSize = 5;
            Assert.AreEqual(5, options.MaxBufferSize);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void MaxBufferSizePropertyCannotBeZero()
        {
            options.MaxBufferSize = 0;
        }

        [Test]
        public void MinBufferSizePropertyValid()
        {
            options.MinBufferSize = 7;
            Assert.AreEqual(7, options.MinBufferSize);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void MinBufferSizePropertyCannotBeZero()
        {
            options.MinBufferSize = 0;
        }

        [Test]
        public void ClearAfterUsePropertyChange()
        {
            options.ClearAfterUse=false;
            Assert.IsFalse(options.ClearAfterUse);
            options.ClearAfterUse=true;
            Assert.IsTrue(options.ClearAfterUse);
        }

        [Test]
        public void MaxBuffersPerSizeBandPropertyValid()
        {
            options.MaxBuffersPerSizeBand = 3;
            Assert.AreEqual(3, options.MaxBuffersPerSizeBand);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void MaxBuffersPerSizeBandCannotBeZero()
        {
            options.MaxBuffersPerSizeBand = 0;
        }

        [Test]
        public void ScalingFactorPropertyValid()
        {
            double d = 2.34;
            options.ScalingFactor = d;
            Assert.AreEqual(d, options.ScalingFactor, 0.001);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void ScalingFactorCannotBeTooLow()
        {
            options.ScalingFactor = 1.24;
        }

        [Test]
        [ExpectedException(typeof(ArgumentException))]
        public void MinBufferSizeCannotBeMoreThanMaxBufferSizeOnManagerConstruction()
        {
            options.MinBufferSize = 10;
            options.MaxBufferSize = 9;
            new CachingBufferManager(options);
        }

        [Test]
        public void MinBufferSizeCanEqualMaxBufferSizeOnManagerConstruction()
        {
            options.MinBufferSize = 10;
            options.MaxBufferSize = 10;
            new CachingBufferManager(options);
        }

        [Test]
        public void ActionOnBufferUnavailablePropertyValid()
        {
            options.ActionOnBufferUnavailable = CachingBufferManager.Options.BufferUnavailableAction.ThrowException;
            Assert.AreEqual(CachingBufferManager.Options.BufferUnavailableAction.ThrowException,
                            options.ActionOnBufferUnavailable);
        }

        [Test]
        [ExpectedException(typeof(ArgumentOutOfRangeException))]
        public void ActionOnBufferUnavailablePropertyInvalid()
        {
            options.ActionOnBufferUnavailable = (CachingBufferManager.Options.BufferUnavailableAction) 17;
        }

        [Test]
        public void ICloneableCloneTest()
        {
            // Just test a subset of the options
            options.MinBufferSize = 5;
            options.MaxBufferSize = 10;
            options.MaxBuffersPerSizeBand = 15;

            ICloneable cloneable = options;
            CachingBufferManager.Options clone = (CachingBufferManager.Options)cloneable.Clone();

            Assert.AreEqual(5, clone.MinBufferSize);
            Assert.AreEqual(10, clone.MaxBufferSize);
            Assert.AreEqual(15, clone.MaxBuffersPerSizeBand);
        }
        #endregion
    }
}
