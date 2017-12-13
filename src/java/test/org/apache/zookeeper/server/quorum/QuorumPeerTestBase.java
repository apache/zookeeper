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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.admin.JettyAdminServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumBase;

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
        final File tmpDir;

        public static final int UNSET_STATIC_CLIENTPORT = -1;
        // standalone mode doens't need myid
        public static final int UNSET_MYID = -1;

        volatile TestQPMain main;

        File baseDir;
        private int myid;
        private int clientPort;
        private String quorumCfgSection;
        private Map<String, String> otherConfigs;

        /**
         * Create a MainThread
         *
         * @param myid
         * @param clientPort
         * @param quorumCfgSection
         * @param otherConfigs
         * @param tickTime initLimit will be 10 and syncLimit will be 5
         * @throws IOException
         */
        public MainThread(int myid, int clientPort, String quorumCfgSection,
                          Map<String, String> otherConfigs, int tickTime) throws IOException {
            baseDir = ClientBase.createTmpDir();
            this.myid = myid;
            this.clientPort = clientPort;
            this.quorumCfgSection = quorumCfgSection;
            this.otherConfigs = otherConfigs;
            LOG.info("id = " + myid + " tmpDir = " + baseDir + " clientPort = "
                    + clientPort);
            confFile = new File(baseDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=" + tickTime + "\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

            tmpDir = new File(baseDir, "data");
            if (!tmpDir.mkdir()) {
                throw new IOException("Unable to mkdir " + tmpDir);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = tmpDir.toString();
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

            File myidFile = new File(tmpDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();
        }

        public MainThread(int myid, String quorumCfgSection) throws IOException {
            this(myid, quorumCfgSection, true);
        }

        public MainThread(int myid, String quorumCfgSection, Integer secureClientPort, boolean writeDynamicConfigFile)
                throws  IOException {
            this(myid, UNSET_STATIC_CLIENTPORT, JettyAdminServer.DEFAULT_PORT, secureClientPort,
                    quorumCfgSection, null, writeDynamicConfigFile, null);
        }

        public MainThread(int myid, String quorumCfgSection, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, UNSET_STATIC_CLIENTPORT, quorumCfgSection, writeDynamicConfigFile);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, null, writeDynamicConfigFile);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, boolean writeDynamicConfigFile,
                          String version) throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, null,
                    writeDynamicConfigFile, version);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, String configs)
                throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, configs, true);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                String configs)  throws IOException {
            this(myid, clientPort, adminServerPort, quorumCfgSection, configs, true);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                String configs, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, clientPort, adminServerPort, quorumCfgSection, configs, writeDynamicConfigFile, null);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                          String configs, boolean writeDynamicConfigFile, String version) throws IOException {
            this(myid, clientPort, adminServerPort, null, quorumCfgSection, configs, writeDynamicConfigFile, version);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, Integer secureClientPort,
                          String quorumCfgSection, String configs, boolean writeDynamicConfigFile, String version)
                throws IOException {
            tmpDir = ClientBase.createTmpDir();
            LOG.info("id = " + myid + " tmpDir = " + tmpDir + " clientPort = "
                    + clientPort + " adminServerPort = " + adminServerPort);

            File dataDir = new File(tmpDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("Unable to mkdir " + dataDir);
            }

            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=4000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");
            if(configs != null){
                fwriter.write(configs);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = PathUtils.normalizeFileSystemPath(dataDir.toString());

            fwriter.write("dataDir=" + dir + "\n");
            fwriter.write("admin.serverPort=" + adminServerPort + "\n");

            // For backward compatibility test, some tests create dynamic configuration
            // without setting client port.
            // This could happen both in static file or dynamic file.
            if (clientPort != UNSET_STATIC_CLIENTPORT) {
                fwriter.write("clientPort=" + clientPort + "\n");
            }

            if (secureClientPort != null) {
                fwriter.write("secureClientPort=" + secureClientPort + "\n");
            }

            if (writeDynamicConfigFile) {
                String dynamicConfigFilename = createDynamicFile(quorumCfgSection, version);
                fwriter.write("dynamicConfigFile=" + dynamicConfigFilename + "\n");
            } else {
                fwriter.write(quorumCfgSection);
            }
            fwriter.flush();
            fwriter.close();

            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();
        }

        private String createDynamicFile(String quorumCfgSection, String version)
                throws IOException {
            String filename = "zoo.cfg.dynamic";
            if( version != null ){
                filename = filename + "." + version;
            }

            File dynamicConfigFile = new File(tmpDir, filename);
            String dynamicConfigFilename = PathUtils.normalizeFileSystemPath(dynamicConfigFile.toString());

            FileWriter fDynamicConfigWriter = new FileWriter(dynamicConfigFile);
            fDynamicConfigWriter.write(quorumCfgSection);
            fDynamicConfigWriter.flush();
            fDynamicConfigWriter.close();

            return dynamicConfigFilename;
        }

        public File[] getDynamicFiles() {
            return getFilesWithPrefix("zoo.cfg.dynamic");
        }

        public File[] getFilesWithPrefix(final String prefix) {
            return tmpDir.listFiles(new FilenameFilter() {      
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(prefix);
                }});
        }

        public File getFileByName(String filename) {
            File f = new File(tmpDir.getPath(), filename);
            return f.isFile() ? f : null;
        }

        public void writeTempDynamicConfigFile(String nextQuorumCfgSection, String version)
                throws IOException {
            File nextDynamicConfigFile = new File(tmpDir,
                    "zoo.cfg" + QuorumPeerConfig.nextDynamicConfigFileSuffix);
            FileWriter fwriter = new FileWriter(nextDynamicConfigFile);
            fwriter.write(nextQuorumCfgSection
                    + "\n"
                    + "version=" + version);
            fwriter.flush();
            fwriter.close();
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection)
                throws IOException {
            this(myid, clientPort, quorumCfgSection,
                    new HashMap<String, String>());
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection,
                          Map<String, String> otherConfigs) throws IOException {
            this(myid, clientPort, quorumCfgSection, otherConfigs, 4000);
        }

        Thread currentThread;

        synchronized public void start() {
            main = getTestQPMain();
            currentThread = new Thread(this);
            currentThread.start();
        }

        public TestQPMain getTestQPMain() {
            return new TestQPMain();
        }

        public void run() {
            String args[] = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
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

        public boolean isQuorumPeerRunning() {
            return main.quorumPeer != null;
        }

        public String getPropFromStaticFile(String key) throws IOException {
            Properties props = new Properties();
            props.load(new FileReader(confFile));
            return props.getProperty(key, "");
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
