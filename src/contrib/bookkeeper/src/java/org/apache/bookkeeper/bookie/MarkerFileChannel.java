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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * This class is just a stub that can be used in collections with
 * FileChannels
 */
public class MarkerFileChannel extends FileChannel {

    @Override
    public void force(boolean metaData) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public FileLock lock(long position, long size, boolean shared)
            throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size)
            throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long position() throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length)
            throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long size() throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count)
            throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared)
            throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length)
            throws IOException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    protected void implCloseChannel() throws IOException {
        // TODO Auto-generated method stub

    }

}
