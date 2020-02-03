/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jute;

import java.io.IOException;
import java.util.List;
import java.util.TreeMap;

/**
 * Interface that all the serializers have to implement.
 *
 */
public interface OutputArchive {

    void writeByte(byte b, String tag) throws IOException;

    void writeBool(boolean b, String tag) throws IOException;

    void writeInt(int i, String tag) throws IOException;

    void writeLong(long l, String tag) throws IOException;

    void writeFloat(float f, String tag) throws IOException;

    void writeDouble(double d, String tag) throws IOException;

    void writeString(String s, String tag) throws IOException;

    void writeBuffer(byte[] buf, String tag)
            throws IOException;

    void writeRecord(Record r, String tag) throws IOException;

    void startRecord(Record r, String tag) throws IOException;

    void endRecord(Record r, String tag) throws IOException;

    void startVector(List<?> v, String tag) throws IOException;

    void endVector(List<?> v, String tag) throws IOException;

    void startMap(TreeMap<?, ?> v, String tag) throws IOException;

    void endMap(TreeMap<?, ?> v, String tag) throws IOException;

}
