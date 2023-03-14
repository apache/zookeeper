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

package org.apache.zookeeper.common;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/*
 *  Used to perform an atomic write into a file.
 *  If there is a failure in the middle of the writing operation,
 *  the original file (if it exists) is left intact.
 *  Based on the org.apache.zookeeper.server.quorum.QuorumPeer.writeLongToFile(...) idiom
 *  using the HDFS AtomicFileOutputStream class.
 */
public class AtomicFileWritingIdiom {

    public interface OutputStreamStatement {

        void write(OutputStream os) throws IOException;

    }

    public interface WriterStatement {

        void write(Writer os) throws IOException;

    }

    public AtomicFileWritingIdiom(File targetFile, OutputStreamStatement osStmt) throws IOException {
        this(targetFile, osStmt, null);
    }

    public AtomicFileWritingIdiom(File targetFile, WriterStatement wStmt) throws IOException {
        this(targetFile, null, wStmt);
    }

    private AtomicFileWritingIdiom(
        File targetFile,
        OutputStreamStatement osStmt,
        WriterStatement wStmt) throws IOException {
        AtomicFileOutputStream out = null;
        boolean triedToClose = false;
        try {
            out = new AtomicFileOutputStream(targetFile);
            if (wStmt == null) {
                // execute output stream operation
                osStmt.write(out);
            } else {
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out));
                // execute writer operation and flush
                wStmt.write(bw);
                bw.flush();
            }
            triedToClose = true;
            // close() will do the best to clean up file/resources in case of errors
            // worst case the tmp file may still exist
            out.close();
            // everything went ok
        } finally {
            // nothing interesting to do if out == null
            if (out != null) {
                if (!triedToClose) {
                    // worst case here the tmp file/resources(fd) are not cleaned up
                    // and the caller will be notified (IOException)
                    out.abort();
                }
            }
        }
    }

}
