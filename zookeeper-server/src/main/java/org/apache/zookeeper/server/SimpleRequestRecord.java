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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;

public class SimpleRequestRecord implements RequestRecord {

    private final Record record;

    public SimpleRequestRecord(Record record) {
        this.record = record;
    }

    @Override
    public <T extends Record> T readRecord(Class<T> clazz) {
            return clazz.cast(record);
    }

    @Override
    public byte[] readBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            record.serialize(boa, "request");
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int limit() {
        byte[] bytes = readBytes();
        return ByteBuffer.wrap(bytes).limit();
    }
}
