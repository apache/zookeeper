package org.apache.bookkeeper.test;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Test;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.BookieProtocol;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;


import junit.framework.TestCase;

public class BookieClientTest extends TestCase {
    static Logger LOG = Logger.getLogger(BookieClientTest.class);
    BookieServer bs;
    File tmpDir;
    int port = 13645;
    protected void setUp() throws Exception {
        tmpDir = File.createTempFile("bookie", "test");
        tmpDir.delete();
        tmpDir.mkdir();
        bs = new BookieServer(port, tmpDir, new File[] { tmpDir });
        bs.start();
    }
    protected void tearDown() throws Exception {
        bs.shutdown();
        recursiveDelete(tmpDir);
    }
    private static void recursiveDelete(File dir) {
        File children[] = dir.listFiles();
        if (children != null) {
            for(File child: children) {
                recursiveDelete(child);
            }
        }
        dir.delete();
    }
    
    static class ResultStruct {
        int rc;
        ByteBuffer entry;
    }
    ReadEntryCallback recb = new ReadEntryCallback() {

        public void readEntryComplete(int rc, long ledgerId, long entryId,
                ByteBuffer bb, Object ctx) {
            ResultStruct rs = (ResultStruct)ctx;
            synchronized(rs) {
                LOG.info("Capacity " + bb.capacity() + ", " + bb.position());
                rs.rc = rc;
                bb.position(bb.position()+16);
                //if (bb.remaining() >=4) {
                //    // Skip the len
                //    bb.position(bb.position()+4);
                //}
                rs.entry = bb.slice();
                LOG.info("Received " + bb.remaining());
                rs.notifyAll();
            }
        }
        
    };

    WriteCallback wrcb = new WriteCallback() {
        public void writeComplete(int rc, long ledgerId, long entryId,
                Object ctx) {
            if (ctx != null) {
                synchronized(ctx) {
                    ctx.notifyAll();
                }
            }
        }
    };

    @Test
    public void testWriteGaps() throws Exception {
        final Object notifyObject = new Object();
        byte[] passwd = new byte[20];
        Arrays.fill(passwd, (byte) 'a');
        
        BookieClient bc = new BookieClient("127.0.0.1", port, 50000);
        ByteBuffer bb;
        bb = createByteBuffer(1,1,1);
        bc.addEntry(1, passwd, 1, bb, wrcb, null);
        bb = createByteBuffer(2,1,2);
        bc.addEntry(1, passwd, 2, bb, wrcb, null);
        bb = createByteBuffer(3,1,3);
        bc.addEntry(1, passwd, 3, bb, wrcb, null);
        bb = createByteBuffer(5,1,5);
        bc.addEntry(1, passwd, 5, bb, wrcb, null);
        bb = createByteBuffer(7,1,7);
        bc.addEntry(1, passwd, 7, bb, wrcb, null);
        synchronized(notifyObject) {
            bb = createByteBuffer(11,1,11);
            bc.addEntry(1, passwd, 11, bb, wrcb, notifyObject);
            notifyObject.wait();
        }
        ResultStruct arc = new ResultStruct();
        synchronized(arc) {
            bc.readEntry(1, 6, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOENTRY, arc.rc);
        }
        synchronized(arc) {
            bc.readEntry(1, 7, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(7, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 1, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(1, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 2, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(2, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 3, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(3, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 4, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOENTRY, arc.rc);
        }
        synchronized(arc) {
            bc.readEntry(1, 11, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(11, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 5, recb, arc);
            arc.wait(1000);
            assertEquals(0, arc.rc);
            assertEquals(5, arc.entry.getInt());
        }
        synchronized(arc) {
            bc.readEntry(1, 10, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOENTRY, arc.rc);
        }
        synchronized(arc) {
            bc.readEntry(1, 12, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOENTRY, arc.rc);
        }
        synchronized(arc) {
            bc.readEntry(1, 13, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOENTRY, arc.rc);
        }
    }
    private ByteBuffer createByteBuffer(int i, long lid, long eid) {
        ByteBuffer bb;
        bb = ByteBuffer.allocate(4+16);
        bb.putInt(i);
        bb.putLong(lid);
        bb.putLong(eid);
        bb.flip();
        return bb;
    }
    @Test
    public void testNoLedger() throws Exception {
        ResultStruct arc = new ResultStruct();
        BookieClient bc = new BookieClient("127.0.0.1", port, 50000);
        synchronized(arc) {
            bc.readEntry(2, 13, recb, arc);
            arc.wait(1000);
            assertEquals(BookieProtocol.ENOLEDGER, arc.rc);
        }
    }
}
