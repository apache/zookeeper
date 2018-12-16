/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.fail;

/**
 * TestOutputArchive creates an output archive from a given outputstream.
 */
interface TestOutputArchive {

    OutputArchive getArchive(OutputStream os) throws IOException;
}

interface TestInputArchive {

    InputArchive getArchive(InputStream is) throws IOException;
}

class TestCheckWriterReader {

    static void checkWriterAndReader(
            TestOutputArchive output, TestInputArchive input,
            TestWriter writer, TestReader reader) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            OutputArchive oa = output.getArchive(baos);
            writer.write(oa);
        } catch (IOException e) {
            fail("Should not throw IOException while writing");
        }
        InputStream is = new ByteArrayInputStream(baos.toByteArray());
        try {
            InputArchive ia = input.getArchive(is);
            reader.read(ia);
        } catch (IOException e) {
            fail("Should not throw IOException while reading back");
        }
    }

}