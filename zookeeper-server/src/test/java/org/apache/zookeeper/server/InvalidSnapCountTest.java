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

package org.apache.zookeeper.server;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test stand-alone server.
 *
 */
public class InvalidSnapCountTest extends ZKTestCase implements Watcher {

    protected static final Logger LOG = LoggerFactory.getLogger(InvalidSnapCountTest.class);

    public static class MainThread extends Thread {

        final File confFile;
        final TestMain main;

        public MainThread(int clientPort) throws IOException {
            super("Standalone server with clientPort:" + clientPort);
            File tmpDir = ClientBase.createTmpDir();
            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");
            fwriter.write("snapCount=1\n");

            File dataDir = new File(tmpDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("unable to mkdir " + dataDir);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = PathUtils.normalizeFileSystemPath(dataDir.toString());
            fwriter.write("dataDir=" + dir + "\n");

            fwriter.write("clientPort=" + clientPort + "\n");
            fwriter.flush();
            fwriter.close();

            main = new TestMain();
        }

        public void run() {
            String[] args = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
            }
        }

        public void shutdown() {
            main.shutdown();
        }

    }

    public static class TestMain extends ZooKeeperServerMain {

        public void shutdown() {
            super.shutdown();
        }

    }

    /**
     * Verify the ability to start a standalone server instance.
     */
    @Test
    public void testInvalidSnapCount() throws Exception {

        final int CLIENT_PORT = 3181;

        MainThread main = new MainThread(CLIENT_PORT);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        assertEquals(SyncRequestProcessor.getSnapCount(), 2);

        main.shutdown();

    }

    public void process(WatchedEvent event) {
        // ignore for this test
    }

}
