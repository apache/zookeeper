using System;
using System.Collections;

namespace MiscUtil.Text
{
	/// <summary>
	/// String of UTF-32 characters (ints). This class is immutable, and so is thread-safe
	/// after copying, but copies must be originally made in a thread-safe manner so as
	/// to avoid seeing invalid data.
	/// </summary>
	public sealed class Utf32String : IEnumerable, IComparable, ICloneable
	{
		#region Constants
		const int HighSurrogateStart = 0xd800;
		const int HighSurrogateEnd = 0xdbff;
		const int LowSurrogateStart = 0xdc00;
		const int LowSurrogateEnd = 0xdfff;
		const int MaxUtf32Character = 0x10ffff;

		/// <summary>
		/// Number of samples to take (at most) to form a hash code.
		/// </summary>
		const int HashcodeSampleSize = 20;

		/// <summary>
		/// An empty UTF-32 string.
		/// </summary>
		public static readonly Utf32String Empty = new Utf32String (new int[0]);
		#endregion

		#region Instance fields
		/// <summary>
		/// UTF-32 characters making up the string.
		/// </summary>
		readonly int[] characters;
		#endregion

		#region IsValidUtf32Char
		/// <summary>
		/// Returns whether or not an integer value is a valid
		/// UTF-32 character, that is, whether it is non-negative
		/// and less than or equal to 0x10ffff.
		/// </summary>
		/// <param name="value">The value to test.</param>
		/// <returns>Whether or not the given value is a valid UTF-32 character.</returns>
		public static bool IsValidUtf32Char (int value)
		{
			return (value >= 0 && value <= MaxUtf32Character);
		}
		#endregion

		#region Properties and indexers
		/// <summary>
		/// The number of UTF-32 characters in this string.
		/// </summary>
		public int Length
		{
			get { return characters.Length; }
		}

		/// <summary>
		/// The character at the specified index.
		/// </summary>
		public int this[int index]
		{
			get { return characters[index]; }
		}
		#endregion

		#region Constructors
		/// <summary>
		/// Used inside this class to construct extra strings quickly, when validation
		/// isn't required and a reference copy is good enough.
		/// </summary>
		Utf32String(int[] characters, bool unused)
		{
			this.characters = characters;
		}

		/// <summary>
		/// Creates a UTF-32 string from an array of integers, all of which must
		/// be less than 0x10ffff and non-negative. A copy of the array is taken so that
        /// further changes to the array afterwards are ignored.
		/// </summary>
		/// <param name="characters">The array of characters to copy. Must not be null.</param>
		public Utf32String(int[] characters)
		{
            characters = (int[])characters.Clone();
			foreach (int character in characters)
			{
				if (!IsValidUtf32Char(character))
				{
					throw new ArgumentException
						("Invalid character in array: "+character, "characters");
				}
			}
			this.characters = characters;
		}

		/// <summary>
		/// Creates a UTF-32 string from a System.String (UTF-16), converting surrogates
		/// where they are present.
		/// </summary>
		/// <param name="utf16">The string in UTF-16 format.</param>
		public Utf32String(String utf16)
		{
			if (utf16 == null)
			{
				throw (new System.ArgumentNullException("utf16"));
			}
			// Assume no surrogates to start with
			characters = new int[utf16.Length];
			int highSurrogate=-1;
			int outputIndex=0;
			for (int i=0; i < utf16.Length; i++)
			{
				char c = utf16[i];
				if (c >= HighSurrogateStart && c <= HighSurrogateEnd)
				{
					if (highSurrogate != -1)
					{
						throw new ArgumentException 
							("Invalid string: two high surrogates in a row", "utf16");
					}
					highSurrogate = (c-HighSurrogateStart)*0x400;
					continue;
				}
				else if (c >= LowSurrogateStart && c <= LowSurrogateEnd)
				{
					if (highSurrogate == -1)
					{
						throw new ArgumentException 
							("Invalid string: low surrogate not preceded by high surrogate");
					}
					characters[outputIndex++] = highSurrogate+(c-LowSurrogateStart)+0x10000;
					highSurrogate = -1;
				}
				else
				{
					if (highSurrogate != -1)
					{
						throw new ArgumentException 
							("Invalid string: high surrogates with no following low surrogate", "utf16");
					}
					characters[outputIndex++] = c;
				}
			}
			if (highSurrogate != -1)
			{
				throw new ArgumentException ("Invalid string: final character is a high surrogate");
			}
			// Trim array if necessary
			if (outputIndex != characters.Length)
			{
				int[] tmp = new int[outputIndex];
				Array.Copy(characters, 0, tmp, 0, outputIndex);
				characters = tmp;
			}
		}

		#endregion

