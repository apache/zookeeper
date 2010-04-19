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
namespace Org.Apache.Jute
{
    using System.Collections.Generic;

    public interface IOutputArchive
    {
        void WriteByte(byte b, string tag);
        void WriteBool(bool b, string tag);
        void WriteInt(int i, string tag);
        void WriteLong(long l, string tag);
        void WriteFloat(float f, string tag);
        void WriteDouble(double d, string tag);
        void WriteString(string s, string tag);
        void WriteBuffer(byte[] buf, string tag);
        void WriteRecord(IRecord r, string tag);
        void StartRecord(IRecord r, string tag);
        void EndRecord(IRecord r, string tag);
        void StartVector<T>(List<T> v, string tag);
        void EndVector<T>(List<T> v, string tag);
        void StartMap(SortedDictionary<string, string> v, string tag);
        void EndMap(SortedDictionary<string, string> v, string tag);
    }
}
