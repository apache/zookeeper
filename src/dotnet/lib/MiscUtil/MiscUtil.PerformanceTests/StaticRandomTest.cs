using System;
using System.Threading;

using NUnit.Framework;

namespace MiscUtil.UnitTests
{
	/// <summary>
	/// Tests for MiscUtil.StaticRandom
	/// </summary>
	[TestFixture]
	public class StaticRandomTest
	{
		/// <summary>
		/// Test a load of threads grabbing a load of numbers.
		/// This test should never actually fail; it will just display how
		/// long it took to run.
		/// </summary>
		[Test]
		public void MultipleThreads()
		{
			int threadCount = 20;
			int size = 10000000;

			DateTime start = DateTime.UtcNow;
			Thread[] threads = new Thread[threadCount];
			for (int i=0; i < threads.Length; i++)
			{
				threads[i] = new Thread(new ThreadStart(new RandomGrabber(size, false).GrabNumbers));
				threads[i].Start();
			}
			for (int i=0; i < threads.Length; i++)
			{
				threads[i].Join();
			}
			Console.WriteLine ("{0} threads grabbing {1} numbers each took {2}", 
				               threadCount, size, DateTime.UtcNow-start);
			Console.WriteLine();
		}
	}
}
