using System;
using System.Threading;

using NUnit.Framework;

using MiscUtil.Threading;

namespace MiscUtil.UnitTests.Threading
{
	/// <summary>
	/// Performance test cases for OrderedLock.
	/// </summary>
	[TestFixture]
	public class OrderedLockTest
	{
		const int Iterations = 1000000000;

		/// <summary>
		/// Tests the performance of OrderedLock compared with Monitor.Enter/Exit
		/// with no inner locks involved, when the lock is not owned to start with.
		/// </summary>
		[Test]
		public void InitialNoInnerLocks()
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

			OrderedLock syncLock = new OrderedLock(Timeout.Infinite);

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

			Console.WriteLine ("Performance with no inner locks (initial acquisition):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("OrderedLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "OrderedLock must not be ridiculously slow");
		}

		/// <summary>
		/// Tests the performance of OrderedLock compared with Monitor.Enter/Exit
		/// with no inner locks involved, and when the lock is already owned.
		/// </summary>
		[Test]
		public void AlreadyOwnedNoInnerLocks()
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

			OrderedLock syncLock = new OrderedLock(Timeout.Infinite);

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

			Console.WriteLine ("Performance with no inner locks (lock already owned):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("OrderedLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "OrderedLock must not be ridiculously slow");
		}

		/// <summary>
		/// Tests the performance of OrderedLock compared with Monitor.Enter/Exit
		/// with two inner locks involved, when the lock isn't owned to start with.
		/// </summary>
		[Test]
		public void InitialTwoInnerLocks()
		{
			DateTime start, end;

			object monitor = new object();
			
			start = DateTime.UtcNow;
			for (int i=0; i < Iterations; i++)
			{
				lock (monitor)
				{
				}
			}
			end = DateTime.UtcNow;
			TimeSpan nativeTime = end-start;

			OrderedLock orderedLock = new OrderedLock(Timeout.Infinite);
			OrderedLock inner1 = new OrderedLock(Timeout.Infinite);
			OrderedLock inner2 = new OrderedLock(Timeout.Infinite);
			orderedLock.InnerLock = inner1;
			inner1.InnerLock = inner2;

			start = DateTime.UtcNow;
			for (int i=0; i < Iterations; i++)
			{
				using (orderedLock.Lock())
				{
				}
			}
			end = DateTime.UtcNow;
			TimeSpan syncLockTime = end-start;
			double factor = syncLockTime.TotalMilliseconds / nativeTime.TotalMilliseconds;

			Console.WriteLine ("Performance with two inner locks (initial acquisition):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("OrderedLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "OrderedLock must not be ridiculously slow");
		}

		/// <summary>
		/// Tests the performance of OrderedLock compared with Monitor.Enter/Exit
		/// with two inner locks involved, when the lock is already owned beforehand.
		/// </summary>
		[Test]
		public void AlreadyOwnedTwoInnerLocks()
		{
			DateTime start, end;

			object monitor = new object();
			
			lock (monitor)
			{
				start = DateTime.UtcNow;
				for	(int i=0; i < Iterations; i++)
				{
					lock (monitor)
					{
					}
				}
				end = DateTime.UtcNow;
			}
			TimeSpan nativeTime = end-start;

			OrderedLock orderedLock = new OrderedLock(Timeout.Infinite);
			OrderedLock inner1 = new OrderedLock(Timeout.Infinite);
			OrderedLock inner2 = new OrderedLock(Timeout.Infinite);
			orderedLock.InnerLock = inner1;
			inner1.InnerLock = inner2;

			using (orderedLock.Lock())
			{
				start = DateTime.UtcNow;
				for (int i=0; i < Iterations; i++)
				{
					using (orderedLock.Lock())
					{
					}
				}
				end = DateTime.UtcNow;
			}
			TimeSpan syncLockTime = end-start;
			double factor = syncLockTime.TotalMilliseconds / nativeTime.TotalMilliseconds;

			Console.WriteLine ("Performance with two inner locks (lock already owned):");
			Console.WriteLine ("Native: {0}", nativeTime);
			Console.WriteLine ("OrderedLock: {0}", syncLockTime);
			Console.WriteLine ("Performance penalty factor: {0:0.00}", factor);
			Console.WriteLine();

			Assert.IsTrue (factor < 10, "OrderedLock must not be ridiculously slow");
		}
	}
}
