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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import junit.framework.TestCase;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.IOUtils;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactoryAccessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientBase extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(ClientBase.class);

    public static int CONNECTION_TIMEOUT = 30000;
    static final File BASETEST =
        new File(System.getProperty("build.test.dir", "build"));

    protected int port = PortAssignment.unique();
    protected String hostPort = "127.0.0.1:" + port;
    protected String ipv6HostPort = "[0:0:0:0:0:0:0:1]:" + port;
    protected int maxCnxns = 0;
    protected ServerCnxnFactory serverFactory = null;
    protected File tmpDir = null;
    
    long initialFdCount;
    
    public ClientBase() {
        super();
    }

    /**
     * In general don't use this. Only use in the special case that you
     * want to ignore results (for whatever reason) in your test. Don't
     * use empty watchers in real code!
     *
     */
    protected class NullWatcher implements Watcher {
        public void process(WatchedEvent event) { /* nada */ }
    }

    public static class CountdownWatcher implements Watcher {
        // XXX this doesn't need to be volatile! (Should probably be final)
        volatile CountDownLatch clientConnected;
        volatile boolean connected;

        public CountdownWatcher() {
            reset();
        }
        synchronized public void reset() {
            clientConnected = new CountDownLatch(1);
            connected = false;
        }
        synchronized public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected ||
                event.getState() == KeeperState.ConnectedReadOnly) {
                connected = true;
                notifyAll();
                clientConnected.countDown();
            } else {
                connected = false;
                notifyAll();
            }
        }
        synchronized public boolean isConnected() {
            return connected;
        }
        synchronized public void waitForConnected(long timeout)
            throws InterruptedException, TimeoutException
        {
            long expire = System.currentTimeMillis() + timeout;
            long left = timeout;
            while(!connected && left > 0) {
                wait(left);
                left = expire - System.currentTimeMillis();
            }
            if (!connected) {
                throw new TimeoutException("Did not connect");

            }
        }
        synchronized public void waitForDisconnected(long timeout)
            throws InterruptedException, TimeoutException
        {
            long expire = System.currentTimeMillis() + timeout;
            long left = timeout;
            while(connected && left > 0) {
                wait(left);
                left = expire - System.currentTimeMillis();
            }
            if (connected) {
                throw new TimeoutException("Did not disconnect");

            }
        }
    }

    protected TestableZooKeeper createClient()
        throws IOException, InterruptedException
    {
        return createClient(hostPort);
    }

    protected TestableZooKeeper createClient(String hp)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(watcher, hp);
    }

    protected TestableZooKeeper createClient(CountdownWatcher watcher)
        throws IOException, InterruptedException
    {
        return createClient(watcher, hostPort);
    }

    private LinkedList<ZooKeeper> allClients;
    private boolean allClientsSetup = false;

    protected TestableZooKeeper createClient(CountdownWatcher watcher, String hp)
        throws IOException, InterruptedException
    {
        return createClient(watcher, hp, CONNECTION_TIMEOUT);
    }

    protected TestableZooKeeper createClient(CountdownWatcher watcher,
            String hp, int timeout)
        throws IOException, InterruptedException
    {
        watcher.reset();
        TestableZooKeeper zk = new TestableZooKeeper(hp, timeout, watcher);
        if (!watcher.clientConnected.await(timeout, TimeUnit.MILLISECONDS))
        {
            Assert.fail("Unable to connect to server");
        }
        synchronized(this) {
            if (!allClientsSetup) {
                LOG.error("allClients never setup");
                Assert.fail("allClients never setup");
            }
            if (allClients != null) {
                allClients.add(zk);
                JMXEnv.ensureAll(getHexSessionId(zk.getSessionId()));
            } else {
                // test done - close the zk, not needed
                zk.close();
            }
        }

        return zk;
    }

    public static class HostPort {
        String host;
        int port;
        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
    public static List<HostPort> parseHostPortList(String hplist) {
        ArrayList<HostPort> alist = new ArrayList<HostPort>();
        for (String hp: hplist.split(",")) {
            int idx = hp.lastIndexOf(':');
            String host = hp.substring(0, idx);
            int port;
            try {
                port = Integer.parseInt(hp.substring(idx + 1));
            } catch(RuntimeException e) {
                throw new RuntimeException("Problem parsing " + hp + e.toString());
            }
            alist.add(new HostPort(host,port));
        }
        return alist;
    }

    public static boolean waitForServerUp(String hp, long timeout) {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                // if there are multiple hostports, just take the first one
                HostPort hpobj = parseHostPortList(hp).get(0);
                String result = send4LetterWord(hpobj.host, hpobj.port, "stat");
                if (result.startsWith("Zookeeper version:") &&
                        !result.contains("READ-ONLY")) {
                    return true;
                }
            } catch (IOException e) {
                // ignore as this is expected
                LOG.info("server " + hp + " not up " + e);
            }

            if (System.currentTimeMillis() > start + timeout) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return false;
    }
    public static boolean waitForServerDown(String hp, long timeout) {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                HostPort hpobj = parseHostPortList(hp).get(0);
                send4LetterWord(hpobj.host, hpobj.port, "stat");
            } catch (IOException e) {
                return true;
            }

            if (System.currentTimeMillis() > start + timeout) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return false;
    }

    public static boolean waitForServerState(QuorumPeer qp, int timeout,
            String serverState) {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
            if (qp.getServerState().equals(serverState))
                return true;
            if (System.currentTimeMillis() > start + timeout) {
                return false;
            }
        }
    }

    static void verifyThreadTerminated(Thread thread, long millis)
        throws InterruptedException
    {
        thread.join(millis);
        if (thread.isAlive()) {
            LOG.error("Thread " + thread.getName() + " : "
                    + Arrays.toString(thread.getStackTrace()));
            Assert.assertFalse("thread " + thread.getName()
                    + " still alive after join", true);
        }
    }


    public static File createTmpDir() throws IOException {
        return createTmpDir(BASETEST);
    }
    static File createTmpDir(File parentDir) throws IOException {
        File tmpFile = File.createTempFile("test", ".junit", parentDir);
        // don't delete tmpFile - this ensures we don't attempt to create
        // a tmpDir with a duplicate name
        File tmpDir = new File(tmpFile + ".dir");
        Assert.assertFalse(tmpDir.exists()); // never true if tmpfile does it's job
        Assert.assertTrue(tmpDir.mkdirs());

        return tmpDir;
    }
    private static int getPort(String hostPort) {
        String[] split = hostPort.split(":");
        String portstr = split[split.length-1];
        String[] pc = portstr.split("/");
        if (pc.length > 1) {
            portstr = pc[0];
        }
        return Integer.parseInt(portstr);
    }

    /**
     * Starting the given server instance
     */
    public static void startServerInstance(File dataDir,
            ServerCnxnFactory factory, String hostPort) throws IOException,
            InterruptedException {
        final int port = getPort(hostPort);
        LOG.info("STARTING server instance 127.0.0.1:{}", port);
        ZooKeeperServer zks = new ZooKeeperServer(dataDir, dataDir, 3000);
        factory.startup(zks);
        Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(
                "127.0.0.1:" + port, CONNECTION_TIMEOUT));
    }

    /**
     * This method instantiates a new server. Starting of the server
     * instance has been moved to a separate method
     * {@link ClientBase#startServerInstance(File, ServerCnxnFactory, String)}.
     * Because any exception on starting the server would leave the server
     * running and the caller would not be able to shutdown the instance. This
     * may affect other test cases.
     * 
     * @return newly created server instance
     * 
     * @see <a
     *      href="https://issues.apache.org/jira/browse/ZOOKEEPER-1852">ZOOKEEPER-1852</a>
     *      for more information.
     */
    public static ServerCnxnFactory createNewServerInstance(
            ServerCnxnFactory factory, String hostPort, int maxCnxns)
            throws IOException, InterruptedException {
        final int port = getPort(hostPort);
        LOG.info("CREATING server instance 127.0.0.1:{}", port);
        if (factory == null) {
            factory = ServerCnxnFactory.createFactory(port, maxCnxns);
        }
        return factory;
    }

    static void shutdownServerInstance(ServerCnxnFactory factory,
            String hostPort)
    {
        if (factory != null) {
            ZKDatabase zkDb = null;
            {
                ZooKeeperServer zs = getServer(factory);
                if (zs != null) {
                    zkDb = zs.getZKDatabase();
                }
            }
            factory.shutdown();
            try {
                if (zkDb != null) {
                    zkDb.close();
                }
            } catch (IOException ie) {
                LOG.warn("Error closing logs ", ie);
            }
            final int PORT = getPort(hostPort);

            Assert.assertTrue("waiting for server down",
                       ClientBase.waitForServerDown("127.0.0.1:" + PORT,
                                                    CONNECTION_TIMEOUT));
        }
    }

    /**
     * Test specific setup
     */
    public static void setupTestEnv() {
        // during the tests we run with 100K prealloc in the logs.
        // on windows systems prealloc of 64M was seen to take ~15seconds
        // resulting in test Assert.failure (client timeout on first session).
        // set env and directly in order to handle static init/gc issues
        System.setProperty("zookeeper.preAllocSize", "100");
        FileTxnLog.setPreallocSize(100 * 1024);
    }

    protected void setUpAll() throws Exception {
        allClients = new LinkedList<ZooKeeper>();
        allClientsSetup = true;
    }

    @Before
    public void setUp() throws Exception {
        /* some useful information - log the number of fds used before
         * and after a test is run. Helps to verify we are freeing resources
         * correctly. Unfortunately this only works on unix systems (the
         * only place sun has implemented as part of the mgmt bean api.
         */
        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix() == true) {
            initialFdCount = osMbean.getOpenFileDescriptorCount();  	
            LOG.info("Initial fdcount is: "
                    + initialFdCount);
        }

        setupTestEnv();

        JMXEnv.setUp();

        setUpAll();

        tmpDir = createTmpDir(BASETEST);

        startServer();

        LOG.info("Client test setup finished");
    }

    protected void startServer() throws Exception {
        LOG.info("STARTING server");
        serverFactory = createNewServerInstance(serverFactory, hostPort,
                maxCnxns);
        startServerInstance(tmpDir, serverFactory, hostPort);
        // ensure that server and data bean are registered
        Set<ObjectName> children = JMXEnv.ensureParent("InMemoryDataTree",
                "StandaloneServer_port");
        // Remove beans which are related to zk client sessions. Strong
        // assertions cannot be done for these client sessions because
        // registeration of these beans with server will happen only on their
        // respective reconnection interval
        verifyUnexpectedBeans(children);
    }

    private void verifyUnexpectedBeans(Set<ObjectName> children) {
        if (allClients != null) {
            for (ZooKeeper zkc : allClients) {
                Iterator<ObjectName> childItr = children.iterator();
                while (childItr.hasNext()) {
                    ObjectName clientBean = childItr.next();
                    if (clientBean.toString().contains(
                            getHexSessionId(zkc.getSessionId()))) {
                        LOG.info("found name:" + zkc.getSessionId()
                                + " client bean:" + clientBean.toString());
                        childItr.remove();
                    }
                }
            }
        }
        for (ObjectName bean : children) {
            LOG.info("unexpected:" + bean.toString());
        }
        TestCase.assertEquals("Unexpected bean exists!", 0, children.size());
    }

    /**
     * Returns a string representation of the given long value session id
     * 
     * @param sessionId
     *            long value of session id
     * @return string representation of session id
     */
    protected static String getHexSessionId(long sessionId) {
        return "0x" + Long.toHexString(sessionId);
    }

    protected void stopServer() throws Exception {
        LOG.info("STOPPING server");
        shutdownServerInstance(serverFactory, hostPort);
        serverFactory = null;
        // ensure no beans are leftover
        JMXEnv.ensureOnly();
    }


    protected static ZooKeeperServer getServer(ServerCnxnFactory fac) {
        ZooKeeperServer zs = ServerCnxnFactoryAccessor.getZkServer(fac);

        return zs;
    }

    protected void tearDownAll() throws Exception {
        synchronized (this) {
            if (allClients != null) for (ZooKeeper zk : allClients) {
                try {
                    if (zk != null)
                        zk.close();
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt", e);
                }
            }
            allClients = null;
        }
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("tearDown starting");

        tearDownAll();

        stopServer();

        if (tmpDir != null) {
            Assert.assertTrue("delete " + tmpDir.toString(), recursiveDelete(tmpDir));
        }

        // This has to be set to null when the same instance of this class is reused between test cases
        serverFactory = null;

        JMXEnv.tearDown();

        /* some useful information - log the number of fds used before
         * and after a test is run. Helps to verify we are freeing resources
         * correctly. Unfortunately this only works on unix systems (the
         * only place sun has implemented as part of the mgmt bean api.
         */
        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix() == true) {
            long fdCount = osMbean.getOpenFileDescriptorCount();     
            String message = "fdcount after test is: "
                    + fdCount + " at start it was " + initialFdCount;
            LOG.info(message);
            if (fdCount > initialFdCount) {
                LOG.info("sleeping for 20 secs");
                //Thread.sleep(60000);
                //assertTrue(message, fdCount <= initialFdCount);
            }
        }
    }

    public static MBeanServerConnection jmxConn() throws IOException {
        return JMXEnv.conn();
    }

    public static boolean recursiveDelete(File d) {
        if (d.isDirectory()) {
            File children[] = d.listFiles();
            for (File f : children) {
                Assert.assertTrue("delete " + f.toString(), recursiveDelete(f));
            }
        }
        return d.delete();
    }

    public static void logAllStackTraces() {
        StringBuilder sb = new StringBuilder();
        sb.append("Starting logAllStackTraces()\n");
        Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
        for (Entry<Thread, StackTraceElement[]> e: threads.entrySet()) {
            sb.append("Thread " + e.getKey().getName() + "\n");
            for (StackTraceElement elem: e.getValue()) {
                sb.append("\tat " + elem + "\n");
            }
        }
        sb.append("Ending logAllStackTraces()\n");
        LOG.error(sb.toString());
    }

    /*
     * Verify that all of the servers see the same number of nodes
     * at the root
     */
    void verifyRootOfAllServersMatch(String hostPort)
        throws InterruptedException, KeeperException, IOException
    {
        String parts[] = hostPort.split(",");

        // run through till the counts no longer change on each server
        // max 15 tries, with 2 second sleeps, so approx 30 seconds
        int[] counts = new int[parts.length];
        int failed = 0;
        for (int j = 0; j < 100; j++) {
            int newcounts[] = new int[parts.length];
            int i = 0;
            for (String hp : parts) {
                try {
                    ZooKeeper zk = createClient(hp);

                    try {
                        newcounts[i++] = zk.getChildren("/", false).size();
                    } finally {
                        zk.close();
                    }
                } catch (Throwable t) {
                    failed++;
                    // if session creation Assert.fails dump the thread stack
                    // and try the next server
                    logAllStackTraces();
                }
            }

            if (Arrays.equals(newcounts, counts)) {
                LOG.info("Found match with array:"
                        + Arrays.toString(newcounts));
                counts = newcounts;
                break;
            } else {
                counts = newcounts;
                Thread.sleep(10000);
            }

            // don't keep this up too long, will Assert.assert false below
            if (failed > 10) {
                break;
            }
        }

        // verify all the servers reporting same number of nodes
        String logmsg = "node count not consistent{} {}";
        for (int i = 1; i < parts.length; i++) {
            if (counts[i-1] != counts[i]) {            	
                LOG.error(logmsg, Integer.valueOf(counts[i-1]), Integer.valueOf(counts[i]));
            } else {
                LOG.info(logmsg, Integer.valueOf(counts[i-1]), Integer.valueOf(counts[i]));
            }
        }
    }

    public static String readFile(File file) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        BufferedInputStream is = new BufferedInputStream(new FileInputStream(file));
        try {
            IOUtils.copyBytes(is, os, 1024, true);
        } finally {
            is.close();
        }
        return os.toString();
    }

    public static String join(String separator, Object[] parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object part : parts) {
            if (!first) {
                sb.append(separator);
                first = false;
            }
            sb.append(part);
        }
        return sb.toString();
    }

    public static ZooKeeper createZKClient(String cxnString) throws Exception {
        return createZKClient(cxnString, CONNECTION_TIMEOUT);
    }

    /**
     * Returns ZooKeeper client after connecting to ZooKeeper Server. Session
     * timeout is {@link #CONNECTION_TIMEOUT}
     *
     * @param cxnString
     *            connection string in the form of host:port
     * @param sessionTimeout
     * @throws IOException
     *             in cases of network failure
     */
    public static ZooKeeper createZKClient(String cxnString, int sessionTimeout) throws IOException {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(cxnString, sessionTimeout, watcher);
        try {
            watcher.waitForConnected(CONNECTION_TIMEOUT);
        } catch (InterruptedException e) {
            Assert.fail("ZooKeeper client can not connect to " + cxnString);
        }
        catch (TimeoutException e) {
            Assert.fail("ZooKeeper client can not connect to " + cxnString);
        }
        return zk;
    }
}
