using System;
using System.Threading;

using NUnit.Framework;

using MiscUtil.Threading;

namespace MiscUtil.UnitTests.Threading
{
	/// <summary>
	/// Test cases for SyncLock
	/// </summary>
	[TestFixture]
	public class SyncLockTest
	{
		/// <summary>
		/// Main subject
		/// </summary>
		SyncLock subject;
		[SetUp]
		public void Setup()
		{
			subject = new SyncLock("Test", Timeout.Infinite);
		}

		/// <summary>
		/// Test that if we try to lock something that's already locked,
		/// we get an exception.
		/// </summary>
		[Test]
		public void TimeoutThrowsException()
		{
			Thread t = LockSubjectInOtherThread();
			try
			{
				Thread.Sleep(250);
				using (subject.Lock(500))
				{					
				}
				Assert.Fail("Should have thrown an exception");
			}
			catch (LockTimeoutException)
			{
				// Expected
			}
			t.Join();
		}

		/// <summary>
		/// We should be able to obtain a lock with no problem if no-one has it.
		/// </summary>
		[Test]
		public void SyncLock()
		{
			using (subject.Lock())
			{
			}
		}

		/// <summary>
		/// We should be able to lock a subject re-entrantly with no problems.
		/// </summary>
		[Test]
		public void ReentrantLock()
		{
			using (subject.Lock())
			{
				using (subject.Lock())
				{
				}
			}
		}

		/// <summary>
		/// After locking, we should own the monitor in the token.
		/// (If we don't, Monitor.Pulse will throw an exception.)
		/// </summary>
		[Test]
		public void MonitorIsAcquired()
		{
			using (LockToken token = subject.Lock())
			{
				Monitor.Pulse(subject.Monitor);
			}
		}

		[Test]
		public void MonitorIsReleased()
		{
			using (LockToken token = subject.Lock())
			{
			}

			try
			{
				Monitor.Pulse(subject.Monitor);
				Assert.Fail("Expected an exception");
			}
			catch (SynchronizationLockException)
			{
				// Expected
			}
		}

		/// <summary>
		/// If a name is specified, it should be preserved
		/// </summary>
		[Test]
		public void NamePreserved()
		{
			string name = "Hello";
			subject = new SyncLock(name);
			Assert.AreEqual(name, subject.Name);
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
