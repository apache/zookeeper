/**
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
    // 读取byte类型
    public byte readByte(String tag) throws IOException;
    // 读取boolean类型
    public boolean readBool(String tag) throws IOException;
    // 读取int类型
    public int readInt(String tag) throws IOException;
    // 读取long类型
    public long readLong(String tag) throws IOException;
    // 读取float类型
    public float readFloat(String tag) throws IOException;
    // 读取double类型
    public double readDouble(String tag) throws IOException;
    // 读取String类型
    public String readString(String tag) throws IOException;
    // 通过缓冲方式读取
    public byte[] readBuffer(String tag) throws IOException;
    // 开始读取记录
    public void readRecord(Record r, String tag) throws IOException;
    // 开始读取记录
    public void startRecord(String tag) throws IOException;
    // 结束读取记录
    public void endRecord(String tag) throws IOException;
    // 开始读取向量
    public Index startVector(String tag) throws IOException;
    // 结束读取向量
    public void endVector(String tag) throws IOException;
    // 开始读取Map
    public Index startMap(String tag) throws IOException;
    // 结束读取Map
    public void endMap(String tag) throws IOException;
}
