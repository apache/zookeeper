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

/**
 * Interface that all the Deserializers have to implement.
 *
 */
public interface InputArchive {

    byte readByte(String tag) throws IOException;

    boolean readBool(String tag) throws IOException;

    int readInt(String tag) throws IOException;

    long readLong(String tag) throws IOException;

    float readFloat(String tag) throws IOException;

    double readDouble(String tag) throws IOException;

    String readString(String tag) throws IOException;

    byte[] readBuffer(String tag) throws IOException;

    void readRecord(Record r, String tag) throws IOException;

    void startRecord(String tag) throws IOException;

    void endRecord(String tag) throws IOException;

    Index startVector(String tag) throws IOException;

    void endVector(String tag) throws IOException;

    Index startMap(String tag) throws IOException;

    void endMap(String tag) throws IOException;

}
