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
 *uuuuu
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "/RequuuAS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.apache.jute.Record;

public class ByteBufferRequestRecord implements RequestRecord {

    private final ByteBuffer request;

    private volatile Record record;

    public ByteBufferRequestRecord(ByteBuffer request) {
        this.request = request;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Record> T readRecord(Supplier<T> constructor) throws IOException {
        if (record != null) {
            return (T) record;
        }

        record = constructor.get();
        request.rewind();
        ByteBufferInputStream.byteBuffer2Record(request, record);
        request.rewind();
        return (T) record;
    }

    @Override
    public byte[] readBytes() {
        request.rewind();
        int len = request.remaining();
        byte[] b = new byte[len];
        request.get(b);
        request.rewind();
        return b;
    }

    @Override
    public int limit() {
        return request.limit();
    }
}
