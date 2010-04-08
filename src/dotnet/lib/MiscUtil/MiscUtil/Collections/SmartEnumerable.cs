using System;
using System.Collections;
using System.Collections.Generic;

namespace MiscUtil.Collections
{
    /// <summary>
    /// Type chaining an IEnumerable&lt;T&gt; to allow the iterating code
    /// to detect the first and last entries simply.
    /// </summary>
    /// <typeparam name="T">Type to iterate over</typeparam>
    public class SmartEnumerable<T> : IEnumerable<SmartEnumerable<T>.Entry>
    {
        /// <summary>
        /// Enumerable we proxy to
        /// </summary>
        IEnumerable<T> enumerable;

        /// <summary>
        /// Constructor.
        /// </summary>
        /// <param name="enumerable">Collection to enumerate. Must not be null.</param>
        public SmartEnumerable(IEnumerable<T> enumerable)
        {
            if (enumerable==null)
            {
                throw new ArgumentNullException ("enumerable");
            }
            this.enumerable = enumerable;
        }

        /// <summary>
        /// Returns an enumeration of Entry objects, each of which knows
        /// whether it is the first/last of the enumeration, as well as the
        /// current value.
        /// </summary>
        public IEnumerator<Entry> GetEnumerator()
        {
            using (IEnumerator<T> enumerator = enumerable.GetEnumerator())
            {
                if (!enumerator.MoveNext())
                {
                    yield break;
                }
                bool isFirst = true;
                bool isLast = false;
                int index=0;
                while (!isLast)
                {
                    T current = enumerator.Current;
                    isLast = !enumerator.MoveNext();
                    yield return new Entry(isFirst, isLast, current, index++);
                    isFirst = false;
                }
            }
        }

        /// <summary>
        /// Non-generic form of GetEnumerator.
        /// </summary>
        IEnumerator IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }

        /// <summary>
        /// Represents each entry returned within a collection,
        /// containing the value and whether it is the first and/or
        /// the last entry in the collection's. enumeration
        /// </summary>
        public class Entry
        {
            readonly bool isFirst;
            readonly bool isLast;
            readonly T value;
            readonly int index;
            
            internal Entry(bool isFirst, bool isLast, T value, int index)
            {
                this.isFirst = isFirst;
                this.isLast = isLast;
                this.value = value;
                this.index = index;
            }

            /// <summary>
            /// The value of the entry.
            /// </summary>
            public T Value { get { return value; } }

            /// <summary>
            /// Whether or not this entry is first in the collection's enumeration.
            /// </summary>
            public bool IsFirst { get { return isFirst; } }

            /// <summary>
            /// Whether or not this entry is last in the collection's enumeration.
            /// </summary>
            public bool IsLast { get { return isLast; } }

            /// <summary>
            /// The 0-based index of this entry (i.e. how many entries have been returned before this one)
            /// </summary>
            public int Index { get { return index; } }
        }
    }
}
