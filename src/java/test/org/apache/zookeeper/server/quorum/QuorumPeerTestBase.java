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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumBase;

/**
 * Has some common functionality for tests that work with QuorumPeers.
 * Override process(WatchedEvent) to implement the Watcher interface
 */
public class QuorumPeerTestBase extends TestCase implements Watcher {
    protected static final Logger LOG =
        Logger.getLogger(QuorumPeerTestBase.class);
    
    public void process(WatchedEvent event) {
        // ignore for this test
    }

    public static  class TestQPMain extends QuorumPeerMain {
        public void shutdown() {
            // ensure it closes - in particular wait for thread to exit
            QuorumBase.shutdown(quorumPeer);
        }
    }

    public static class MainThread extends Thread {
        final File confFile;
        final TestQPMain main;

        public MainThread(int myid, int clientPort, String quorumCfgSection)
            throws IOException
        {
            super("QuorumPeer with myid:" + myid
                    + " and clientPort:" + clientPort);
            File tmpDir = ClientBase.createTmpDir();
            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

            File dataDir = new File(tmpDir, "data");
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
            fwriter.write(quorumCfgSection + "\n");
            fwriter.flush();
            fwriter.close();

            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();

            main = new TestQPMain();
        }

        public void run() {
            String args[] = new String[1];
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
}
