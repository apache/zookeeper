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
using ZooKeeperNet.IO;

namespace Org.Apache.Jute
{
    using System;
    using System.IO;
    using System.Text;

    public class BinaryInputArchive : IInputArchive
    {
        private readonly EndianBinaryReader reader;

        static public BinaryInputArchive GetArchive(EndianBinaryReader reader)
        {
            return new BinaryInputArchive(reader);
        }

        private class BinaryIndex : IIndex
        {
            private int nelems;

            internal BinaryIndex(int nelems)
            {
                this.nelems = nelems;
            }
            public bool Done()
            {
                return (nelems <= 0);
            }
            public void Incr()
            {
                nelems--;
            }
        }
        
        /** Creates a new instance of BinaryInputArchive */
        public BinaryInputArchive(EndianBinaryReader reader)
        {
            this.reader = reader;
        }

        public byte ReadByte(string tag)
        {
            return reader.ReadByte();
        }

        public bool ReadBool(string tag)
        {
            return reader.ReadBoolean();
        }

        public int ReadInt(string tag)
        {
            return reader.ReadInt32();
        }

        public long ReadLong(string tag)
        {
            return reader.ReadInt64();
        }

        public float ReadFloat(string tag)
        {
            return reader.ReadSingle();
        }

        public double ReadDouble(string tag)
        {
            return reader.ReadDouble();
        }

        public string ReadString(string tag)
        {
            int len = reader.ReadInt32();
            if (len == -1) return null;
            var b = reader.ReadBytesOrThrow(len);
            return Encoding.UTF8.GetString(b);
        }

        static public int maxBuffer = determineMaxBuffer();


        private static int determineMaxBuffer()
        {
            string maxBufferString = Convert.ToString(4096 * 1024); // = System.getProperty("jute.maxbuffer");
            try
            {
                int buffer;
                Int32.TryParse(maxBufferString, out buffer);
                return buffer;
            }
            catch (Exception e)
            {
                return 0xfffff;
            }

        }
        public byte[] ReadBuffer(string tag)
        {
            int len = ReadInt(tag);
            if (len == -1) return null;
            if (len < 0 || len > maxBuffer)
            {
                throw new IOException("Unreasonable length = " + len);
            }
            var arr = reader.ReadBytesOrThrow(len);
            return arr;
        }

        public void ReadRecord(IRecord r, string tag)
        {
            r.Deserialize(this, tag);
        }

        public void StartRecord(string tag) { }

        public void EndRecord(string tag) { }

        public IIndex StartVector(string tag)
        {
            int len = ReadInt(tag);
            if (len == -1)
            {
                return null;
            }
            return new BinaryIndex(len);
        }

        public void EndVector(string tag) { }

        public IIndex StartMap(string tag)
        {
            return new BinaryIndex(ReadInt(tag));
        }

        public void EndMap(string tag) { }

    }
}
