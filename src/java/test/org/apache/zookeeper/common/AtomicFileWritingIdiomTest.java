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
package org.apache.zookeeper.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.AtomicFileWritingIdiom.OutputStreamStatement;
import org.apache.zookeeper.common.AtomicFileWritingIdiom.WriterStatement;
import org.junit.BeforeClass;
import org.junit.Test;

public class AtomicFileWritingIdiomTest extends ZKTestCase {

    private static File tmpdir;

    @BeforeClass
    public static void createTmpDir() {
        tmpdir = new File("build/test/tmp");
        tmpdir.mkdirs();
    }

    @Test
    public void testOutputStreamSuccess() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
            @Override
            public void write(OutputStream os) throws IOException {
                os.write("after".getBytes("ASCII"));
                assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
            }
        });
        assertFalse("tmp file should have been deleted", tmp.exists());
        // content changed
        assertEquals("after", getContent(target));
        target.delete();
    }

    @Test
    public void testWriterSuccess() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        new AtomicFileWritingIdiom(target, new WriterStatement() {
            @Override
            public void write(Writer os) throws IOException {
                os.write("after");
                assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
            }
        });
        assertFalse("tmp file should have been deleted", tmp.exists());
        // content changed
        assertEquals("after", getContent(target));
        target.delete();
    }

    @Test
    public void testOutputStreamFailure() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
                @Override
                public void write(OutputStream os) throws IOException {
                    os.write("after".getBytes("ASCII"));
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new RuntimeException();
                }
            });
        } catch (RuntimeException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    @Test
    public void testWriterFailure() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new WriterStatement() {
                @Override
                public void write(Writer os) throws IOException {
                    os.write("after");
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new RuntimeException();
                }
            });
        } catch (RuntimeException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    @Test
    public void testOutputStreamFailureIOException() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
                @Override
                public void write(OutputStream os) throws IOException {
                    os.write("after".getBytes("ASCII"));
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new IOException();
                }
            });
        } catch (IOException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    @Test
    public void testWriterFailureIOException() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new WriterStatement() {
                @Override
                public void write(Writer os) throws IOException {
                    os.write("after");
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new IOException();
                }
            });
        } catch (IOException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    @Test
    public void testOutputStreamFailureError() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
                @Override
                public void write(OutputStream os) throws IOException {
                    os.write("after".getBytes("ASCII"));
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new Error();
                }
            });
        } catch (Error ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    @Test
    public void testWriterFailureError() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        createFile(target, "before");
        assertEquals("before", getContent(target));
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new WriterStatement() {
                @Override
                public void write(Writer os) throws IOException {
                    os.write("after");
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new Error();
                }
            });
        } catch (Error ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // content preserved
        assertEquals("before", getContent(target));
        target.delete();
    }

    // ************** target file does not exist

    @Test
    public void testOutputStreamSuccessNE() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        target.delete();
        assertFalse("file should not exist", target.exists());
        new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
            @Override
            public void write(OutputStream os) throws IOException {
                os.write("after".getBytes("ASCII"));
                assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
            }
        });
        // content changed
        assertEquals("after", getContent(target));
        target.delete();
    }

    @Test
    public void testWriterSuccessNE() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        target.delete();
        assertFalse("file should not exist", target.exists());
        new AtomicFileWritingIdiom(target, new WriterStatement() {
            @Override
            public void write(Writer os) throws IOException {
                os.write("after");
                assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
            }
        });
        assertFalse("tmp file should have been deleted", tmp.exists());
        // content changed
        assertEquals("after", getContent(target));
        target.delete();
    }

    @Test
    public void testOutputStreamFailureNE() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        target.delete();
        assertFalse("file should not exist", target.exists());
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new OutputStreamStatement() {
                @Override
                public void write(OutputStream os) throws IOException {
                    os.write("after".getBytes("ASCII"));
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new RuntimeException();
                }
            });
        } catch (RuntimeException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // file should not exist
        assertFalse("file should not exist", target.exists());
    }

    @Test
    public void testWriterFailureNE() throws IOException {
        File target = new File(tmpdir, "target.txt");
        final File tmp = new File(tmpdir, "target.txt.tmp");
        target.delete();
        assertFalse("file should not exist", target.exists());
        boolean exception = false;
        try {
            new AtomicFileWritingIdiom(target, new WriterStatement() {
                @Override
                public void write(Writer os) throws IOException {
                    os.write("after");
                    os.flush();
                    assertTrue("implementation of AtomicFileOutputStream has changed, update the test", tmp.exists());
                    throw new RuntimeException();
                }
            });
        } catch (RuntimeException ex) {
            exception = true;
        }
        assertFalse("tmp file should have been deleted", tmp.exists());
        assertTrue("should have raised an exception", exception);
        // file should not exist
        assertFalse("file should not exist", target.exists());
    }

    private String getContent(File file, String encoding) throws IOException {
        StringBuilder result = new StringBuilder();
        FileInputStream fis = new FileInputStream(file);
        byte[] b = new byte[20];
        int nb;
        while ((nb = fis.read(b)) != -1) {
               result.append(new String(b, 0, nb, encoding));
        }
        fis.close();
        return result.toString();
    }

    private String getContent(File file) throws IOException {
        return getContent(file, "ASCII");
    }

    private void createFile(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes("ASCII"));
        fos.close();
    }

}
