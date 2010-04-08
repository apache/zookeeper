using System;
using System.Collections.Generic;
using System.Text;
using MiscUtil.Threading;

namespace MiscUtil
{
    /// <summary>
    /// An implementation of IBufferManager which keeps a cache of
    /// buffers. The precise behaviour is controlled by the nested Options
    /// class.
    /// 
    /// This class is safe to use from multiple threads, but buffers
    /// returned are not inherently thread safe (although they have no
    /// thread affinity).
    /// </summary>
    public sealed class CachingBufferManager : IBufferManager
    {
        #region Fields
        /// <summary>
        /// Options configurating this manager.
        /// </summary>
        readonly Options options;
        /// <summary>
        /// List of bands. Each entry is an array
        /// of size MaxBuffersPerSizeBand. Each entry of that array is either
        /// null or a buffer, which may or may not be in use. The first entry
        /// in the list consists of buffers of size MinBufferSize, then
        /// MinBufferSize*ScalingFactor etc.
        /// </summary>
        readonly List<CachedBuffer[]> bufferBands = new List<CachedBuffer[]>();
        /// <summary>
        /// Lock for member access. 5 seconds should be more than adequate
        /// as a timeout.
        /// </summary>
        readonly SyncLock padlock = new SyncLock("Lock for CachingBufferManager", 5000);
        #endregion

        #region Construction
        /// <summary>
        /// Creates a caching buffer manager configured with the default options
        /// (as per a freshly created instance of Options).
        /// </summary>
        public CachingBufferManager()
        {
            // Make sure the settings are available to all threads.
            using (padlock.Lock())
            {
                this.options = new Options();
            }
        }

        /// <summary>
        /// Creates a caching buffer manager configured with the specified
        /// options. Note that the options are cloned - any changes to the
        /// options passed in after construction will have no effect on the
        /// manager.
        /// </summary>
        /// <param name="options">The configuration options for this manager.</param>
        /// <exception cref="ArgumentException">The configuration is invalid</exception>
        public CachingBufferManager(Options options)
        {
            // Make sure the settings are available to all threads.
            using (padlock.Lock())
            {
                this.options = options.Clone();
                if (options.MaxBufferSize < options.MinBufferSize)
                {
                    throw new ArgumentException("MaxBufferSize must be at least as big as MinBufferSize");
                }
            }
        }
        #endregion

        #region IBufferManager Members
        /// <summary>
        /// Returns a buffer of the given size or greater.
        /// </summary>
        /// <exception cref="BufferAcquisitionException">If the buffer could
        /// not be taken from a pool, and the ActionOnBufferUnavailable
        /// is set to ThrowException, or if the specified size is greater
        /// than the maximum buffer size.</exception>
        /// <exception cref="ArgumentOutOfRangeException">If minimumSize is less than 0.</exception>
        public IBuffer GetBuffer(int minimumSize)
        {
            if (minimumSize < 0)
            {
                throw new ArgumentOutOfRangeException("minimumSize must be greater than or equal to 0");
            }
            if (minimumSize > options.MaxBufferSize)
            {
                throw new BufferAcquisitionException("Requested buffer " + minimumSize + " is larger " +
                                                     "than maximum buffer size " + options.MaxBufferSize);
            }

            // Work out the size of buffer to use, and where in the list to find
            // cached buffers
            int listIndex = 0;
            int size = options.MinBufferSize;
            while (size < minimumSize)
            {
                size = CalculateNextSizeBand(size);
                listIndex++;
            }

            // Loop in case we need to find the next size up
            while (true)
            {
                CachedBuffer ret = FindAvailableBuffer(listIndex, size);
                if (ret != null)
                {
                    return ret;
                }

                switch (options.ActionOnBufferUnavailable)
                {
                    case Options.BufferUnavailableAction.ReturnUncached:
                        return new CachedBuffer(minimumSize, options.ClearAfterUse);

                    case Options.BufferUnavailableAction.ThrowException:
                        throw new BufferAcquisitionException("No buffers available");
                }
                // Must be "use bigger". Use an uncached buffer if we've reached the maximum size, 
                // otherwise try the next size band
                if (size == options.MaxBufferSize)
                {
                    return new CachedBuffer(minimumSize, options.ClearAfterUse);
                }
                size = CalculateNextSizeBand(size);
                listIndex++;
            }
        }

        /// <summary>
        /// Works out the size of the next band up from the current size.
        /// This is based on the scaling factor in the options, the maximum buffer
        /// size, and the requirement that the returned size should always be
        /// greater than the original one. (This is achieved using Ceiling -
        /// the worst case is when size is 1 and the scaling factor is 1.25,
        /// whereupon Ceiling will return 2.)
        /// </summary>
        /// <param name="size"></param>
        int CalculateNextSizeBand(int size)
        {
            return (int)Math.Ceiling(Math.Min(options.MaxBufferSize,
                                              size * options.ScalingFactor));
        }

        /// <summary>
        /// Finds an available buffer from the list, creating a new buffer
        /// where appropriate.
        /// </summary>
        /// <param name="listIndex">Index into the list of buffer slots</param>
        /// <param name="size">Size of buffer to create if necessary</param>
        /// <returns>An available buffer, or null if none are available in the given band.</returns>
        CachedBuffer FindAvailableBuffer(int listIndex, int size)
        {
            using (padlock.Lock())
            {
                // Make sure there'll be an entry, even if it's null
                while (listIndex >= bufferBands.Count)
                {
                    bufferBands.Add(null);
                }

                // Create a new array of buffers if necessary
                CachedBuffer[] buffers = bufferBands[listIndex];
                if (buffers == null)
                {
                    buffers = new CachedBuffer[options.MaxBuffersPerSizeBand];
                    bufferBands[listIndex] = buffers;
                }

                // Look through all the buffers in this band for an available one, or an unused slot
                for (int i = 0; i < buffers.Length; i++)
                {
                    // No cached unused buffers. Create a new one.
                    if (buffers[i] == null)
                    {
                        buffers[i] = new CachedBuffer(size, options.ClearAfterUse);
                        return buffers[i];
                    }
                    if (buffers[i].Available)
                    {
                        buffers[i].Available = false;
                        return buffers[i];
                    }
                }
                return null;
            }
        }

