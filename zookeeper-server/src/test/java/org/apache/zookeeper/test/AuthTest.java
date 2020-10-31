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

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;
import org.junit.Test;

public class AuthTest extends ClientBase {
    static {
        // password is test
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest",
                "super:D/InIHSb7yEEbrWz8b9l71RjZJU=");    
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.test.InvalidAuthProvider");
    }

    private final CountDownLatch authFailed = new CountDownLatch(1);

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
                authFailed.countDown();
            }
            else {
                super.process(event);
            }
        }
    }

    @Test
    public void testBadAuthNotifiesWatch() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("FOO", "BAR".getBytes());
            zk.getData("/path1", false, null);
            Assert.fail("Should get auth state error");
        } catch(KeeperException.AuthFailedException e) {
            if(!authFailed.await(CONNECTION_TIMEOUT,
                    TimeUnit.MILLISECONDS))
            {
                Assert.fail("Should have called my watcher");
            }
        }
        finally {
            zk.close();
        }
    }
    
    @Test
    public void testBadAuthThenSendOtherCommands() throws Exception {
        ZooKeeper zk = createClient();     
        try {        
            zk.addAuthInfo("INVALID", "BAR".getBytes());
            zk.exists("/foobar", false);             
            zk.getData("/path1", false, null);
            Assert.fail("Should get auth state error");
        } catch(KeeperException.AuthFailedException e) {
            if(!authFailed.await(CONNECTION_TIMEOUT,
                    TimeUnit.MILLISECONDS))
            {
                Assert.fail("Should have called my watcher");
            }
        }
        finally {
            zk.close();          
        }
    }

    
    @Test
    public void testSuper() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("digest", "pat:pass".getBytes());
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL,
                    CreateMode.PERSISTENT);
            zk.close();
            // verify no auth
            zk = createClient();
            try {
                zk.getData("/path1", false, null);
                Assert.fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify bad pass Assert.fails
            zk = createClient();
            zk.addAuthInfo("digest", "pat:pass2".getBytes());
            try {
                zk.getData("/path1", false, null);
                Assert.fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify super with bad pass Assert.fails
            zk = createClient();
            zk.addAuthInfo("digest", "super:test2".getBytes());
            try {
                zk.getData("/path1", false, null);
                Assert.fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify super with correct pass success
            zk = createClient();
            zk.addAuthInfo("digest", "super:test".getBytes());
            zk.getData("/path1", false, null);
        } finally {
            zk.close();
        }
    }
    
    @Test
    public void testSuperACL() throws Exception {
    	 ZooKeeper zk = createClient();
         try {
             zk.addAuthInfo("digest", "pat:pass".getBytes());
             zk.create("/path1", null, Ids.CREATOR_ALL_ACL,
                     CreateMode.PERSISTENT);
             zk.close();
             // verify super can do anything and ignores ACLs
             zk = createClient();
             zk.addAuthInfo("digest", "super:test".getBytes());
             zk.getData("/path1", false, null);
             
             zk.setACL("/path1", Ids.READ_ACL_UNSAFE, -1);
             zk.create("/path1/foo", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
           
             
             zk.setACL("/path1", Ids.OPEN_ACL_UNSAFE, -1);
        	 
         } finally {
             zk.close();
         }
    }

    @Test(timeout = 120000)
    public void testCheckACLWhenConcurrentModificationException() throws Exception {
        // only use one zk client to have the same ServerCnxn instance.
        final ZooKeeper zk  = createClient();
        try {
            // make sure you have the path:/path and use the CLI:
            // [zk: 127.0.0.1:2180(CONNECTED) 1] addauth digest user1:12345
            // [zk: 127.0.0.1:2180(CONNECTED) 2] getAcl /path
            //      'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE=
            //      : cdrwa
            String path = "/testCheckACLWhenConcurrentModificationException";
            zk.addAuthInfo("digest", "user1:12345".getBytes());
            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.setACL(path, Ids.OPEN_ACL_UNSAFE, -1);
            int index = 100;
            CountDownLatch latch1 = new CountDownLatch(index);
            CountDownLatch latch2 = new CountDownLatch(index);
            AtomicBoolean hasConcurrentModificationException = new AtomicBoolean(false);

            //one thread
            new Thread(() -> {
                zk.addAuthInfo("digest", "user1:12345".getBytes());
                Random r = new Random(1);
                for (int i = 0; i < 10000; i++) {
                    zk.addAuthInfo("digest", ("thread-1-" + r.nextInt(1000000) + ":12345").getBytes());
                }
                int  i = 0;
                while (i ++ < index) {
                    try {
                        latch1.countDown();
                        zk.getChildren(path, null, new Stat());
                        Thread.sleep(50);
                    } catch (KeeperException.MarshallingErrorException e) {
                        hasConcurrentModificationException.set(true);
                    } catch (KeeperException | InterruptedException e2) {
                        e2.printStackTrace();
                    }
                }
            }).start();

            //another thread
            new Thread(() -> {
                Random r = new Random(1);
                int  i = 0;
                while (i ++ < index) {
                    try {
                        latch2.countDown();
                        Thread.sleep(50);
                        String id = "thread-2-" + r.nextInt(1000000) + ":12345";
                        zk.addAuthInfo("digest", id.getBytes());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            latch1.await(10, TimeUnit.SECONDS);
            latch2.await(10, TimeUnit.SECONDS);

            if (hasConcurrentModificationException.get()) {
                Assert.fail("should not have this Exception caused by ConcurrentModificationException When CheckACL");
            }
        } finally {
            zk.close();
        }
    }
}