		#region Substring
		/// <summary>
		/// Takes a substring of this string, starting at the given index.
		/// </summary>
		/// <param name="start">Starting index of desired substring in this string</param>
		/// <returns>A substring of this string</returns>
		public Utf32String Substring (int start)
		{
			if (start < 0)
			{
				throw new ArgumentOutOfRangeException("start must be non-negative", "start");
			}
			if (start > Length)
			{
				throw new ArgumentOutOfRangeException
					("start must be less than or equal to the length of the string", "start");
			}
			if (start==Length)
			{
				return Utf32String.Empty;
			}
			return Substring (start, Length-start);
		}

		/// <summary>
		/// Takes a substring of this string, starting at the given index
		/// and containing the given number of characters.
		/// </summary>
		/// <param name="start">Starting index of desired substring in this string</param>
		/// <param name="count">The number of characters in the desired substring</param>
		/// <returns>A substring of this string</returns>
		public Utf32String Substring (int start, int count)
		{
			if (start < 0)
			{
				throw new ArgumentOutOfRangeException("start must be non-negative", "start");
			}
			if (start > Length)
			{
				throw new ArgumentOutOfRangeException
					("start must be less than or equal to the length of the string", "start");
			}
			if (count < 0)
			{
				throw new ArgumentOutOfRangeException("count must be non-negative", "count");
			}
			if (start+count > Length)
			{
				throw new ArgumentOutOfRangeException
					("start+count must be less than or equal to the length of the string");
			}
			if (count==0)
			{
				return Utf32String.Empty;
			}
			int[] tmp = new int[count];
			Array.Copy(characters, start, tmp, 0, count);
			return new Utf32String(tmp, true);
		}

		#endregion
		
		#region IndexOf
		/// <summary>
		/// Finds the index of another Utf32String within this one.
		/// </summary>
		/// <param name="value">Value to find</param>
		/// <returns>The index of value within this string, or -1 if it isn't found</returns>
		public int IndexOf (Utf32String value)
		{
			return IndexOf(value, 0, Length);
		}

		/// <summary>
		/// Finds the index of another Utf32String within this one,
		/// starting at the specified position.
		/// </summary>
		/// <param name="value">Value to find</param>
		/// <param name="start">First position to consider when finding value within this Utf32String</param>
		/// <returns>The index of value within this string, or -1 if it isn't found</returns>
		public int IndexOf (Utf32String value, int start)
		{
			if (start < 0 || start > Length)
			{
				throw new ArgumentOutOfRangeException("start must lie within the string bounds", "start");
			}
			return IndexOf (value, start, Length-start);
		}

		/// <summary>
		/// Finds the index of another Utf32String within this one,
		/// starting at the specified position and considering the
		/// specified number of positions.
		/// </summary>
		/// <param name="value">Value to find</param>
		/// <param name="start">First position to consider when finding value within this Utf32String</param>
		/// <param name="count">Number of positions to consider</param>
		/// <returns>The index of value within this string, or -1 if it isn't found</returns>
		public int IndexOf (Utf32String value, int start, int count)
		{
			if (value == null)
			{
				throw (new System.ArgumentNullException("value"));
			}
			if (start < 0 || start > Length)
			{
				throw new ArgumentOutOfRangeException("start must lie within the string bounds", "start");
			}
			if (count < 0)
			{
				throw new ArgumentOutOfRangeException("count must be non-negative", "count");
			}
			if (start+count > Length)
			{
				throw new ArgumentOutOfRangeException
					("start+count must be less than or equal to the length of the string");
			}
			for (int i=start; i < start+count; i++)
			{
				if (i+value.Length > Length)
				{
					return -1;
				}
				int j;
				for (j=0; j < value.Length; j++)
				{
					if (characters[i+j]!=value.characters[j])
					{
						break;
					}
				}
				if (j==value.Length)
				{
					return i;
				}
			}
			return -1;
		}

		/// <summary>
		/// Finds the first index of the specified character within this string.
		/// </summary>
		/// <param name="character">Character to find</param>
		/// <returns>The index of the first occurrence of the specified character, or -1
		/// if it is not found.</returns>
		public int IndexOf (int character)
		{
			return IndexOf(character, 0, Length);
		}

		/// <summary>
		/// Finds the first index of the specified character within this string, starting
		/// at the specified position.
		/// </summary>
		/// <param name="character">Character to find</param>
		/// <param name="start">First position to consider</param>
		/// <returns>The index of the first occurrence of the specified character, or -1
		/// if it is not found.</returns>
		public int IndexOf (int character, int start)
		{
			if (start < 0 || start > Length)
			{
				throw new ArgumentOutOfRangeException("start must lie within the string bounds", "start");
			}
			return IndexOf (character, start, Length-start);
		}

