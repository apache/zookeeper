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
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Front-end for serializers. Also serves as a factory for serializers.
 */
public class RecordWriter {

    private OutputArchive archive;

    static HashMap<String, Method> constructFactory() {
        HashMap<String, Method> factory = new HashMap<String, Method>();

        try {
            factory.put(
                    "binary",
                    BinaryOutputArchive.class.getDeclaredMethod("getArchive", OutputStream.class));
        } catch (SecurityException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }

        return factory;
    }

    private static HashMap<String, Method> archiveFactory = constructFactory();

    private static OutputArchive createArchive(OutputStream out, String format) {
        Method factory = archiveFactory.get(format);
        if (factory != null) {
            Object[] params = {out};
            try {
                return (OutputArchive) factory.invoke(null, params);
            } catch (IllegalArgumentException | InvocationTargetException | IllegalAccessException ex) {
                ex.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Creates a new instance of RecordWriter.
     *
     * @param out    Output stream where the records will be serialized
     * @param format Serialization format ("binary", "xml", or "csv")
     */
    public RecordWriter(OutputStream out, String format) {
        archive = createArchive(out, format);
    }

    /**
     * Serialize a record.
     *
     * @param r record to be serialized
     */
    public void write(Record r) throws IOException {
        r.serialize(archive, "");
    }
}
