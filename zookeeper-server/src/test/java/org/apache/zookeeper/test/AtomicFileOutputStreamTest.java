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

package org.apache.zookeeper.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AtomicFileOutputStreamTest extends ZKTestCase {

    private static final String TEST_STRING = "hello world";
    private static final String TEST_STRING_2 = "goodbye world";

    private File testDir;
    private File dstFile;

    @BeforeEach
    public void setupTestDir() throws IOException {
        testDir = ClientBase.createEmptyTestDir();
        dstFile = new File(testDir, "test.txt");
    }
    @AfterEach
    public void cleanupTestDir() throws IOException {
        ClientBase.recursiveDelete(testDir);
    }

    /**
     * Test case where there is no existing file
     */
    @Test
    public void testWriteNewFile() throws IOException {
        OutputStream fos = new AtomicFileOutputStream(dstFile);
        assertFalse(dstFile.exists());
        fos.write(TEST_STRING.getBytes());
        fos.flush();
        assertFalse(dstFile.exists());
        fos.close();
        assertTrue(dstFile.exists());

        String readBackData = new String(Files.readAllBytes(dstFile.toPath()), UTF_8);
        assertEquals(TEST_STRING, readBackData);
    }

    /**
     * Test case where there is no existing file
     */
    @Test
    public void testOverwriteFile() throws IOException {
        assertTrue(dstFile.createNewFile(), "Creating empty dst file");

        OutputStream fos = new AtomicFileOutputStream(dstFile);

        assertTrue(dstFile.exists(), "Empty file still exists");
        fos.write(TEST_STRING.getBytes());
        fos.flush();

        // Original contents still in place
        assertEquals("", new String(Files.readAllBytes(dstFile.toPath()), UTF_8));

        fos.close();

        // New contents replace original file
        String readBackData = new String(Files.readAllBytes(dstFile.toPath()), UTF_8);
        assertEquals(TEST_STRING, readBackData);
    }

    /**
     * Test case where the flush() fails at close time - make sure that we clean
     * up after ourselves and don't touch any existing file at the destination
     */
    @Test
    public void testFailToFlush() throws IOException {
        // Create a file at destination
        FileOutputStream fos = new FileOutputStream(dstFile);
        fos.write(TEST_STRING_2.getBytes());
        fos.close();

        OutputStream failingStream = createFailingStream();
        failingStream.write(TEST_STRING.getBytes());
        try {
            failingStream.close();
            fail("Close didn't throw exception");
        } catch (IOException ioe) {
            // expected
        }

        // Should not have touched original file
        assertEquals(TEST_STRING_2, new String(Files.readAllBytes(dstFile.toPath()), UTF_8));

        assertEquals(dstFile.getName(), String.join(",", testDir.list()), "Temporary file should have been cleaned up");
    }

    /**
     * Create a stream that fails to flush at close time
     */
    private OutputStream createFailingStream() throws FileNotFoundException {
        return new AtomicFileOutputStream(dstFile) {
            @Override
            public void flush() throws IOException {
                throw new IOException("injected failure");
            }
        };
    }

    /**
     * Ensure the tmp file is cleaned up and dstFile is not created when
     * aborting a new file.
     */
    @Test
    public void testAbortNewFile() throws IOException {
        AtomicFileOutputStream fos = new AtomicFileOutputStream(dstFile);

        fos.abort();

        assertEquals(0, testDir.list().length);
    }

    /**
     * Ensure the tmp file is cleaned up and dstFile is not created when
     * aborting a new file.
     */
    @Test
    public void testAbortNewFileAfterFlush() throws IOException {
        AtomicFileOutputStream fos = new AtomicFileOutputStream(dstFile);
        fos.write(TEST_STRING.getBytes());
        fos.flush();

        fos.abort();

        assertEquals(0, testDir.list().length);
    }

    /**
     * Ensure the tmp file is cleaned up and dstFile is untouched when
     * aborting an existing file overwrite.
     */
    @Test
    public void testAbortExistingFile() throws IOException {
        FileOutputStream fos1 = new FileOutputStream(dstFile);
        fos1.write(TEST_STRING.getBytes());
        fos1.close();

        AtomicFileOutputStream fos2 = new AtomicFileOutputStream(dstFile);

        fos2.abort();

        // Should not have touched original file
        assertEquals(TEST_STRING, new String(Files.readAllBytes(dstFile.toPath()), UTF_8));
        assertEquals(1, testDir.list().length);
    }

    /**
     * Ensure the tmp file is cleaned up and dstFile is untouched when
     * aborting an existing file overwrite.
     */
    @Test
    public void testAbortExistingFileAfterFlush() throws IOException {
        FileOutputStream fos1 = new FileOutputStream(dstFile);
        fos1.write(TEST_STRING.getBytes());
        fos1.close();

        AtomicFileOutputStream fos2 = new AtomicFileOutputStream(dstFile);
        fos2.write(TEST_STRING_2.getBytes());
        fos2.flush();

        fos2.abort();

        // Should not have touched original file
        assertEquals(TEST_STRING, new String(Files.readAllBytes(dstFile.toPath()), UTF_8));
        assertEquals(1, testDir.list().length);
    }

}
