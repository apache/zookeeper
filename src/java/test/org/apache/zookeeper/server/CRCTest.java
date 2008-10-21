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

package org.apache.zookeeper.server;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.test.ClientBase;

public class CRCTest extends TestCase implements Watcher{
    
    private static final Logger LOG = Logger.getLogger(CRCTest.class);
    private static String HOSTPORT = "127.0.0.1:2357";
    ZooKeeperServer zks;
    private CountDownLatch startSignal;
    
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());
        ServerStats.registerAsConcrete();
    }
    @Override
    protected void tearDown() throws Exception {
        ServerStats.unregister();
        LOG.info("FINISHED " + getName());
    }
    
    /**
     * corrupt a file by writing m at 500 b
     * offset
     * @param file the file to be corrupted
     * @throws IOException
     */
    private void corruptFile(File file) throws IOException {
        // corrupt the logfile
        RandomAccessFile raf  = new RandomAccessFile(file, "rw");
        byte[] b = "mahadev".getBytes();
        long writeLen = 500L;
        raf.seek(writeLen);
        //corruptting the data
        raf.write(b);
        raf.close();
    }

    /** test checksums for the logs and snapshots.
     * the reader should fail on reading 
     * a corrupt snapshot and a corrupt log
     * file
     * @throws Exception
     */
   public void testChecksums() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.snapCount = 150;
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(PORT);
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue("waiting for server being up", 
                ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, 20000, this);
        for (int i =0; i < 2000; i++) {
            zk.create("/crctest- " + i , ("/crctest- " + i).getBytes(), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        f.shutdown();
        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                           ClientBase.CONNECTION_TIMEOUT));

        File versionDir = new File(tmpDir, "version-2");
        File[] list = versionDir.listFiles();
        //there should be only two files 
        // one the snapshot and the other logFile
        File snapFile = null;
        File logFile = null;
        for (File file: list) {
            LOG.info("file is " + file);
            if (file.getName().startsWith("log")) {
                logFile = file;
                corruptFile(logFile);
            }
        }
        FileTxnLog flog = new FileTxnLog(versionDir);
        TxnIterator itr = flog.read(1);
        //we will get a checksum failure
        try {
            while (itr.next()) {
            }
            assertTrue(false);
        } catch(IOException ie) {
            LOG.info("crc corruption", ie);
        }
        itr.close();
        // find the last snapshot
        FileSnap snap = new FileSnap(versionDir);
        snapFile = snap.findMostRecentSnapshot();
        // corrupt this file
        corruptFile(snapFile);
        DataTree dt = new DataTree();
        Map<Long, Integer> sessions = 
            new ConcurrentHashMap<Long, Integer>();
        try {   
            snap.deserialize(dt, sessions);
            assertTrue(false);
        } catch(IOException ie) {
            LOG.info("checksu failure in snapshot", ie);    
        }
         
   }
    
    public void process(WatchedEvent event) {
        LOG.info("Event:" + event.getState() + " " + event.getType() + " " + event.getPath());
        if (event.getState() == KeeperState.SyncConnected
                && startSignal != null && startSignal.getCount() > 0)
        {              
            startSignal.countDown();      
        }
    }
}
