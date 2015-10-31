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
namespace org.apache.jute
{
    using System.Collections.Generic;

    /**
 * Interface that alll the serializers have to implement.
 *
 */

    internal interface OutputArchive
    {
        void writeBool(bool b, string tag);
        void writeInt(int i, string tag);
        void writeLong(long l, string tag);
        void writeString(string s, string tag);
        void writeBuffer(byte[] buf, string tag);
        void writeRecord(Record r, string tag);
        void startRecord(Record r, string tag);
        void endRecord(Record r, string tag);
        void startVector<T>(List<T> v, string tag);
        void endVector<T>(List<T> v, string tag);
    }
}
