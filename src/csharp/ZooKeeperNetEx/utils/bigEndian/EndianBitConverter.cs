/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * 
 * Copyright (c) 2004-2008 Jon Skeet and Marc Gravell.
 * All rights reserved.
 */

using System;

namespace org.apache.utils
{
	/// <summary>
	/// Equivalent of System.BitConverter, but with either endianness.
	/// </summary>
	internal abstract class EndianBitConverter
	{
		#region Factory properties

		static readonly BigEndianBitConverter big = new BigEndianBitConverter();
		/// <summary>
		/// Returns a big-endian bit converter instance. The same instance is
		/// always returned.
		/// </summary>
		public static BigEndianBitConverter Big
		{
			get { return big; }
		}
		#endregion
        
		#region To(PrimitiveType) conversions
		/// <summary>
		/// Returns a Boolean value converted from one byte at a specified position in a byte array.
		/// </summary>
		/// <param name="value">An array of bytes.</param>
		/// <param name="startIndex">The starting position within value.</param>
		/// <returns>true if the byte at startIndex in value is nonzero; otherwise, false.</returns>
		public bool ToBoolean (byte[] value, int startIndex)
		{
			CheckByteArgument(value, startIndex, 1);
			return BitConverter.ToBoolean(value, startIndex);
		}
        
		/// <summary>
		/// Returns a 32-bit signed integer converted from four bytes at a specified position in a byte array.
		/// </summary>
		/// <param name="value">An array of bytes.</param>
		/// <param name="startIndex">The starting position within value.</param>
		/// <returns>A 32-bit signed integer formed by four bytes beginning at startIndex.</returns>
		public int ToInt32 (byte[] value, int startIndex)
		{
			return unchecked((int) (CheckedFromBytes(value, startIndex, 4)));
		}

		/// <summary>
		/// Returns a 64-bit signed integer converted from eight bytes at a specified position in a byte array.
		/// </summary>
		/// <param name="value">An array of bytes.</param>
		/// <param name="startIndex">The starting position within value.</param>
		/// <returns>A 64-bit signed integer formed by eight bytes beginning at startIndex.</returns>
		public long ToInt64 (byte[] value, int startIndex)
		{
			return CheckedFromBytes(value, startIndex, 8);
		}

		/// <summary>
		/// Checks the given argument for validity.
		/// </summary>
		/// <param name="value">The byte array passed in</param>
		/// <param name="startIndex">The start index passed in</param>
		/// <param name="bytesRequired">The number of bytes required</param>
		/// <exception cref="ArgumentNullException">value is a null reference</exception>
		/// <exception cref="ArgumentOutOfRangeException">
		/// startIndex is less than zero or greater than the length of value minus bytesRequired.
		/// </exception>
		static void CheckByteArgument(byte[] value, int startIndex, int bytesRequired)
		{
			if (value==null)
			{
				throw new ArgumentNullException("value");
			}
			if (startIndex < 0 || startIndex > value.Length-bytesRequired)
			{
				throw new ArgumentOutOfRangeException("startIndex");
			}
		}

        /// <summary>
        /// Checks the arguments for validity before calling FromBytes
        /// (which can therefore assume the arguments are valid).
        /// </summary>
        /// <param name="value">The bytes to convert after checking</param>
        /// <param name="startIndex">The index of the first byte to convert</param>
        /// <param name="bytesToConvert">The number of bytes to convert</param>
        /// <returns></returns>
		long CheckedFromBytes(byte[] value, int startIndex, int bytesToConvert)
		{
			CheckByteArgument(value, startIndex, bytesToConvert);
			return FromBytes(value, startIndex, bytesToConvert);
		}

		/// <summary>
		/// Convert the given number of bytes from the given array, from the given start
		/// position, into a long, using the bytes as the least significant part of the long.
		/// By the time this is called, the arguments have been checked for validity.
		/// </summary>
		/// <param name="value">The bytes to convert</param>
		/// <param name="startIndex">The index of the first byte to convert</param>
		/// <param name="bytesToConvert">The number of bytes to use in the conversion</param>
		/// <returns>The converted number</returns>
		protected abstract long FromBytes(byte[] value, int startIndex, int bytesToConvert);
		#endregion
        
		#region CopyBytes conversions
		/// <summary>
		/// Copies the given number of bytes from the least-specific
		/// end of the specified value into the specified byte array, beginning
		/// at the specified index.
		/// This is used to implement the other CopyBytes methods.
		/// </summary>
		/// <param name="value">The value to copy bytes for</param>
		/// <param name="bytes">The number of significant bytes to copy</param>
		/// <param name="buffer">The byte array to copy the bytes into</param>
		/// <param name="index">The first index into the array to copy the bytes into</param>
		void CopyBytes(long value, int bytes, byte[] buffer, int index)
		{
			if (buffer==null)
			{
				throw new ArgumentNullException("buffer", "Byte array must not be null");
			}
			if (buffer.Length < index+bytes)
			{
				throw new ArgumentOutOfRangeException("buffer","Buffer not big enough for value");
			}
			CopyBytesImpl(value, bytes, buffer, index);
		}

		/// <summary>
		/// Copies the given number of bytes from the least-specific
		/// end of the specified value into the specified byte array, beginning
		/// at the specified index.
		/// This must be implemented in concrete derived classes, but the implementation
		/// may assume that the value will fit into the buffer.
		/// </summary>
		/// <param name="value">The value to copy bytes for</param>
		/// <param name="bytes">The number of significant bytes to copy</param>
		/// <param name="buffer">The byte array to copy the bytes into</param>
		/// <param name="index">The first index into the array to copy the bytes into</param>
		protected abstract void CopyBytesImpl(long value, int bytes, byte[] buffer, int index);

		/// <summary>
		/// Copies the specified Boolean value into the specified byte array,
		/// beginning at the specified index.
		/// </summary>
		/// <param name="value">A Boolean value.</param>
		/// <param name="buffer">The byte array to copy the bytes into</param>
		/// <param name="index">The first index into the array to copy the bytes into</param>
		public void CopyBytes(bool value, byte[] buffer, int index)
		{
			CopyBytes(value ? 1 : 0, 1, buffer, index);
		}

		/// <summary>
		/// Copies the specified 32-bit signed integer value into the specified byte array,
		/// beginning at the specified index.
		/// </summary>
		/// <param name="value">The number to convert.</param>
		/// <param name="buffer">The byte array to copy the bytes into</param>
		/// <param name="index">The first index into the array to copy the bytes into</param>
		public void CopyBytes(int value, byte[] buffer, int index)
		{
			CopyBytes(value, 4, buffer, index);
		}

		/// <summary>
		/// Copies the specified 64-bit signed integer value into the specified byte array,
		/// beginning at the specified index.
		/// </summary>
		/// <param name="value">The number to convert.</param>
		/// <param name="buffer">The byte array to copy the bytes into</param>
		/// <param name="index">The first index into the array to copy the bytes into</param>
		public void CopyBytes(long value, byte[] buffer, int index)
		{
			CopyBytes(value, 8, buffer, index);
		}
        
		#endregion
	}
}
