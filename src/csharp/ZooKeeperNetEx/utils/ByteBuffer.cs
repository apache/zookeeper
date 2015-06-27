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
 * All rights reserved.
 * 
 */
using System.Collections.Generic;
using System.IO;

namespace org.apache.utils
{
    public class ByteBuffer
    {
        public readonly MemoryStream Stream;
        private Mode mode;

        private ByteBuffer() : this(new MemoryStream())
        {
        }

        public ByteBuffer(MemoryStream stream)
        {
            Stream = stream;
        }

        public static ByteBuffer allocate(int capacity)
        {
            var buffer = new ByteBuffer {Stream = {Capacity = capacity}, mode = Mode.Write};
            return buffer;
        }

        public static ByteBuffer allocateDirect(int capacity)
        {
            return allocate(capacity);
        }
        
        public void flip()
        {
            mode = Mode.Read;
            Stream.SetLength(Stream.Position);
            Stream.Position = 0;
        }

        public void clear()
        {
            mode = Mode.Write;
            Stream.Position = 0;
        }

        public int limit()
        {
            if (mode == Mode.Write)
                return Stream.Capacity;
            else
                return (int) Stream.Length;
        }

        public int remaining()
        {
            return limit() - (int) Stream.Position;
        }

        public bool hasRemaining()
        {
            return remaining() > 0;
        }

        //'Mode' is only used to determine whether to return data length or capacity from the 'limit' method:
        private enum Mode
        {
            Read,
            Write
        }

        public IEnumerable<byte> array()
        {
            return Stream.ToArray();
        }
    }
}