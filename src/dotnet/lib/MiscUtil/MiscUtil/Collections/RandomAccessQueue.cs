using System;
using System.Collections;
using System.Collections.Generic;

namespace MiscUtil.Collections
{
	/// <summary>
	/// A class with a similar function to System.Collections.Queue,
	/// but allowing random access to the contents of the queue as well
	/// as the usual enqueuing at the end and dequeuing at the start.
	/// This implementation is not synchronized at all - clients should
	/// provide their own synchronization. A SyncRoot is provided for
	/// this purpose, although any other common reference may also be used.
	/// In order to provide an efficient implementation of both random access
	/// and the removal of items from the start of the queue, a circular
	/// buffer is used and resized when necessary. The buffer never shrinks
	/// unless TrimToSize is called.
	/// </summary>
	public sealed class RandomAccessQueue<T> : ICollection<T>, ICollection, IEnumerable<T>, ICloneable
	{
		#region Constants
		/// <summary>
		/// Default (and minimum) capacity for the buffer containing the elements in the queue.
		/// </summary>
		public const int DefaultCapacity = 16;
		#endregion

		#region Fields, properties and indexer
		/// <summary>
		/// The circular buffer containing the items in the queue
		/// </summary>
		T[] buffer;

		/// <summary>
		/// The "physical" index of item with logical index 0.
		/// </summary>
		int start;

		int count;
		/// <summary>
		/// The number of items in the queue.
		/// </summary>
		public int Count
		{
			get { return count; }
		}

		/// <summary>
		/// Indexer for the class, allowing items to be retrieved by
		/// index and replaced.
		/// </summary>
		public T this[int index]
		{
			get
			{
				if (index < 0 || index >= count)
				{
					throw new ArgumentOutOfRangeException("index");
				}
				return buffer[(start+index)%Capacity];
			}
			set
			{
				if (index < 0 || index >= count)
				{
					throw new ArgumentOutOfRangeException("index");
				}
				version++;
				buffer[(start+index)%Capacity]=value;
			}
		}

		/// <summary>
		/// Current capacity of the queue - the size of the buffer.
		/// </summary>
		public int Capacity
		{
			get { return buffer.Length; }
		}

		/// <summary>
		/// Version information for the queue - this is incremented every time
		/// the contents of the queue is changed, so that enumerators can detect
		/// the change.
		/// </summary>
		int version=0;
		#endregion

		#region Construction
		/// <summary>
		/// Initializes a new instance of the RandomAccessQueue class which is empty
		/// and has the specified capacity (or the default capacity if that is higher).
		/// </summary>
		/// <param name="capacity">The initial capacity of the queue</param>
		public RandomAccessQueue (int capacity)
		{
			buffer = new T[Math.Max(capacity, DefaultCapacity)];
		}

		/// <summary>
		/// Initializes a new instance of the RandomAccessQueue class which is empty
		/// and has the default capacity.
		/// </summary>
		public RandomAccessQueue() : this (DefaultCapacity)
		{
		}

		/// <summary>
		/// Private constructor used in cloning
		/// </summary>
		/// <param name="buffer">The buffer to clone for use in this queue</param>
		/// <param name="count">The number of "valid" elements in the buffer</param>
		/// <param name="start">The first valid element in the queue</param>
		RandomAccessQueue (T[] buffer, int count, int start)
		{
			this.buffer = (T[])buffer.Clone();
			this.count = count;
			this.start = start;
		}
		#endregion
		
		#region Public methods 
		/// <summary>
		/// Clears the queue without resizing the buffer
		/// </summary>
		public void Clear()
		{
			start = 0;
			count = 0;
			((IList)buffer).Clear();
		}

		/// <summary>
		/// Resizes the buffer to just fit the current number of items in the queue.
		/// The buffer size is never set to less than the default capacity, however.
		/// </summary>
		public void TrimToSize()
		{
			int newCapacity = Math.Max(Count, DefaultCapacity);
			if (Capacity==newCapacity)
			{
				return;
			}
			Resize(newCapacity, -1);
		}

		/// <summary>
		/// Adds an item to the end of the queue.
		/// </summary>
		/// <param name="value">The item to add to the queue. The value can be a null reference.</param>
		public void Enqueue (T value)
		{
			Enqueue(value, count);
		}

		/// <summary>
		/// Adds an object at the specified index.
		/// </summary>
		/// <param name="value">The item to add to the queue. The value can be a null reference.</param>
		/// <param name="index">The index of the newly added item</param>
		public void Enqueue (T value, int index)
		{
			if (count==Capacity)
			{
				Resize(count*2, index);
				count++;
			}
			else
			{
				count++;
				// TODO: Make this vaguely efficient :)
				for (int i=count-2; i >= index; i--)
				{
					this[i+1]=this[i];
				}
			}
			this[index]=value;
		}	        

