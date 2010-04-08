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
		const int Iterations = 1000000000;
        
		/// <summary>
		/// Tests the performance of acquiring a SyncLock for the first time compared with Monitor.Enter/Exit.
		/// </summary>
		[Test]
		public void Initial()
		{
			object monitor = new object();
			DateTime start = DateTime.UtcNow;
			for (int i=0; i < Iterations; i++)
			{
				lock (monitor)
				{
				}
			}
			DateTime end = DateTime.UtcNow;
			TimeSpan nativeTime = end-start;

			SyncLock syncLock = new SyncLock(Timeout.Infinite);

			start = DateTime.UtcNow;
			for (int i=0; i < Iterations; i++)
			{
				using (syncLock.Lock())
				{
				}
			}
			end = DateTime.UtcNow;
			TimeSpan syncLockTime = end-start;

			double factor = syncLockTime.TotalMilliseconds / nativeTime.TotalMilliseconds;

			Console.WriteLine ("Performance of SyncLock (initial acquisition):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("SyncLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "SyncLock must not be ridiculously slow");
		}

		/// <summary>
		/// Tests the performance of acquiring a SyncLock which the current thread 
		/// already owns compared with Monitor.Enter/Exit.
		/// </summary>
		[Test]
		public void AlreadyOwned()
		{
			DateTime start, end;
			object monitor = new object();
			lock (monitor)
			{
				start = DateTime.UtcNow;
				for (int i=0; i < Iterations; i++)
				{
					lock (monitor)
					{
					}
				}
				end = DateTime.UtcNow;
			}
			
			TimeSpan nativeTime = end-start;

			SyncLock syncLock = new SyncLock(Timeout.Infinite);

			using (syncLock.Lock())
			{
				start = DateTime.UtcNow;
				for (int i=0; i < Iterations; i++)
				{
					using (syncLock.Lock())
					{
					}
				}
				end = DateTime.UtcNow;
			}
			TimeSpan syncLockTime = end-start;

			double factor = syncLockTime.TotalMilliseconds / nativeTime.TotalMilliseconds;

			Console.WriteLine ("Performance of SyncLock (lock already owned):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("SyncLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "SyncLock must not be ridiculously slow");
		}
	}
}
