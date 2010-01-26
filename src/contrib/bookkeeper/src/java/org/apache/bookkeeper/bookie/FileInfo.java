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

package org.apache.bookkeeper.bookie;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * This is the file handle for a ledger's index file that maps entry ids to location.
 * It is used by LedgerCache.
 */
class FileInfo {
    private FileChannel fc;
    /**
     * The fingerprint of a ledger index file
     */
    private byte header[] = "BKLE\0\0\0\0".getBytes();
    static final long START_OF_DATA = 1024;
    private long size;
    private int useCount;
    private boolean isClosed;
    public FileInfo(File lf) throws IOException {
        fc = new RandomAccessFile(lf, "rws").getChannel();
        size = fc.size();
        if (size == 0) {
            fc.write(ByteBuffer.wrap(header));
        }
    }

    synchronized public long size() {
        long rc = size-START_OF_DATA;
        if (rc < 0) {
            rc = 0;
        }
        return rc;
    }

    synchronized public int read(ByteBuffer bb, long position) throws IOException {
        int total = 0;
        while(bb.remaining() > 0) {
            int rc = fc.read(bb, position+START_OF_DATA);
            if (rc <= 0) {
                throw new IOException("Short read");
            }
            total += rc;
        }
        return total;
    }

    synchronized public void close() throws IOException {
        isClosed = true;
        if (useCount == 0) {
            fc.close();
        }
    }

    synchronized public long write(ByteBuffer[] buffs, long position) throws IOException {
        long total = 0;
        try {
            fc.position(position+START_OF_DATA);
            while(buffs[buffs.length-1].remaining() > 0) {
                long rc = fc.write(buffs);
                if (rc <= 0) {
                    throw new IOException("Short write");
                }
                total += rc;
            }
        } finally {
            long newsize = position+START_OF_DATA+total;
            if (newsize > size) {
                size = newsize;
            }
        }
        return total;
    }

    synchronized public void use() {
        useCount++;
    }
    
    synchronized public void release() {
        useCount--;
        if (isClosed && useCount == 0) {
            try {
                fc.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
