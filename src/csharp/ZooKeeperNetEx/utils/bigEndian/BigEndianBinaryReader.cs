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
 * 
 */

using System;
using System.IO;

namespace org.apache.utils
{
    /// <summary>
    /// Equivalent of System.IO.BinaryReader, but with either endianness, depending on
    /// the EndianBitConverter it is constructed with. No data is buffered in the
    /// reader; the client may seek within the stream at will.
    /// </summary>
    internal class BigEndianBinaryReader 
    {
        #region Fields not directly related to properties

        /// <summary>
        /// Buffer used for temporary storage before conversion into primitives
        /// </summary>
        private readonly byte[] byteBuffer = new byte[16];

        #endregion

        #region Constructors

        /// <summary>
        /// Constructs a new binary reader with the given bit converter, reading
        /// to the given stream, using the given encoding.
        /// </summary>
        /// <param name="stream">Stream to read data from</param>
        public BigEndianBinaryReader(Stream stream)
        {
            if (stream == null)
            {
                throw new ArgumentNullException("stream");
            }
            if (!stream.CanRead)
            {
                throw new ArgumentException("Stream isn't writable", "stream");
            }
            this.stream = stream;
        }

        #endregion

        #region Properties
        
        private readonly Stream stream;

        #endregion

        #region Public methods

        /// <summary>
        /// Reads a boolean from the stream. 1 byte is read.
        /// </summary>
        /// <returns>The boolean read</returns>
        public bool ReadBoolean()
        {
            ReadInternal(byteBuffer, 1);
            return BigEndianBitConverter.ToBoolean(byteBuffer, 0);
        }

        /// <summary>
        /// Reads a 32-bit signed integer from the stream, using the bit converter
        /// for this reader. 4 bytes are read.
        /// </summary>
        /// <returns>The 32-bit integer read</returns>
        public int ReadInt32()
        {
            ReadInternal(byteBuffer, 4);
            return BigEndianBitConverter.ToInt32(byteBuffer, 0);
        }

        /// <summary>
        /// Reads a 64-bit signed integer from the stream, using the bit converter
        /// for this reader. 8 bytes are read.
        /// </summary>
        /// <returns>The 64-bit integer read</returns>
        public long ReadInt64()
        {
            ReadInternal(byteBuffer, 8);
            return BigEndianBitConverter.ToInt64(byteBuffer, 0);
        }

        /// <summary>
        /// Reads the specified number of bytes, returning them in a new byte array.
        /// If not enough bytes are available before the end of the stream, this
        /// method will throw an IOException.
        /// </summary>
        /// <param name="count">The number of bytes to read</param>
        /// <returns>The bytes read</returns>
        public byte[] ReadBytesOrThrow(int count)
        {
            byte[] ret = new byte[count];
            ReadInternal(ret, count);
            return ret;
        }

        #endregion

        #region Private methods

        /// <summary>
        /// Reads the given number of bytes from the stream, throwing an exception
        /// if they can't all be read.
        /// </summary>
        /// <param name="data">Buffer to read into</param>
        /// <param name="size">Number of bytes to read</param>
        void ReadInternal (byte[] data, int size)
        {
            int index=0;
            while (index < size)
            {
                int read = stream.Read(data, index, size-index);
                if (read==0)
                {
                    throw new EndOfStreamException("End of stream reached with " + (size - index) + "byte(s) left to read.");
                }
                index += read;
            }
        }
        #endregion

    
    }
}
