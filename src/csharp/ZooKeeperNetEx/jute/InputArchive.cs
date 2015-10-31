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
    /**
 * Interface that all the Deserializers have to implement.
 *
 */

    internal interface InputArchive
    {
        bool readBool(string tag);
        int readInt(string tag);
        long readLong(string tag);
        string readString(string tag);
        byte[] readBuffer(string tag);
        void readRecord(Record r, string tag);
        void startRecord(string tag);
        void endRecord(string tag);
        Index startVector(string tag);
        void endVector(string tag);
    }
}