		/// <summary>
		/// Finds the first index of the specified character within this string, starting
		/// at the specified position and considering the specified number of positions.
		/// </summary>
		/// <param name="character">Character to find</param>
		/// <param name="start">First position to consider</param>
		/// <param name="count">Number of positions to consider</param>
		/// <returns>The index of the first occurrence of the specified character, or -1
		/// if it is not found.</returns>
		public int IndexOf (int character, int start, int count)
		{
			if (!IsValidUtf32Char(character))
			{
				throw new ArgumentException("Invalid UTF-32 character specified", "character");
			}
			if (start < 0 || start > Length)
			{
				throw new ArgumentOutOfRangeException("start must lie within the string bounds", "start");
			}
			if (count < 0)
			{
				throw new ArgumentOutOfRangeException("count must be non-negative", "count");
			}
			if (start+count > Length)
			{
				throw new ArgumentOutOfRangeException
					("start+count must be less than or equal to the length of the string");
			}
			for (int i=start; i < start+count; i++)
			{
				if (characters[i]==character)
				{
					return i;
				}
			}
			return -1;
		}

		#endregion

		#region Comparison
		/// <summary>
		/// Compares two UTF-32 strings (in a culture-insensitive manner) for equality.
		/// </summary>
		/// <param name="other">The other string to compare this one to.</param>
		/// <returns>Whether or not this string is equal to the other one.</returns>
		public bool Equals(Utf32String other)
		{
			if (Object.ReferenceEquals(this, other))
			{
				return true;
			}
			return (CompareTo(other)==0);
		}

		/// <summary>
		/// Compares one string with another for equality.
		/// </summary>
		/// <param name="strA">The first string to compare</param>
		/// <param name="strB">The second string to compare</param>
		/// <returns>true if the strings are equivalent; false otherwise</returns>
		public static bool Equals (Utf32String strA, Utf32String strB)
		{
			return Compare(strA, strB)==0;
		}

		/// <summary>
		/// Compares the two specified strings.
		/// </summary>
		/// <param name="strA">The first string to compare</param>
		/// <param name="strB">The second string to compare</param>
		/// <returns>0 if both strings are null or they are equal; a negative number if strA is null or
		/// is lexicographically before strB; a positive number otherwise</returns>
		public static int Compare(Utf32String strA, Utf32String strB)
		{
			if (object.ReferenceEquals(strA, strB))
			{
				return 0;
			}
			if ((object)strA==null || (object)strB==null)
			{
				return ((object)strA==null ? -1 : 1);
			}
			return strA.CompareTo(strB);
		}
		#endregion
		
		#region Concatenation
		/// <summary>
		/// Concatenates an array of strings together.
		/// </summary>
		/// <param name="strings">The array of strings to concatenate.</param>
		/// <returns></returns>
		public static Utf32String Concat (params Utf32String[] strings)
		{
			if (strings==null)
			{
				throw new ArgumentNullException("strings");
			}
			int size = 0;
			foreach (Utf32String s in strings)
			{
				if (s != null)
				{
					size += s.Length;
				}
			}
			if (size==0)
			{
				return Utf32String.Empty;
			}
			int[] tmp = new int[size];
			int index=0;
			foreach (Utf32String s in strings)
			{
				if (s != null)
				{
					Array.Copy(s.characters, 0, tmp, index, s.Length);
					index += s.Length;
				}
			}
			return new Utf32String(tmp);
		}

		/// <summary>
		/// Returns a concatenation of the given strings.
		/// </summary>
		/// <param name="strA">The first string</param>
		/// <param name="strB">The second string</param>
		/// <returns>A string consisting of the first string followed by the second</returns>
		public static Utf32String Concat (Utf32String strA, Utf32String strB)
		{
			return Concat(new Utf32String[]{strA, strB});
		}

		/// <summary>
		/// Returns a concatenation of the given strings.
		/// </summary>
		/// <param name="strA">The first string</param>
		/// <param name="strB">The second string</param>
		/// <param name="strC">The third string</param>
		/// <returns>
		/// A string consisting of the first string 
		/// followed by the second, followed by the third
		/// </returns>
		public static Utf32String Concat (Utf32String strA, Utf32String strB, Utf32String strC)
		{
			return Concat(new Utf32String[]{strA, strB, strC});
		}

		/// <summary>
		/// Returns a concatenation of the given strings.
		/// </summary>
		/// <param name="strA">The first string</param>
		/// <param name="strB">The second string</param>
		/// <param name="strC">The third string</param>
		/// <param name="strD">The fourth string</param>
		/// <returns>
		/// A string consisting of the first string 
		/// followed by the second, followed by the third,
		/// followed by the fourth
		/// </returns>
		public static Utf32String Concat (Utf32String strA, Utf32String strB, Utf32String strC, Utf32String strD)
		{
			return Concat(new Utf32String[]{strA, strB, strC, strD});
		}
		#endregion

