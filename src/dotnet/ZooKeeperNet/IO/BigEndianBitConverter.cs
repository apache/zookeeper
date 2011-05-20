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
namespace ZooKeeperNet.IO
{
	/// <summary>
	/// Implementation of EndianBitConverter which converts to/from big-endian
	/// byte arrays.
	/// </summary>
	public sealed class BigEndianBitConverter : EndianBitConverter
	{
		/// <summary>
		/// Indicates the byte order ("endianess") in which data is converted using this class.
		/// </summary>
		/// <remarks>
		/// Different computer architectures store data using different byte orders. "Big-endian"
		/// means the most significant byte is on the left end of a word. "Little-endian" means the 
		/// most significant byte is on the right end of a word.
		/// </remarks>
		/// <returns>true if this converter is little-endian, false otherwise.</returns>
		public sealed override bool IsLittleEndian()
		{
			return false;
		}

		/// <summary>
		/// Indicates the byte order ("endianess") in which data is converted using this class.
		/// </summary>
		public sealed override Endianness Endianness 
		{ 
			get { return Endianness.BigEndian; }
		}

		/// <summary>
		/// Copies the specified number of bytes from value to buffer, starting at index.
		/// </summary>
		/// <param name="value">The value to copy</param>
		/// <param name="bytes">The number of bytes to copy</param>
		/// <param name="buffer">The buffer to copy the bytes into</param>
		/// <param name="index">The index to start at</param>
		protected override void CopyBytesImpl(long value, int bytes, byte[] buffer, int index)
		{
			int endOffset = index+bytes-1;
			for (int i=0; i < bytes; i++)
			{
				buffer[endOffset-i] = unchecked((byte)(value&0xff));
				value = value >> 8;
			}
		}
		
		/// <summary>
		/// Returns a value built from the specified number of bytes from the given buffer,
		/// starting at index.
		/// </summary>
		/// <param name="buffer">The data in byte array format</param>
		/// <param name="startIndex">The first index to use</param>
		/// <param name="bytesToConvert">The number of bytes to use</param>
		/// <returns>The value built from the given bytes</returns>
		protected override long FromBytes(byte[] buffer, int startIndex, int bytesToConvert)
		{
			long ret = 0;
			for (int i=0; i < bytesToConvert; i++)
			{
				ret = unchecked((ret << 8) | buffer[startIndex+i]);
			}
			return ret;
		}
	}
}
