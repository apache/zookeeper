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
package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AtomicFileOutputStreamTest extends ZKTestCase {
    private static final String TEST_STRING = "hello world";
    private static final String TEST_STRING_2 = "goodbye world";

    private File testDir;
    private File dstFile;

    @Before
    public void setupTestDir() throws IOException {
        testDir = ClientBase.createTmpDir();
        dstFile = new File(testDir, "test.txt");
    }
    @After
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

        String readBackData = ClientBase.readFile(dstFile);
        assertEquals(TEST_STRING, readBackData);
    }

    /**
     * Test case where there is no existing file
     */
    @Test
    public void testOverwriteFile() throws IOException {
        assertTrue("Creating empty dst file", dstFile.createNewFile());

        OutputStream fos = new AtomicFileOutputStream(dstFile);

        assertTrue("Empty file still exists", dstFile.exists());
        fos.write(TEST_STRING.getBytes());
        fos.flush();

        // Original contents still in place
        assertEquals("", ClientBase.readFile(dstFile));

        fos.close();

        // New contents replace original file
        String readBackData = ClientBase.readFile(dstFile);
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
        assertEquals(TEST_STRING_2, ClientBase.readFile(dstFile));

        assertEquals("Temporary file should have been cleaned up",
                dstFile.getName(), ClientBase.join(",", testDir.list()));
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
        assertEquals(TEST_STRING, ClientBase.readFile(dstFile));
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
        assertEquals(TEST_STRING, ClientBase.readFile(dstFile));
        assertEquals(1, testDir.list().length);
    }
}