        #endregion

        #region Options nested class
        /// <summary>
        /// Provides options for CachingBufferManager.
        /// </summary>
        public sealed class Options : ICloneable
        {
            int maxBuffersPerSizeBand = 16;
            /// <summary>
            /// The maximum number of buffers to keep for each
            /// size band. When a buffer is requested from one size band,
            /// if none are available and this many buffers have already
            /// been allocated, a buffer from the next size band is
            /// returned (using the same algorithm). Defaults to 16;
            /// must not be less than 1.
            /// </summary>
            public int MaxBuffersPerSizeBand 
            { 
                get { return maxBuffersPerSizeBand; } 
                set
                {
                    if (value < 1)
                    {
                        throw new ArgumentOutOfRangeException("Must have at least 1 buffer per size band");
                    }
                    maxBuffersPerSizeBand = value;
                }
            }

            int minBufferSize=1024;
            /// <summary>
            /// The minimum buffer size to use, in bytes. If a buffer less than this size is
            /// requested, this is the size that will actually be used. Must be at least 1.
            /// On construction of the CachingBufferManager, this must not be greater than
            /// MaxBufferSize. Defaults to 1024.
            /// </summary>
            public int MinBufferSize
            {
                get { return minBufferSize; } 
                set
                {
                    if (value < 1)
                    {
                        throw new ArgumentOutOfRangeException("Minimum buffer size must be at least 1");
                    }
                    minBufferSize = value;
                }
            }

            bool clearAfterUse=true;
            /// <summary>
            /// Whether buffers are cleared (i.e. all bytes set to 0) after they
            /// are disposed. Defaults to true. For situations where buffers do not
            /// contain any sensitive information, and all clients know to only use
            /// data they have specifically put into the buffer (e.g. when copying
            /// streams), this may be set to false for efficiency.
            /// </summary>
            public bool ClearAfterUse
            {
                get { return clearAfterUse; }
                set { clearAfterUse = value; }
            }

            double scalingFactor=2.0;
            /// <summary>
            /// The scaling factor for sizes of buffers. The smallest buffer is of size
            /// MinBufferSize, then the next smallest is MinBufferSize*ScalingFactor, and
            /// so forth. The default is 2.0, so with the default buffer size of 1K, buffers
            /// will be 1K, 2K, 4K, 8K etc. The value must be greater than or equal to 1.25.
            /// </summary>
            public double ScalingFactor
            {
                get { return scalingFactor; }
                set
                {
                    if (value < 1.25)
                    {
                        throw new ArgumentOutOfRangeException("Scaling factor must be at least 1.25.");
                    }
                    scalingFactor = value;
                }
            }

            int maxBufferSize = int.MaxValue;
            /// <summary>
            /// The maximum size of buffer to return, or 0 for no maximum.
            /// This is primarily used with an ActionOnBufferUnavailable of UseBigger,
            /// which could potentially create bigger and bigger buffers. If a buffer
            /// of a size greater than this is requested, a BufferAcquisitionException will
            /// be thrown. Defaults to Int32.MaxValue. The value must be greater than 0.
            /// On construction of the CachingBufferManager, this must not be less than
            /// MinBufferSize.
            /// </summary>
            public int MaxBufferSize
            {
                get { return maxBufferSize; }
                set
                {
                    if (value <= 0)
                    {
                        throw new ArgumentOutOfRangeException("Maximum buffer size must be non-negative");
                    }
                    maxBufferSize = value;
                }
            }

            BufferUnavailableAction actionOnBufferUnavailable = BufferUnavailableAction.ReturnUncached;
            /// <summary>
            /// Determines the action to take when a buffer of the most appropriate size
            /// is unavailable. Defaults to ReturnUncached.
            /// </summary>
            public BufferUnavailableAction ActionOnBufferUnavailable
            {
                get { return actionOnBufferUnavailable; }
                set
                {
                    if (!Enum.IsDefined(typeof(BufferUnavailableAction),
                                        value))
                    {
                        throw new ArgumentOutOfRangeException("Only defined in BufferUnavailableAction are permitted");
                    }
                    actionOnBufferUnavailable = value;
                }
            }

            /// <summary>
            /// Enumeration of options available when a buffer of the most appropriate size
            /// is unavailable.
            /// </summary>
            public enum BufferUnavailableAction
            {
                /// <summary>
                /// A buffer of the next size band up is returned, or the size band above that,
                /// etc, until the maximum size is reached, at which point this fails over to
                /// ReturnUncached.
                /// </summary>
                UseBigger,
                /// <summary>
                /// A buffer of exactly the right size is returned, but one which when disposed
                /// does not return the buffer to a cache.
                /// </summary>
                ReturnUncached,
                /// <summary>
                /// A BufferAcquisitionException is thrown.
                /// </summary>
                ThrowException
            }

            /// <summary>
            /// Strongly typed Clone implementation.
            /// </summary>
            /// <returns>A clone of these options</returns>
            public Options Clone()
            {
                return (Options)MemberwiseClone();
            }

            #region ICloneable Members
            /// <summary>
            /// Weakly typed Clone implementation.
            /// </summary>
            object ICloneable.Clone()
            {
                return Clone();
            }
            #endregion
        }
        #endregion
    }   
}
