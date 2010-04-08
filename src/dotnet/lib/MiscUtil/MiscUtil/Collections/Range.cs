using System;
using System.Collections.Generic;
using System.Text;

namespace MiscUtil.Collections
{
    /// <summary>
    /// Represents a range of values
    /// </summary>
    public class Range<T> where T : IComparable<T>
    {
        /// <summary>
        /// The start of the range.
        /// </summary>
        T start;
        /// <summary>
        /// The end of the range.
        /// </summary>
        T end;

        /// <summary>
        /// Whether or not this range is inclusive
        /// </summary>
        bool isInclusive;        

        /// <summary>
        /// Constructs a new inclusive range
        /// </summary>
        public Range(T start, T end)
            : this (start, end, true)
        {
        }

        private Range(T start, T end, bool isInclusive)
        {
            this.start = start;
            this.end = end;
            this.isInclusive = isInclusive;
        }

        /// <summary>
        /// Returns an exclusive range with the same boundaries as this
        /// </summary>
        public Range<T> Exclusive
        {
            get 
            {  
                if (!isInclusive)
                {
                    return this;
                }
                Range<T> ret = (Range<T>)MemberwiseClone();
                ret.isInclusive = false;
                return ret;
            }
        }

        /// <summary>
        /// Returns an inclusive range with the same boundaries as this
        /// </summary>
        public Range<T> Inclusive
        {
            get 
            { 
                if (isInclusive)
                {
                    return this;
                }
                Range<T> ret = (Range<T>)MemberwiseClone();
                ret.isInclusive = true;
                return ret;
            }
        }

        /// <summary>
        /// Returns whether or not the range contains the given value
        /// </summary>
        public bool Contains(T value)
        {
            if (value.CompareTo(start) < 0)
            {
                return false;
            }
            int upperBound = value.CompareTo(end);
            return upperBound < 0 || (upperBound==0 && isInclusive);
        }
    }
}