		/// <summary>
		/// Removes an T from the start of the queue, returning it.
		/// </summary>
		/// <returns>The item at the head of the queue</returns>
		public T Dequeue()
		{
			if (count==0)
			{
				throw new InvalidOperationException("Dequeue called on an empty queue.");
			}
			T ret = this[0];
			this[0] = default(T);
			start++;
			if (start==Capacity)
			{
				start = 0;
			}
			count--;
			return ret;
		}

		/// <summary>
		/// Removes an item at the given index and returns it.
		/// </summary>
		/// <param name="index">The index of the item to remove</param>
		/// <returns>The item which has been removed from the</returns>
		public T RemoveAt(int index)
		{
			if (index < 0 || index >= count)
			{
				throw new ArgumentOutOfRangeException("index");
			}
			// Special case: head of queue
			if (index==0)
			{
				return Dequeue();
			}
			T ret = this[index];
			// Special case: end of queue
			if (index==count-1)
			{
				this[index]=default(T);
				count--;
				return ret;
			}
			T current = this[index];
			// Everything else involves shuffling things one way or the other.
			// Shuffle things in whichever way involves only a single array copy
			// (either towards the end or towards the start - with a circular buffer
			// it doesn't matter which)
			// TODO: Benchmark this to find out whether one way is faster than the other
			// and possibly put code in to copy the shorter amount, even if it involves two copies
			if (start+index >= Capacity)
			{
                // Move everything later than index down 1
				Array.Copy(buffer, start+index-Capacity+1, 
                           buffer, start+index-Capacity, 
                           count-index-1);
                buffer[(start + count - 1) - Capacity] = default(T);
			}
			else
			{
                // Move everything earlier than index up one
				Array.Copy(buffer, start, buffer, start+1, index);
				buffer[start]=default(T);
				start++;
			}
			count--;
			version++;
			return ret;
		}

		/// <summary>
		/// Copies the elements of the queue to the given array, beginning at
		/// the specified index in the array.
		/// </summary>
		/// <param name="dest">The array to copy the contents of the queue into</param>
		/// <param name="index">The zero-based index in array at which copying begins</param>
		public void CopyTo (Array dest, int index)
		{
			if (dest==null)
			{
				throw new ArgumentNullException("dest");
			}
            T[] strongDest = dest as T[];
            if (strongDest==null)
            {
                throw new ArgumentException(string.Format("Cannot copy elements of type {0} to an array of type {1}",
                                                          typeof(T).Name, dest.GetType().GetElementType().Name));
            }
            CopyTo(strongDest, index);
		}

		/// <summary>
		/// Performs a binary search using IComparable. If the value occurs multiple times,
		/// there is no guarantee as to which index will be returned. If the value does
		/// not occur at all, the bitwise complement of the first index containing a larger
		/// value is returned (or the bitwise complement of the size of the queue if the value
		/// is larger than any value in the queue). This is the location at which the value should
		/// be inserted to preserve sort order. If the list is not sorted according to 
		/// the appropriate IComparable implementation before this method is calling, the result
		/// is not guaranteed. The value passed in must implement IComparable, unless it is null.
		/// The IComparable.CompareTo method will be called on the value passed in, with the
		/// values in the queue as parameters, rather than the other way round. No test is made
		/// to make sure that the types of item are the same - it is up to the implementation of
		/// IComparable to throw an exception if incomparable types are presented.
		/// A null reference is treated as being less than any item, (so passing in null will always 
		/// return 0 or -1). The implementation of IComparable is never asked to compare to null.
		/// </summary>
		/// <param name="obj">The item to search for</param>
		/// <returns>
		/// A location in the queue containing the item, or the bitwise complement of the 
		/// first index containing a larger value.
		/// </returns>
		public int BinarySearch (T obj)
		{
			if (obj==null)
			{
				if (count==0 || buffer[start]!=null)
				{
					return ~0;
				}
				else
				{
					return 0;
				}
			}

			IComparable comp = obj as IComparable;
			if (comp==null)
			{
				throw new ArgumentException("obj does not implement IComparable");
			}

			if (count==0)
			{
				return ~0;
			}

			int min=0;
			int max=count-1;

			while (min <= max)
			{
				int test = (min+max)/2;
				T element= this[test];
				int result = element==null ? 1 : comp.CompareTo(element);
				if (result==0)
				{
					return test;
				}
				if (result < 0)
				{
					max = test-1;
				}
				if (result > 0)
				{
					min = test+1;
				}
			}

			return ~min;
		}

