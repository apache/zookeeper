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
 */

using org.apache.utils;
using org.apache.zookeeper;

namespace org.apache.jute
{
    using System.IO;
    using System.Text;

    internal class BinaryInputArchive : InputArchive
    {
        private readonly BigEndianBinaryReader reader;

        static public BinaryInputArchive getArchive(BigEndianBinaryReader reader)
        {
            return new BinaryInputArchive(reader);
        }

        private class BinaryIndex : Index
        {
            private int nelems;

            internal BinaryIndex(int nelems)
            {
                this.nelems = nelems;
            }
            public bool done()
            {
                return (nelems <= 0);
            }
            public void incr()
            {
                nelems--;
            }
        }
        
        /** Creates a new instance of BinaryInputArchive */
        public BinaryInputArchive(BigEndianBinaryReader reader)
        {
            this.reader = reader;
        }

        public bool readBool(string tag)
        {
            return reader.ReadBoolean();
        }

        public int readInt(string tag)
        {
            return reader.ReadInt32();
        }

        public long readLong(string tag)
        {
            return reader.ReadInt64();
        }

        public string readString(string tag)
        {
            int len = reader.ReadInt32();
            if (len == -1) return null;
            var b = reader.ReadBytesOrThrow(len);
            return Encoding.UTF8.GetString(b);
        }

        public byte[] readBuffer(string tag)
        {
            int len = readInt(tag);
            if (len == -1) return null;
            if (len < 0 || len > ClientCnxn.packetLen)
            {
                throw new IOException(new StringBuilder("Unreasonable length = ").Append(len).ToString());
            }
            var arr = reader.ReadBytesOrThrow(len);
            return arr;
        }

        public void readRecord(Record r, string tag)
        {
            r.deserialize(this, tag);
        }

        public void startRecord(string tag) { }

        public void endRecord(string tag) { }

        public Index startVector(string tag)
        {
            int len = readInt(tag);
            if (len == -1)
            {
                return null;
            }
            return new BinaryIndex(len);
        }

        public void endVector(string tag) { }
    }
}
