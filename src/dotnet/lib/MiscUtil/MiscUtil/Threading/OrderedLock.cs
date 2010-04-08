using System;
using System.Threading;

namespace MiscUtil.Threading
{
	/// <summary>
	/// Class used for locking, as an alternative to just locking on normal monitors.
	/// Allows for timeouts when locking, and each Lock method returns a token which
	/// must then be disposed of to release the internal monitor (i.e. to unlock).
	/// All properties and methods of this class are thread-safe.
	/// </summary>
	public class OrderedLock : SyncLock
	{
		#region Fields which aren't backing properties
		/// <summary>
		/// Lock count (incremented with Lock, decremented with Unlock).
		/// </summary>
		int count;
		#endregion

		#region Properties
		volatile Thread owner;
		/// <summary>
		/// The current owner of the lock, if any.
		/// </summary>
		public Thread Owner
		{
			get { return owner; }
		}

		volatile OrderedLock innerLock;
		/// <summary>
		/// Gets or sets the "inner" lock for this lock. This lock must not be acquired
		/// after the inner one, unless it has already been acquired previously.
		/// Inner locks are transitive - if A has an inner lock B, and B has
		/// an inner lock C, then C is also effectively an inner lock of A.
		/// If this property to null, this lock is considered not to have an inner lock.
		/// </summary>
		public OrderedLock InnerLock
		{
			set
			{
				innerLock = value;
			}
			get
			{
				return innerLock;
			}
		}
		#endregion

		#region Constructors
		/// <summary>
		/// Creates a new lock with no name, and the default timeout specified by DefaultDefaultTimeout.
		/// </summary>
		public OrderedLock() : base()
		{
		}

		/// <summary>
		/// Creates a new lock with the specified name, and the default timeout specified by
		/// DefaultDefaultTimeout.
		/// </summary>
		/// <param name="name">The name of the new lock</param>
		public OrderedLock (string name) : base(name)
		{
		}

		/// <summary>
		/// Creates a new lock with no name, and the specified default timeout
		/// </summary>
		/// <param name="defaultTimeout">Default timeout, in milliseconds</param>
		public OrderedLock(int defaultTimeout) : base(defaultTimeout)
		{
		}

		/// <summary>
		/// Creates a new lock with the specified name, and an
		/// infinite default timeout.
		/// </summary>
		/// <param name="name">The name of the new lock</param>
		/// <param name="defaultTimeout">
		/// Default timeout, in milliseconds. Use Timeout.Infinite
		/// for an infinite timeout, or a non-negative number otherwise.
		/// </param>
		public OrderedLock (string name, int defaultTimeout) : base (name, defaultTimeout)
		{
		}
		#endregion

		#region Deadlock detection
		/// <summary>
		/// Sets the "inner" lock for this lock, returning this lock. This
		/// is a convenience method for setting InnerLock as part of a variable
		/// declaration.
		/// </summary>
		/// <example>
		/// OrderedLock inner = new OrderedLock();
		/// OrderedLock outer = new OrderedLock().SetInnerLock(inner);
		/// </example>
		/// <param name="inner">The inner </param>
		/// <returns>This lock is returned.</returns>
		public OrderedLock SetInnerLock (OrderedLock inner)
		{
			InnerLock = inner;
			return this;
		}
		#endregion

		#region Lock methods
		/// <summary>
		/// Locks the monitor, with the specified timeout. This implementation validates
		/// the ordering of locks, and maintains the current owner.
		/// </summary>
		/// <param name="timeout">The timeout, in milliseconds. Must be Timeout.Infinite,
		/// or non-negative.</param>
		/// <returns>A lock token which should be disposed to release the lock</returns>
		/// <exception cref="LockTimeoutException">The operation times out.</exception>
		/// <exception cref="LockOrderException">
		/// The lock order would be violated if this lock were taken out. (i.e. attempting
		/// to acquire the lock could cause deadlock.)
		/// </exception>
		public override LockToken Lock (int timeout)
		{
			// Check whether we should be allowed to take out this lock, according to
			// the inner locks we have.
			// Performance note: This would be in a separate method, but the cost of
			// making a method call (which can't be inlined in this case) is sufficiently
			// high as to make it worth manually inlining.
			OrderedLock inner = InnerLock;
			// Performance note: This would be a single if statement with shortcutting,
			// but fetching the current thread is mildly expensive.
			if (inner!=null)
			{
				Thread currentThread = Thread.CurrentThread;
				if (Owner!=currentThread)
				{
					while (inner != null)
					{
						if (inner.Owner==currentThread)
						{
							throw new LockOrderException("Unable to acquire lock {0} as lock {1} is already held",
								Name, inner.Name);
						}
						inner = inner.InnerLock;
					}
				}
			}

			LockToken ret = base.Lock(timeout);

			// Now remember that we've locked, and set the owner if necessary
			// Performance note: On a single processor, it is slightly cheaper
			// to assign owner every time, without a test. On multiple processor
			// boxes, it is cheaper to avoid the volatile write.
			if (Interlocked.Increment(ref count)==1)
			{
				owner = Thread.CurrentThread;
			}
			return ret;
		}

		/// <summary>
		/// Unlocks the monitor, decreasing the count and setting the owner to null
		/// if the count becomes 0.
		/// </summary>
		protected internal override void Unlock()
		{
			base.Unlock();
			if (Interlocked.Decrement(ref count)==0)
			{
				owner = null;
			}
		}
		#endregion
	}
}
