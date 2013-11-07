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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Provides a buffering layer in front of a FileChannel.
 */
public class BufferedChannel 
{
    ByteBuffer writeBuffer;
    ByteBuffer readBuffer;
    private FileChannel bc;
    long position;
    int capacity;
    long readBufferStartPosition;
    long writeBufferStartPosition;
    BufferedChannel(FileChannel bc, int capacity) throws IOException {
        this.bc = bc;
        this.capacity = capacity;
        position = bc.position();
        writeBufferStartPosition = position;
    }
/*    public void close() throws IOException {
        bc.close();
    }
*/
//    public boolean isOpen() {
//        return bc.isOpen();
//    }

    synchronized public int write(ByteBuffer src) throws IOException {
        int copied = 0;
        if (writeBuffer == null) {
            writeBuffer = ByteBuffer.allocateDirect(capacity);
        }
        while(src.remaining() > 0) {
            int truncated = 0;
            if (writeBuffer.remaining() < src.remaining()) {
                truncated = src.remaining() - writeBuffer.remaining();
                src.limit(src.limit()-truncated);
            }
            copied += src.remaining();
            writeBuffer.put(src);
            src.limit(src.limit()+truncated);
            if (writeBuffer.remaining() == 0) {
                writeBuffer.flip();
                bc.write(writeBuffer);
                writeBuffer.clear();
                writeBufferStartPosition = bc.position();
            }
        }
        position += copied;
        return copied;
    }
    
    public long position() {
        return position;
    }
    
    public void flush(boolean sync) throws IOException {
        synchronized(this) {
            if (writeBuffer == null) {
                return;
            }
            writeBuffer.flip();
            bc.write(writeBuffer);
            writeBuffer.clear();
            writeBufferStartPosition = bc.position();
        }
        if (sync) {
            bc.force(false);
        }
    }

    /*public Channel getInternalChannel() {
        return bc;
    }*/
    synchronized public int read(ByteBuffer buff, long pos) throws IOException {
        if (readBuffer == null) {
            readBuffer = ByteBuffer.allocateDirect(capacity);
            readBufferStartPosition = Long.MIN_VALUE;
        }
        int rc = buff.remaining();
        while(buff.remaining() > 0) {
            // check if it is in the write buffer    
            if (writeBuffer != null && writeBufferStartPosition <= pos) {
                long positionInBuffer = pos - writeBufferStartPosition;
                long bytesToCopy = writeBuffer.position()-positionInBuffer;
                if (bytesToCopy > buff.remaining()) {
                    bytesToCopy = buff.remaining();
                }
                if (bytesToCopy == 0) {
                    throw new IOException("Read past EOF");
                }
                ByteBuffer src = writeBuffer.duplicate();
                src.position((int) positionInBuffer);
                src.limit((int) (positionInBuffer+bytesToCopy));
                buff.put(src);
                pos+= bytesToCopy;
                // first check if there is anything we can grab from the readBuffer
            } else if (readBufferStartPosition <= pos && pos < readBufferStartPosition+readBuffer.capacity()) {
                long positionInBuffer = pos - readBufferStartPosition;
                long bytesToCopy = readBuffer.capacity()-positionInBuffer;
                if (bytesToCopy > buff.remaining()) {
                    bytesToCopy = buff.remaining();
                }
                ByteBuffer src = readBuffer.duplicate();
                src.position((int) positionInBuffer);
                src.limit((int) (positionInBuffer+bytesToCopy));
                buff.put(src);
                pos += bytesToCopy;
            // let's read it
            } else {
                readBufferStartPosition = pos;
                readBuffer.clear();
                // make sure that we don't overlap with the write buffer
                if (readBufferStartPosition + readBuffer.capacity() >= writeBufferStartPosition) {
                    readBufferStartPosition = writeBufferStartPosition - readBuffer.capacity();
                    if (readBufferStartPosition < 0) {
                        readBuffer.put(LedgerEntryPage.zeroPage, 0, (int)-readBufferStartPosition);
                    }
                }
                while(readBuffer.remaining() > 0) {
                    if (bc.read(readBuffer, readBufferStartPosition+readBuffer.position()) <= 0) {
                        throw new IOException("Short read");
                    }
                }
                readBuffer.put(LedgerEntryPage.zeroPage, 0, readBuffer.remaining());
                readBuffer.clear();
            }
        }
        return rc;
    }
}
