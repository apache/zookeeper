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

package org.apache.zookeeper.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.server.persistence.SnapStream.StreamMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SnapStreamTest {

    @AfterEach
    public void tearDown() {
        System.clearProperty(SnapStream.ZOOKEEPER_SHAPSHOT_STREAM_MODE);
        SnapStream.setStreamMode(StreamMode.DEFAULT_MODE);
    }

    @Test
    public void testStreamMode() {
        assertEquals(StreamMode.CHECKED.getName(), "");
        assertEquals(StreamMode.CHECKED.getFileExtension(), "");
        assertEquals(StreamMode.CHECKED, StreamMode.fromString("name"));
        assertEquals(StreamMode.GZIP.getName(), "gz");
        assertEquals(StreamMode.GZIP.getFileExtension(), ".gz");
        assertEquals(StreamMode.GZIP, StreamMode.fromString("gz"));
        assertEquals(StreamMode.SNAPPY.getName(), "snappy");
        assertEquals(StreamMode.SNAPPY.getFileExtension(), ".snappy");
        assertEquals(StreamMode.SNAPPY, StreamMode.fromString("snappy"));
    }

    @Test
    public void testGetStreamMode() {
        assertEquals(StreamMode.CHECKED, SnapStream.getStreamMode("snapshot.180000e3a2"), "expected to return un-compressed stream");
        assertEquals(StreamMode.SNAPPY, SnapStream.getStreamMode("snapshot.180000e3a2.snappy"), "expected to return snappy stream");
        assertEquals(StreamMode.GZIP, SnapStream.getStreamMode("snapshot.180000e3a2.gz"), "expected to return gzip stream");
    }

    @Test
    public void testSerializeDeserializeWithChecked(@TempDir File tmpDir) throws IOException {
        testSerializeDeserialize(StreamMode.CHECKED, "", tmpDir);
    }

    @Test
    public void testSerializeDeserializeWithSNAPPY(@TempDir File tmpDir) throws IOException {
        testSerializeDeserialize(StreamMode.SNAPPY, ".snappy", tmpDir);
    }

    @Test
    public void testSerializeDeserializeWithGZIP(@TempDir File tmpDir) throws IOException {
        testSerializeDeserialize(StreamMode.GZIP, ".gz", tmpDir);
    }

    private void testSerializeDeserialize(StreamMode mode, String fileSuffix, File tmpDir) throws IOException {
        testSerializeDeserialize(mode, fileSuffix, false, tmpDir);
        testSerializeDeserialize(mode, fileSuffix, true, tmpDir);
    }

    private void testSerializeDeserialize(StreamMode mode, String fileSuffix, boolean fsync, File tmpDir) throws IOException {
        SnapStream.setStreamMode(mode);

        // serialize with gzip stream
        File file = new File(tmpDir, "snapshot.180000e3a2" + fileSuffix);
        CheckedOutputStream os = SnapStream.getOutputStream(file, fsync);
        OutputArchive oa = BinaryOutputArchive.getArchive(os);
        FileHeader header = new FileHeader(FileSnap.SNAP_MAGIC, 2, 1);
        header.serialize(oa, "fileheader");
        SnapStream.sealStream(os, oa);
        os.flush();
        os.close();

        assertTrue(SnapStream.isValidSnapshot(file));

        // deserialize with gzip stream
        CheckedInputStream is = SnapStream.getInputStream(file);
        InputArchive ia = BinaryInputArchive.getArchive(is);
        FileHeader restoredHeader = new FileHeader();
        restoredHeader.deserialize(ia, "fileheader");
        assertEquals(restoredHeader, header, "magic not the same");
        SnapStream.checkSealIntegrity(is, ia);
    }

    private void checkInvalidSnapshot(String filename, boolean fsync, File tmpDir) throws IOException {
        // set the output stream mode to CHECKED
        SnapStream.setStreamMode(StreamMode.CHECKED);

        // serialize to CHECKED file without magic header
        File file = new File(tmpDir, filename);
        OutputStream os = SnapStream.getOutputStream(file, fsync);
        os.write(1);
        os.flush();
        os.close();
        assertFalse(SnapStream.isValidSnapshot(file));
    }

    private void checkInvalidSnapshot(String filename, File tmpDir) throws IOException {
        checkInvalidSnapshot(filename, false, tmpDir);
        checkInvalidSnapshot(filename, true, tmpDir);
    }

    /*
        For this test a single tempDirectory will be created but the checkInvalidsnapshot will create
        multiple files within the directory for the tests.
     */
    @Test
    public void testInvalidSnapshot(@TempDir File tmpDir) throws IOException {
        assertFalse(SnapStream.isValidSnapshot(null));

        checkInvalidSnapshot("snapshot.180000e3a2", tmpDir);
        checkInvalidSnapshot("snapshot.180000e3a2.gz", tmpDir);
        checkInvalidSnapshot("snapshot.180000e3a2.snappy", tmpDir);
    }

}
