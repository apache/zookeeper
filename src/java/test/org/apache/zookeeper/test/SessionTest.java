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

import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.CreateFlags;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;
import junit.framework.TestCase;

public class SessionTest extends TestCase implements Watcher {
    static File baseTest = new File(System.getProperty("build.test.dir",
            "build"));

    protected void setUp() throws Exception {
        ServerStats.registerAsConcrete();
    }
    protected void tearDown() throws Exception {
        ServerStats.unregister();
    }
    /**
     * this test checks to see if the sessionid that was created for the
     * first zookeeper client can be reused for the second one immidiately
     * after the first client closes and the new client resues them.
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void testSessionReuse() throws IOException, InterruptedException {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(33299);
        f.startup(zs);
        Thread.sleep(4000);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:33299", 3000, this);

        long sessionId = zk.getSessionId();
        byte[] passwd = zk.getSessionPasswd();
        zk.close();
        zk = new ZooKeeper("127.0.0.1:33299", 3000, this, sessionId, passwd);
        assertEquals(sessionId, zk.getSessionId());
        zk.close();
        zs.shutdown();
        f.shutdown();

    }
    @Test
    public void testSession() throws IOException, InterruptedException, KeeperException {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(33299);
        f.startup(zs);
        Thread.sleep(2000);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:33299", 30000, this);
        zk.create("/e", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateFlags.EPHEMERAL);
        System.out.println("zk with session id " + zk.getSessionId()
                + " was destroyed!");
        // zk.close();
        Stat stat = new Stat();
        try {
            zk = new ZooKeeper("127.0.0.1:33299", 30000, this, zk
                    .getSessionId(), zk.getSessionPasswd());
            System.out.println("zk with session id " + zk.getSessionId()
                    + " was created!");
            zk.getData("/e", false, stat);
            System.out.println("After get data /e");
        } catch (KeeperException e) {
            // the zk.close() above if uncommented will close the session on the
            // server
            // in such case we get an exception here because we've tried joining
            // a closed session
        }
        zk.close();
        Thread.sleep(10000);
        zk = new ZooKeeper("127.0.0.1:33299", 30000, this);
        assertEquals(null, zk.exists("/e", false));
        System.out.println("before close zk with session id "
                + zk.getSessionId() + "!");
        zk.close();
        System.out.println("before shutdown zs!");
        zs.shutdown();
        System.out.println("after shutdown zs!");
    }

    public void process(WatcherEvent event) {
    }

}
