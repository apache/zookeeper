using System;
using System.Threading;

using NUnit.Framework;

using MiscUtil.Threading;

namespace MiscUtil.UnitTests.Threading
{
	/// <summary>
	/// Test cases for OrderedLock. Note that these tests do not duplicate the
	/// tests of SimpleLock - in other words, we don't explicitly validate that
	/// we haven't broken straight locking.
	/// </summary>
	[TestFixture]
	public class OrderedLockTest
	{
		/// <summary>
		/// Main subject
		/// </summary>
		OrderedLock subject;
		/// <summary>
		/// Inner lock of subject
		/// </summary>
		OrderedLock inner1 = new OrderedLock();
		/// <summary>
		/// Inner lock of inner1
		/// </summary>
		OrderedLock inner2 = new OrderedLock();

		[SetUp]
		public void Setup()
		{
			subject = new OrderedLock("Test", Timeout.Infinite);

			inner1.InnerLock = inner2;
			subject.InnerLock = inner1;
		}

		/// <summary>
		/// Obtaining locks in the correct order doesn't cause an exception
		/// </summary>
		[Test]
		public void CorrectOrder()
		{
			using (subject.Lock())
			using (inner1.Lock())
			using (inner2.Lock())
			{
			}
		}

		/// <summary>
		/// Obtaining locks in an incorrect order throws an exception at the
		/// right time.
		/// </summary>
		[Test]
		public void IncorrectOrder()
		{
			using (inner1.Lock())
			{
				try
				{
					using (subject.Lock())
					{
						Assert.Fail ("Expected an exception");
					}
				}
				catch (LockOrderException)
				{
					// Expected
				}
			}
		}

		/// <summary>
		/// After the outer lock has been acquired, it's allowed to be reacquired 
		/// after an inner lock.
		/// </summary>
		public void IncorrectOrderAfterInitialAcquisition()
		{
			using (subject.Lock())
			using (inner1.Lock())
			using (subject.Lock())
			{
			}
		}

		/// <summary>
		/// Obtaining inner2 then subject should fail too.
		/// </summary>
		[Test]
		public void InnerLocksAreTransitive()
		{
			using (inner2.Lock())
			{
				try
				{
					using (subject.Lock())
					{
						Assert.Fail ("Expected an exception");
					}
				}
				catch (LockOrderException)
				{
					// Expected
				}
			}
		}

		/// <summary>
		/// The Owner thread should be null when it's not owned, and
		/// the correct thread otherwise (counting appropriately).
		/// </summary>
		[Test]
		public void CorrectOwner()
		{
			Assert.IsNull(subject.Owner);
			using (subject.Lock())
			{
				Assert.AreEqual(Thread.CurrentThread, subject.Owner);
				using (subject.Lock())
				{
					Assert.AreEqual(Thread.CurrentThread, subject.Owner);
				}
				Assert.AreEqual(Thread.CurrentThread, subject.Owner);
			}
			Assert.IsNull(subject.Owner);
		}

		#region Non-tests
		/// <summary>
		/// Locks the subject for half a second in a different
		/// thread, returning that thread.
		/// </summary>
		Thread LockSubjectInOtherThread()
		{
			Thread t = new Thread(new ThreadStart(LockSubject));
			t.Start();
			return t;
		}

		/// <summary>
		/// Just used by LockSubjectInOtherThread.
		/// </summary>
		void LockSubject()
		{
			using (subject.Lock())
			{
				Thread.Sleep(1000);
			}
		}
		#endregion
	}
}