		/// <summary>
		/// Performs a binary search using the specified IComparer. If the value occurs multiple times,
		/// there is no guarantee as to which index will be returned. If the value does
		/// not occur at all, the bitwise complement of the first index containing a larger
		/// value is returned (or the bitwise complement of the size of the queue if the value
		/// is larger than any value in the queue). This is the location at which the value should
		/// be inserted to preserve sort order. If the list is not sorted according to 
		/// the appropriate IComparer implementation before this method is calling, the result
		/// is not guaranteed. The CompareTo method will be called on the comparer passed in, with the
		/// specified value as the first parameter, and values in the queue as the second parameter, 
		/// rather than the other way round.
		/// While a null reference should be treated as being less than any object in most
		/// implementations of IComparer, this is not required by this method. Any null references
		/// (whether in the queue or the specified value itself) are passed directly to the CompareTo
		/// method. This allow for IComparers to reverse the usual order, if required.
		/// </summary>
		/// <param name="obj">The object to search for</param>
		/// <param name="comparer">The comparator to use for searching. Must not be null.</param>
		/// <returns>
		/// A location in the queue containing the object, or the bitwise complement of the 
		/// first index containing a larger value.
		/// </returns>
		public int BinarySearch (T obj, IComparer<T> comparer)
		{
			if (comparer==null)
			{
				throw new ArgumentNullException("comparer");
			}

			if (count==0)
			{
				return ~0;
			}

			int min=0;
			int max=count-1;

			while (min <= max)
			{
				int test = (min+max)/2;
				int result = comparer.Compare(obj, this[test]);
				if (result==0)
				{
					return test;
				}
				if (result < 0)
				{
					max = test-1;
				}
				if (result > 0)
				{
					min = test+1;
				}
			}

			return ~min;
		}

        /// <summary>
        /// Performs a binary search using the specified Comparison. If the value occurs multiple times,
        /// there is no guarantee as to which index will be returned. If the value does
        /// not occur at all, the bitwise complement of the first index containing a larger
        /// value is returned (or the bitwise complement of the size of the queue if the value
        /// is larger than any value in the queue). This is the location at which the value should
        /// be inserted to preserve sort order. If the list is not sorted according to 
        /// the appropriate IComparer implementation before this method is calling, the result
        /// is not guaranteed. The CompareTo method will be called on the comparer passed in, with the
        /// specified value as the first parameter, and values in the queue as the second parameter, 
        /// rather than the other way round.
        /// While a null reference should be treated as being less than any object in most
        /// implementations of IComparer, this is not required by this method. Any null references
        /// (whether in the queue or the specified value itself) are passed directly to the CompareTo
        /// method. This allow for Comparisons to reverse the usual order, if required.
        /// </summary>
        /// <param name="obj">The object to search for</param>
        /// <param name="comparison">The comparison to use for searching. Must not be null.</param>
        /// <returns>
        /// A location in the queue containing the object, or the bitwise complement of the 
        /// first index containing a larger value.
        /// </returns>
        public int BinarySearch(T obj, Comparison<T> comparison)
        {
            return BinarySearch(obj, new ComparisonComparer<T>(comparison));
        }        

		/// <summary>
		/// Returns an enumerator that can iterate through the queue.
        /// Note that due to the way C# 2.0 iterators work, we cannot spot changes 
        /// to the queue after the enumerator was fetched but before MoveNext() is first
        /// called.
		/// </summary>
		/// <returns>Returns an enumerator for the entire queue.</returns>
        public IEnumerator<T> GetEnumerator()
        {
            int originalVersion = version;

            for (int i = 0; i < Count; i++)
            {
                yield return this[i];
                if (version != originalVersion)
                {
                    throw new InvalidOperationException
                        ("Collection was modified after the enumerator was created");
                }
            }
        }

        /// <summary>
        /// Returns an enumerator that can iterate through the queue.
        /// </summary>
        /// <returns>Returns an enumerator for the entire queue.</returns>
        IEnumerator IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }

		/// <summary>
		/// Creates a new queue with the same contents as this queue.
		/// The queues are separate, however - adding an item to the returned
		/// queue doesn't affect the current queue or vice versa.
        /// A new sync root is also supplied.
		/// </summary>
		/// <returns>A clone of the current queue</returns>
		object ICloneable.Clone()
		{
			return this.Clone();
		}

		/// <summary>
		/// Strongly typed version of ICloneable.Clone. Creates a new queue 
		/// with the same contents as this queue.
		/// The queues are separate, however - adding an item to the returned
		/// queue doesn't affect the current queue or vice versa.
        /// A new sync root is also supplied.
        /// </summary>
		/// <returns>A clone of the current queue</returns>
		public RandomAccessQueue<T> Clone()
		{
			return new RandomAccessQueue<T>(buffer, count, start);
		}
		#endregion	

