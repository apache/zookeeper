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

package org.apache.zookeeper.server.persistence;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * a class that keeps track of the position
 * in the input stream. The position points to offset
 * that has been consumed by the applications. It can
 * wrap buffered input streams to provide the right offset
 * for the application.
 */
public class PositionInputStream extends FilterInputStream {
    private long position;

    /**
     * Create a positioned input stream as a wrapper over a generic input stream
     * @param in underlying input stream
     */
    public PositionInputStream(InputStream in) {
        super(in);
        position = 0;
    }

    @Override
    public int read() throws IOException {
        int rc = super.read();
        if (rc > -1) {
            position++;
        }
        return rc;
    }

    @Override
    public int read(byte[] b) throws IOException {
        int rc = super.read(b);
        if (rc > 0) {
            position += rc;
        }
        return rc;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int rc = super.read(b, off, len);
        if (rc > 0) {
            position += rc;
        }
        return rc;
    }

    @Override
    public long skip(long n) throws IOException {
        long rc = super.skip(n);
        if (rc > 0) {
            position += rc;
        }
        return rc;
    }
    public long getPosition() {
        return position;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int readLimit) {
        throw new UnsupportedOperationException("mark");
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("reset");
    }
}
