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
    public interface IInputArchive
    {
        byte ReadByte(string tag);
        bool ReadBool(string tag);
        int ReadInt(string tag);
        long ReadLong(string tag);
        float ReadFloat(string tag);
        double ReadDouble(string tag);
        string ReadString(string tag);
        byte[] ReadBuffer(string tag);
        void ReadRecord(IRecord r, string tag);
        void StartRecord(string tag);
        void EndRecord(string tag);
        IIndex StartVector(string tag);
        void EndVector(string tag);
        IIndex StartMap(string tag);
        void EndMap(string tag);
    }
}
