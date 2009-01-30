package org.apache.bookkeeper.util;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;

/**
 * Base class for tests.
 */

public abstract class ClientBase extends TestCase {
    protected static final Logger LOG = Logger.getLogger(ClientBase.class);

    public static final int CONNECTION_TIMEOUT = 30000;
    static final File BASETEST =
        new File(System.getProperty("build.test.dir", "build"));

    protected String hostPort = "127.0.0.1:33221";
    protected NIOServerCnxn.Factory serverFactory = null;
    protected File tmpDir = null;

    public ClientBase() {
        super();
    }

    public ClientBase(String name) {
        super(name);
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

    protected static class CountdownWatcher implements Watcher {
        volatile CountDownLatch clientConnected = new CountDownLatch(1);

        public void process(WatchedEvent event) {
            if (event.getState() == Event.KeeperState.SyncConnected) {
                clientConnected.countDown();
            }
        }
    }
    
    protected ZooKeeper createClient()
        throws IOException, InterruptedException
    {
        return createClient(hostPort);
    }

    protected ZooKeeper createClient(String hp)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(watcher, hp);
    }

    protected ZooKeeper createClient(CountdownWatcher watcher, String hp)
        throws IOException, InterruptedException
    {
        ZooKeeper zk = new ZooKeeper(hp, 20000, watcher);
        if (!watcher.clientConnected.await(CONNECTION_TIMEOUT,
                TimeUnit.MILLISECONDS))
        {
            fail("Unable to connect to server");
        }
        return zk;
    }

    public static boolean waitForServerUp(String hp, long timeout) {
        long start = System.currentTimeMillis();
        String split[] = hp.split(":");
        String host = split[0];
        int port = Integer.parseInt(split[1]);
        while (true) {
            try {
                Socket sock = new Socket(host, port);
                BufferedReader reader = null;
                try {
                    OutputStream outstream = sock.getOutputStream();
                    outstream.write("stat".getBytes());
                    outstream.flush();

                    reader =
                        new BufferedReader(
                                new InputStreamReader(sock.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && line.startsWith("Zookeeper version:")) {
                    	LOG.info("Server UP");
                        return true;
                    }
                } finally {
                    sock.close();
                    if (reader != null) {
                        reader.close();
                    }
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
        String split[] = hp.split(":");
        String host = split[0];
        int port = Integer.parseInt(split[1]);
        while (true) {
            try {
                Socket sock = new Socket(host, port);
                try {
                    OutputStream outstream = sock.getOutputStream();
                    outstream.write("stat".getBytes());
                    outstream.flush();
                } finally {
                    sock.close();
                }
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
    
    static void verifyThreadTerminated(Thread thread, long millis)
        throws InterruptedException
    {
        thread.join(millis);
        if (thread.isAlive()) {
            LOG.error("Thread " + thread.getName() + " : "
                    + Arrays.toString(thread.getStackTrace()));
            assertFalse("thread " + thread.getName() 
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
        assertFalse(tmpDir.exists()); // never true if tmpfile does it's job
        assertTrue(tmpDir.mkdirs());
        
        return tmpDir;
    }
    
    static NIOServerCnxn.Factory createNewServerInstance(File dataDir,
            NIOServerCnxn.Factory factory, String hostPort)
        throws IOException, InterruptedException 
    {
        ZooKeeperServer zks = new ZooKeeperServer(dataDir, dataDir, 3000);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        if (factory == null) {
            factory = new NIOServerCnxn.Factory(PORT);
        }
        factory.startup(zks);

        assertTrue("waiting for server up",
                   ClientBase.waitForServerUp("127.0.0.1:" + PORT,
                                              CONNECTION_TIMEOUT));

        return factory;
    }
    
    static void shutdownServerInstance(NIOServerCnxn.Factory factory,
            String hostPort)
    {
        if (factory != null) {
            factory.shutdown();
            final int PORT = Integer.parseInt(hostPort.split(":")[1]);

            assertTrue("waiting for server down",
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
        // resulting in test failure (client timeout on first session).
        // set env and directly in order to handle static init/gc issues
        System.setProperty("zookeeper.preAllocSize", "100");
        FileTxnLog.setPreallocSize(100);
    }
    
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());

        //ServerStats.registerAsConcrete();

        tmpDir = createTmpDir(BASETEST);
        
        setupTestEnv();
        serverFactory =
            createNewServerInstance(tmpDir, serverFactory, hostPort);
        
        LOG.info("Client test setup finished");
    }

    @Override
    protected void tearDown() throws Exception {
        LOG.info("tearDown starting");

        shutdownServerInstance(serverFactory, hostPort);
        
        if (tmpDir != null) {
            //assertTrue("delete " + tmpDir.toString(), recursiveDelete(tmpDir));
            // FIXME see ZOOKEEPER-121 replace following line with previous
            recursiveDelete(tmpDir);
        }

        //ServerStats.unregister();

        LOG.info("FINISHED " + getName());
    }

    private static boolean recursiveDelete(File d) {
        if (d.isDirectory()) {
            File children[] = d.listFiles();
            for (File f : children) {
                //assertTrue("delete " + f.toString(), recursiveDelete(f));
                // FIXME see ZOOKEEPER-121 replace following line with previous
                recursiveDelete(f);
            }
        }
        return d.delete();
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
        for (int j = 0; j < 100; j++) {
            int newcounts[] = new int[parts.length];
            int i = 0;
            for (String hp : parts) {
                ZooKeeper zk = createClient(hp);
                try {
                    newcounts[i++] = zk.getChildren("/", false).size();
                } finally {
                    zk.close();
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
        }

        // verify all the servers reporting same number of nodes
        for (int i = 1; i < parts.length; i++) {
            assertEquals("node count not consistent", counts[i-1], counts[i]);
        }
    }
}
