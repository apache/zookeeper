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
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.junit.Assert;
import org.junit.Test;

public class SaslAuthTest extends ClientBase {
    static {
        System.setProperty("zookeeper.authProvider.1","org.apache.zookeeper.server.auth.SASLAuthenticationProvider");

        try {
            File tmpDir = createTmpDir();
            File saslConfFile = new File(tmpDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);

            fwriter.write("" +
                    "Server {\n" +
                    "          org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "          user_super=\"test\";\n" +
                    "};\n" +
                    "Client {\n" +
                    "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "       username=\"super\"\n" +
                    "       password=\"test\";\n" +
                    "};" + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config",saslConfFile.getAbsolutePath());
        }
        catch (IOException e) {
            // could not create tmp directory to hold JAAS conf file : test will fail now.
        }
    }

    private AtomicInteger authFailed = new AtomicInteger(0);
    
    @Override
    protected TestableZooKeeper createClient(String hp)
    throws IOException, InterruptedException
    {
        MyWatcher watcher = new MyWatcher();
        return createClient(watcher, hp);
    }

    private class MyWatcher extends CountdownWatcher {
        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.incrementAndGet();
            }
            else {
                super.process(event);
            }
        }
    }

    @Test
    public void testAuth() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            Thread.sleep(1000);
        } finally {
            zk.close();
        }
    }

    @Test
    public void testValidSaslIds() throws Exception {
        ZooKeeper zk = createClient();

        List<String> validIds = new ArrayList<String>();
        validIds.add("user");
        validIds.add("service/host.name.com");
        validIds.add("user@KERB.REALM");
        validIds.add("service/host.name.com@KERB.REALM");

        int i = 0;
        for(String validId: validIds) {
            List<ACL> aclList = new ArrayList<ACL>();
            ACL acl = new ACL(0,new Id("sasl",validId));
            aclList.add(acl);
            zk.create("/valid"+i,null,aclList,CreateMode.PERSISTENT);
            i++;
        }
    }

    @Test
    public void testInvalidSaslIds() throws Exception {
        ZooKeeper zk = createClient();

        List<String> invalidIds = new ArrayList<String>();
        invalidIds.add("user@KERB.REALM/server.com");
        invalidIds.add("user@KERB.REALM1@KERB.REALM2");

        int i = 0;
        for(String invalidId: invalidIds) {
            List<ACL> aclList = new ArrayList<ACL>();
            try {
                ACL acl = new ACL(0,new Id("sasl",invalidId));
                aclList.add(acl);
                zk.create("/invalid"+i,null,aclList,CreateMode.PERSISTENT);
                Assert.fail("SASLAuthenticationProvider.isValid() failed to catch invalid Id.");
            }
            catch (KeeperException.InvalidACLException e) {
                // ok.
            }
            finally {
                i++;
            }
        }
    }

}
