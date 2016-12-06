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

/**
 * 
 */
package org.apache.zookeeper.server.quorum;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Has some common functionality for tests that work with QuorumPeers. Override
 * process(WatchedEvent) to implement the Watcher interface
 */
public class QuorumPeerTestBase extends ZKTestCase implements Watcher {
    protected static final Logger LOG = LoggerFactory
            .getLogger(QuorumPeerTestBase.class);

    public static final int TIMEOUT = 5000;

    public void process(WatchedEvent event) {
        // ignore for this test
    }

    public static class TestQPMain extends QuorumPeerMain {
        public void shutdown() {
            // ensure it closes - in particular wait for thread to exit
            if (quorumPeer != null) {
                QuorumBase.shutdown(quorumPeer);
            }
        }
    }

    public static class MainThread implements Runnable {
        final File confFile;
        volatile TestQPMain main;
        final File dataDir;
        CountDownLatch mainFailed;
        File baseDir;
        private final int myid;
        private final int clientPort;
        private final String quorumCfgSection;
        private final Map<String, String> otherConfigs;

        public MainThread(int myid, int clientPort, String quorumCfgSection,
                Map<String, String> otherConfigs) throws IOException {
            baseDir = ClientBase.createTmpDir();
            this.myid = myid;
            this.clientPort = clientPort;
            this.quorumCfgSection = quorumCfgSection;
            this.otherConfigs = otherConfigs;
            LOG.info("id = " + myid + " tmpDir = " + baseDir + " clientPort = "
                    + clientPort);
            confFile = new File(baseDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=4000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

            dataDir = new File(baseDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("Unable to mkdir " + dataDir);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = dataDir.toString();
            String osname = java.lang.System.getProperty("os.name");
            if (osname.toLowerCase().contains("windows")) {
                dir = dir.replace('\\', '/');
            }
            fwriter.write("dataDir=" + dir + "\n");

            fwriter.write("clientPort=" + clientPort + "\n");

            // write extra configurations
            Set<Entry<String, String>> entrySet = otherConfigs.entrySet();
            for (Entry<String, String> entry : entrySet) {
                fwriter.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }

            fwriter.write(quorumCfgSection + "\n");
            fwriter.flush();
            fwriter.close();

            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection)
                throws IOException {
            this(myid, clientPort, quorumCfgSection,
                    new HashMap<String, String>());
        }

        Thread currentThread;

        synchronized public void start() {
            main = new TestQPMain();
            currentThread = new Thread(this);
            currentThread.start();
            mainFailed = new CountDownLatch(1);
        }

        public void run() {
            String args[] = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
                main.shutdown();
                mainFailed.countDown();
            } finally {
                currentThread = null;
            }
        }

        public void shutdown() throws InterruptedException {
            Thread t = currentThread;
            if (t != null && t.isAlive()) {
                main.shutdown();
                t.join(500);
            }
        }

        public void join(long timeout) throws InterruptedException {
            Thread t = currentThread;
            if (t != null) {
                t.join(timeout);
            }
        }

        public boolean isAlive() {
            Thread t = currentThread;
            return t != null && t.isAlive();
        }

        public void clean() {
            ClientBase.recursiveDelete(main.quorumPeer.getTxnFactory()
                    .getDataDir());
        }

        public QuorumPeer getQuorumPeer() {
            return main.quorumPeer;
        }

        public void deleteBaseDir() {
            ClientBase.recursiveDelete(baseDir);
        }

        public int getMyid() {
            return myid;
        }

        public int getClientPort() {
            return clientPort;
        }

        public String getQuorumCfgSection() {
            return quorumCfgSection;
        }

        public Map<String, String> getOtherConfigs() {
            return otherConfigs;
        }

        public File getConfFile() {
            return confFile;
        }
    }
}
