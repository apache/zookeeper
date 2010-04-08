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
	public class SyncLock
	{
		#region Fields which aren't backing properties
		/// <summary>
		/// Lock for static mutable properties.
		/// </summary>
		static object staticLock = new object();
		#endregion

		#region Properties
		/// <summary>
		/// The default timeout for new instances of this class
		/// where the default timeout isn't otherwise specified.
		/// Defaults to Timeout.Infinite.
		/// </summary>
		static int defaultDefaultTimeout = Timeout.Infinite;
		static int DefaultDefaultTimeout
		{
			get
			{
				lock (staticLock)
				{
					return defaultDefaultTimeout;
				}
			}
			set
			{
				if (value < Timeout.Infinite)
				{
					throw new ArgumentOutOfRangeException ("Invalid timeout specified");
				}
				lock (staticLock)
				{
					defaultDefaultTimeout = value;
				}
			}
		}

		int defaultTimeout;
		/// <summary>
		/// The default timeout for the 
		/// </summary>
		public int DefaultTimeout
		{
			get { return defaultTimeout; }
		}

		string name;
		/// <summary>
		/// The name of this lock.
		/// </summary>
		public string Name
		{
			get { return name; }
		}

		object monitor = new object();
		/// <summary>
		/// The internal monitor used for locking. While this
		/// is owned by the thread, it can be used for waiting
		/// and pulsing in the usual way. Note that manually entering/exiting
		/// this monitor could result in the lock malfunctioning.
		/// </summary>
		public object Monitor
		{
			get { return monitor; }
		}
		#endregion

		#region Constructors
		/// <summary>
		/// Creates a new lock with no name, and the default timeout specified by DefaultDefaultTimeout.
		/// </summary>
		public SyncLock() : this (null, DefaultDefaultTimeout)
		{
		}

		/// <summary>
		/// Creates a new lock with the specified name, and the default timeout specified by
		/// DefaultDefaultTimeout.
		/// </summary>
		/// <param name="name">The name of the new lock</param>
		public SyncLock (string name) : this (name, DefaultDefaultTimeout)
		{
		}

		/// <summary>
		/// Creates a new lock with no name, and the specified default timeout
		/// </summary>
		/// <param name="defaultTimeout">Default timeout, in milliseconds</param>
		public SyncLock(int defaultTimeout) : this (null, defaultTimeout)
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
		public SyncLock (string name, int defaultTimeout)
		{
			if (defaultTimeout < Timeout.Infinite)
			{
				throw new ArgumentOutOfRangeException ("Invalid timeout specified");
			}
			if (name==null)
			{
				name = "Anonymous Lock";
			}
			this.name = name;
			this.defaultTimeout = defaultTimeout;
		}
		#endregion

		#region Lock methods
		/// <summary>
		/// Locks the monitor, with the default timeout.
		/// </summary>
		/// <returns>A lock token which should be disposed to release the lock</returns>
		/// <exception cref="LockTimeoutException">The operation times out.</exception>
		public LockToken Lock()
		{
			return Lock(defaultTimeout);
		}

		/// <summary>
		/// Locks the monitor, with the specified timeout.
		/// </summary>
		/// <param name="timeout">The timeout duration. When converted to milliseconds, 
		/// must be Timeout.Infinite, or non-negative.</param>
		/// <returns>A lock token which should be disposed to release the lock</returns>
		/// <exception cref="LockTimeoutException">The operation times out.</exception>
		public LockToken Lock (TimeSpan timeout)
		{
			long millis = (long) timeout.TotalMilliseconds;
			if (millis < Timeout.Infinite || millis > int.MaxValue)
			{
				throw new ArgumentOutOfRangeException ("Invalid timeout specified");
			}
			return Lock ((int)millis);
		}

		/// <summary>
		/// Locks the monitor, with the specified timeout. Derived classes may override
		/// this method to change the behaviour; the other calls to Lock all result in
		/// a call to this method. This implementation checks the validity of the timeout,
		/// calls Monitor.TryEnter (throwing an exception if appropriate) and returns a
		/// new LockToken.
		/// </summary>
		/// <param name="timeout">The timeout, in milliseconds. Must be Timeout.Infinite,
		/// or non-negative.</param>
		/// <returns>A lock token which should be disposed to release the lock</returns>
		/// <exception cref="LockTimeoutException">The operation times out.</exception>
		public virtual LockToken Lock (int timeout)
		{
			if (timeout < Timeout.Infinite)
			{
				throw new ArgumentOutOfRangeException ("Invalid timeout specified");
			}

			if (!System.Threading.Monitor.TryEnter(monitor, timeout))
			{
				throw new LockTimeoutException ("Failed to acquire lock {0}", name);
			}
			return new LockToken(this);
		}

		/// <summary>
		/// Unlocks the monitor. This method may be overridden in derived classes
		/// to change the behaviour. This implementation simply calls Monitor.Exit.
		/// </summary>
		protected internal virtual void Unlock()
		{
			System.Threading.Monitor.Exit(monitor);
		}
		#endregion
	}
}
