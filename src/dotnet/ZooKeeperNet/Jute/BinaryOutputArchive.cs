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
    using System.Collections.Generic;
    using System.Text;

    public class BinaryOutputArchive : IOutputArchive
    {
        private readonly EndianBinaryWriter writer;

        public static BinaryOutputArchive getArchive(EndianBinaryWriter writer)
        {
            return new BinaryOutputArchive(writer);
        }

        /** Creates a new instance of BinaryOutputArchive */
        public BinaryOutputArchive(EndianBinaryWriter writer)
        {
            this.writer = writer;
        }

        public void WriteByte(byte b, string tag)
        {
            writer.Write(b);
        }

        public void WriteBool(bool b, string tag)
        {
            writer.Write(b);
        }

        public void WriteInt(int i, string tag)
        {
            writer.Write(i);
        }

        public void WriteLong(long l, string tag)
        {
            writer.Write(l);
        }

        public void WriteFloat(float f, string tag)
        {
            writer.Write(f);
        }

        public void WriteDouble(double d, string tag)
        {
            writer.Write(d);
        }

        public void WriteString(string s, string tag)
        {
            if (s == null)
            {
                WriteInt(-1, "len");
                return;
            }
            byte[] bb = Encoding.UTF8.GetBytes(s);
            WriteInt(bb.Length, "len");
            writer.Write(bb, 0, bb.Length);
        }

        public void WriteBuffer(byte[] barr, string tag)
        {
            if (barr == null)
            {
                writer.Write(-1);
                return;
            }
            writer.Write(barr.Length);
            writer.Write(barr);
        }

        public void WriteRecord(IRecord r, string tag)
        {
            if (r == null) return;

            r.Serialize(this, tag);
        }

        public void StartRecord(IRecord r, string tag) { }

        public void EndRecord(IRecord r, string tag) { }

        public void StartVector<T>(List<T> v, string tag)
        {
            if (v == null)
            {
                WriteInt(-1, tag);
                return;
            }
            WriteInt(v.Count, tag);
        }

        public void EndVector<T>(List<T> v, string tag) { }

        public void StartMap(SortedDictionary<string, string> v, string tag)
        {
            WriteInt(v.Count, tag);
        }

        public void EndMap(SortedDictionary<string, string> v, string tag) { }
    }
}