		#region ToIntArray
		/// <summary>
		/// Copies the UTF-32 characters in this string to an int array.
		/// </summary>
		/// <returns>An array of integers representing the characters in this array.</returns>
		public int[] ToInt32Array()
		{
			return (int[]) characters.Clone();
		}
		#endregion

		#region Object.* overrides
		/// <summary>
		/// Converts the UTF-32 string into a UTF-16 string, 
		/// creating surrogates if necessary.
		/// </summary>
		/// <returns>
		/// A UTF-16 string (System.String) representing the same 
		/// character data as this UTF-32 string.
		/// </returns>
		public override string ToString()
		{
			int surrogates = 0;
			// Count surrogates to start with
			foreach (int character in characters)
			{
				if (character > 0xffff)
				{
					surrogates++;
				}
			}
			char[] utf16 = new char[Length+surrogates];
			int outputIndex = 0;
			foreach (int character in characters)
			{
				if (character < 0x10000)
				{
					utf16[outputIndex++]=(char)character;
				}
				else
				{
					utf16[outputIndex++]=(char)(((character-0x10000)/0x400)+HighSurrogateStart);
					utf16[outputIndex++]=(char)(((character-0x10000)%0x400)+LowSurrogateStart);
				}
			}
			return new string(utf16);
		}

		/// <summary>
		/// Returns whether or not this UTF-32 string is equal to another object.
		/// </summary>
		/// <param name="obj">The object to compare this UTF-32 string to.</param>
		/// <returns>Whether or not this object is equal to the other one.</returns>
		public override bool Equals(object obj)
		{
			Utf32String other = obj as Utf32String;
			if (other==null)
			{
				return false;
			}
			return Equals(other);
		}

		/// <summary>
		/// Returns a hashcode formed from sampling some of the characters in this
		/// UTF-32 string. This gives a good balance between performance and hash
		/// collisions.
		/// </summary>
		/// <returns>A hashcode for this UTF-32 string.</returns>
		public override int GetHashCode()
		{
			int hash = 0;
			int step = Math.Max(Length / HashcodeSampleSize, 1);
			for (int i = 0; i < Length; i += step)
			{
				hash ^= characters[i];
			}
			return hash;
		}
		#endregion

		#region Operators
		/// <summary>
		/// Returns a concatenation of the given strings.
		/// </summary>
		/// <param name="strA">The first string</param>
		/// <param name="strB">The second string</param>
		/// <returns>A string consisting of the first string followed by the second</returns>
		public static Utf32String operator+ (Utf32String strA, Utf32String strB)
		{
			return Concat(strA, strB);
		}

		/// <summary>
		/// Determines whether two specified String objects have the same value.
		/// </summary>
		/// <param name="strA">A string or a null reference</param>
		/// <param name="strB">A string or a null reference</param>
		/// <returns>true if the value of strA is the same as the value of strB; otherwise, false</returns>
		public static bool operator== (Utf32String strA, Utf32String strB)
		{
			return Equals(strA, strB);
		}

		/// <summary>
		/// Determines whether two specified String objects have different values.
		/// </summary>
		/// <param name="strA">A string or a null reference</param>
		/// <param name="strB">A string or a null reference</param>
		/// <returns>true if the value of strA is different from the value of strB; otherwise, false</returns>
		public static bool operator!= (Utf32String strA, Utf32String strB)
		{
			return !Equals(strA, strB);
		}
		#endregion

		#region IEnumerable Members
		/// <summary>
		/// Enumerates the characters in the string.
		/// </summary>
		/// <returns>The enumerator for </returns>
		public IEnumerator GetEnumerator()
		{
			return characters.GetEnumerator();
		}
		#endregion

		#region IComparable Members
		/// <summary>
		/// Compares this string to another Utf32String.
		/// </summary>
		/// <param name="obj">The other Utf32String to compare this string to.</param>
		/// <returns>
		/// &lt;0 if this string &lt;> obj; 0 if this==object; &gt;0 if this string &gt; obj, 
		/// with the relation defines in a culture-insensitive way in lexicographic order.
		/// </returns>
		public int CompareTo(object obj)
		{
			if (obj==null)
			{
				return 1;
			}
			Utf32String other = obj as Utf32String;
			if (other==null)
			{
				throw new ArgumentException("Can only compare Utf32Strings", "obj");
			}

			int minLength = Math.Min(this.Length, other.Length);
			for (int i=0; i < minLength; i++)
			{
				int result = this[i]-other[i];
				if (result!=0)
				{
					return result;
				}
			}
			// Both strings are the same for all the characters in the shorter
			// one. The longer one is now greater, or they're the same.
			return this.Length-other.Length;
		}
		#endregion

		#region ICloneable Members

		/// <summary>
		/// Creates a shallow copy of this string.
		/// </summary>
		/// <returns>A shallow copy of this string.</returns>
		public object Clone()
		{
			return MemberwiseClone();
		}

		#endregion
	}
}
