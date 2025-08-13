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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class LoggerTestTool implements AutoCloseable {
    private final ByteArrayOutputStream os;
    private PrintStream oldErr;

    private static final class DualPrintStream extends PrintStream {

        private PrintStream out2 = null;

        public DualPrintStream(OutputStream out1, PrintStream out2) {
            super(out1);
            this.out2 = out2;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            out2.write(buf, off, len);
        }

        @Override
        public void write(int b) {
            super.write(b);
            out2.write(b);
        }

        @Override
        public void flush() {
            super.flush();
            out2.flush();
        }

        @Override
        public void close() {
            super.close();
        }
    }


    public LoggerTestTool() {
        os = createLoggingStream();
    }

    public ByteArrayOutputStream getOutputStream() {
        return os;
    }

    private ByteArrayOutputStream createLoggingStream() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new DualPrintStream(baos, System.err);
        this.oldErr = System.err;
        System.setErr(ps);
        return baos;
    }

    @Override
    public void close() throws Exception {
        System.setErr(oldErr);
        os.close();
    }
}