		#region Private methods
		/// <summary>
		/// Resizes the queue to a new capacity, optionally leaving a gap at
		/// a specified logical index so that a new item can be slotted in 
		/// without further copying
		/// </summary>
		/// <param name="newCapacity">The new capacity</param>
		/// <param name="gapIndex">The logical index at which to insert a gap,
		/// or -1 for no gap</param>
		void Resize (int newCapacity, int gapIndex)
		{
			T[] newBuffer = new T[newCapacity];
			if (gapIndex==-1)
			{
				int firstChunkSize;
				int secondChunkSize;
                // If we don't wrap round, it's easy
				if (buffer.Length-start >= count)
				{
					firstChunkSize = count;
					secondChunkSize = 0;
				}
				else
				{
					firstChunkSize = buffer.Length-start;
					secondChunkSize = count-firstChunkSize;
				}
				Array.Copy(buffer, start, newBuffer, 0, firstChunkSize);
				Array.Copy(buffer, 0, newBuffer, firstChunkSize, secondChunkSize);
			}
			else
			{
				// Aargh. The logic's too difficult to do prettily here. Do it simply instead...
				int outIndex=0;
				int inIndex=start;
				for (int i=0; i < count; i++)
				{
					if (i==gapIndex)
					{
						outIndex++;
					}
					newBuffer[outIndex] = buffer[inIndex];
					outIndex++;
					inIndex++;
					if (inIndex==buffer.Length)
					{
						inIndex=0;
					}
				}
			}
			buffer = newBuffer;
			start = 0;
		}
		#endregion

		#region Synchronization
		object syncRoot = new object();
		/// <summary>
		/// An object reference to synchronize on when using the queue
		/// from multiple threads. This reference isn't used anywhere
		/// in the class itself. The same reference will always be returned
        /// for the same queue, and this will never be the same as the reference
        /// returned for a different queue, even a clone.
		/// </summary>
		public object SyncRoot
		{
			get { return syncRoot; }
		}	

		/// <summary>
		/// Returns false, to indicate that this queue is not synchronized.
		/// </summary>
		public bool IsSynchronized
		{
			get { return false; }
		}
		#endregion

        #region ICollection<T> Members

        /// <summary>
        /// Adds an item to the queue
        /// </summary>
        /// <param name="item">The item to add</param>
        public void Add(T item)
        {
            Enqueue(item);
        }

        /// <summary>
        /// Returns whether or not the queue contains the given item,
        /// using the default EqualityComparer if the item to find is
        /// non-null.
        /// </summary>
        public bool Contains(T item)
        {
            if (item == null)
            {
                for (int i = 0; i < Count; i++)
                {
                    if (this[i] == null)
                    {
                        return true;
                    }
                }
                return false;
            }
            IEqualityComparer<T> comparer = EqualityComparer<T>.Default;
            for (int i = 0; i < Count; i++)
            {
                if (comparer.Equals(this[i], item))
                {
                    return true;
                }
            }
            return false;            
        }

        /// <summary>
        /// Copies the elements of the queue to the given array, beginning at
        /// the specified index in the array.
        /// </summary>
        /// <param name="dest">The array to copy the contents of the queue into</param>
        /// <param name="index">The zero-based index in array at which copying begins</param>
        public void CopyTo(T[] dest, int index)
        {
            if (dest == null)
            {
                throw new ArgumentNullException("dest");
            }
            if (index < 0)
            {
                throw new ArgumentOutOfRangeException("index");
            }
            if (dest.Length < index + Count)
            {
                throw new ArgumentException("Not enough space in array for contents of queue");
            }
            // TODO: Use one or two Array.Copy calls instead
            for (int i = 0; i < Count; i++)
            {
                dest[i + index] = this[i];
            }
        }

        /// <summary>
        /// Returns false, to indicate that this queue is not read-only.
        /// </summary>
        public bool IsReadOnly
        {
            get { return false; }
        }

        /// <summary>
        /// Removes the given item from the queue, if it is present. The first
        /// equal value is removed.
        /// </summary>
        public bool Remove(T item)
        {
            if (item == null)
            {
                for (int i = 0; i < Count; i++)
                {
                    if (this[i] == null)
                    {
                        RemoveAt(i);
                        return true;
                    }
                }
                return false;
            }
            IEqualityComparer<T> comparer = EqualityComparer<T>.Default;
            for (int i = 0; i < Count; i++)
            {
                if (comparer.Equals(this[i], item))
                {
                    RemoveAt(i);
                    return true;
                }
            }
            return false;
        }

        #endregion
    }
}
