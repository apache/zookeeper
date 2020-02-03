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

// This code was found and refactored from here:
// https://stackoverflow.com/questions/11182192/how-do-i-serve-https-and-http-for-jetty-from-one-port/40076056#40076056

package org.apache.zookeeper.server.admin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class ReadAheadEndpoint implements EndPoint {

    private final EndPoint endPoint;
    private final ByteBuffer start;
    private final byte[] bytes;
    private int leftToRead;
    private IOException pendingException = null;

    @Override
    public InetSocketAddress getLocalAddress() {
        return endPoint.getLocalAddress();
    }
    @Override
    public InetSocketAddress getRemoteAddress() {
        return endPoint.getRemoteAddress();
    }
    @Override
    public boolean isOpen() {
        return endPoint.isOpen();
    }
    @Override
    public long getCreatedTimeStamp() {
        return endPoint.getCreatedTimeStamp();
    }
    @Override
    public boolean isOutputShutdown() {
        return endPoint.isOutputShutdown();
    }
    @Override
    public boolean isInputShutdown() {
        return endPoint.isInputShutdown();
    }
    @Override
    public void shutdownOutput() {
        endPoint.shutdownOutput();
    }
    @Override
    public void close() {
        endPoint.close();
    }
    @Override
    public Object getTransport() {
        return endPoint.getTransport();
    }
    @Override
    public long getIdleTimeout() {
        return endPoint.getIdleTimeout();
    }
    @Override
    public Connection getConnection() {
        return endPoint.getConnection();
    }
    @Override
    public void onOpen() {
        endPoint.onOpen();
    }
    @Override
    public void onClose() {
        endPoint.onClose();
    }
    @Override
    public boolean isOptimizedForDirectBuffers() {
        return endPoint.isOptimizedForDirectBuffers();
    }
    @Override
    public boolean isFillInterested() {
        return endPoint.isFillInterested();
    }
    @Override
    public boolean tryFillInterested(Callback v) {
        return endPoint.tryFillInterested(v);
    }
    @Override
    public boolean flush(ByteBuffer... v) throws IOException {
        return endPoint.flush(v);
    }
    @Override
    public void setIdleTimeout(long v) {
        endPoint.setIdleTimeout(v);
    }
    @Override
    public void write(Callback v, ByteBuffer... b) throws WritePendingException {
        endPoint.write(v, b);
    }
    @Override
    public void setConnection(Connection v) {
        endPoint.setConnection(v);
    }
    @Override
    public void upgrade(Connection v) {
        endPoint.upgrade(v);
    }
    @Override
    public void fillInterested(Callback v) throws ReadPendingException {
        endPoint.fillInterested(v);
    }

    public ReadAheadEndpoint(final EndPoint channel, final int readAheadLength) {
        if (channel == null) {
            throw new IllegalArgumentException("channel cannot be null");
        }

        this.endPoint = channel;
        start = ByteBuffer.wrap(bytes = new byte[readAheadLength]);
        start.flip();
        leftToRead = readAheadLength;
    }

    private synchronized void readAhead() throws IOException {
        if (leftToRead > 0) {
            int n = 0;
            do {
                n = endPoint.fill(start);
            } while (n == 0 && endPoint.isOpen() && !endPoint.isInputShutdown());
            if (n == -1) {
                leftToRead = -1;
            } else {
                leftToRead -= n;
            }
            if (leftToRead <= 0) {
                start.rewind();
            }
        }
    }

    private int readFromStart(final ByteBuffer dst) throws IOException {
        final int n = Math.min(dst.remaining(), start.remaining());
        if (n > 0) {
            dst.put(bytes, start.position(), n);
            start.position(start.position() + n);
            dst.flip();
        }
        return n;
    }

    @Override
    public synchronized int fill(final ByteBuffer dst) throws IOException {
        throwPendingException();
        if (leftToRead > 0) {
            readAhead();
        }
        if (leftToRead > 0) {
            return 0;
        }
        final int sr = start.remaining();
        if (sr > 0) {
            dst.compact();
            final int n = readFromStart(dst);
            if (n < sr) {
                return n;
            }
        }
        return sr + endPoint.fill(dst);
    }

    public byte[] getBytes() {
        if (pendingException == null) {
            try {
                readAhead();
            } catch (IOException e) {
                pendingException = e;
            }
        }
        byte[] ret = new byte[bytes.length];
        System.arraycopy(bytes, 0, ret, 0, ret.length);
        return ret;
    }

    private void throwPendingException() throws IOException {
        if (pendingException != null) {
            IOException e = pendingException;
            pendingException = null;
            throw e;
        }
    }

}
