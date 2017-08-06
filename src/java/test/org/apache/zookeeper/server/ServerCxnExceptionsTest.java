/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.fail;

/**
 * Unit tests to test different exceptions scenarios in sendResponse
 */
@RunWith(BMUnitRunner.class)
public class ServerCxnExceptionsTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCxnExceptionsTest.class);
    private static String previousFactory = null;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        previousFactory = System.getProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        if (previousFactory != null) {
            System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, previousFactory);
        }
    }

    @Test(timeout = 60000, expected = KeeperException.ConnectionLossException.class)
    @BMRule(name = "IOExceptionNetty",
            targetClass = "org.apache.zookeeper.server.NettyServerCnxn",
            targetMethod = "sendResponse",
            action = "throw new IOException(\"Test IOException from ServerCxnExceptionsTest with Netty\");",
            targetLocation = "AT ENTRY"
    )
    public void testIOExceptionNetty() throws Exception {
        tearDown();
        nettySetup();
        testZKHelper(true);
    }

    @Test(timeout = 60000, expected = KeeperException.ConnectionLossException.class)
    @BMRule(name = "IOExceptionNIO",
            targetClass = "org.apache.zookeeper.server.NIOServerCnxn",
            targetMethod = "sendResponse",
            action = "throw new IOException(\"Test IOException from ServerCxnExceptionsTest with NIO\");",
            targetLocation = "AT ENTRY"
    )
    public void testIOExceptionNIO() throws Exception {
        tearDown();
        nioSetup();
        testZKHelper(true);
    }

    @Test(timeout = 60000)
    public void testNoExceptionNetty() throws Exception {
        tearDown();
        nettySetup();
        testZKHelper(false);
    }

    @Test(timeout = 60000)
    public void testNoExceptionNIO() throws Exception {
        tearDown();
        nioSetup();
        testZKHelper(false);
    }

    @Test(timeout = 60000, expected = KeeperException.ConnectionLossException.class)
    @BMRule(name = "RuntimeException Netty",
            targetClass = "org.apache.zookeeper.server.NettyServerCnxn",
            targetMethod = "sendResponse",
            action = "throw new RuntimeException(\"Test RuntimeException from ServerCxnExceptionsTest\")",
            targetLocation = "AT ENTRY"
    )
    public void testNettyRunTimeException() throws Exception {
        tearDown();
        nettySetup();
        testZKHelper(true);
    }

    @Test(timeout = 60000, expected = KeeperException.ConnectionLossException.class)
    @BMRule(name = "RuntimeException Netty",
            targetClass = "org.apache.zookeeper.server.NIOServerCnxn",
            targetMethod = "sendResponse",
            action = "throw new RuntimeException(\"Test RuntimeException from ServerCxnExceptionsTest\")",
            targetLocation = "AT ENTRY"
    )
    public void testNIORunTimeException() throws Exception {
        tearDown();
        nioSetup();
        testZKHelper(true);
    }

    private void nettySetup() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NettyServerCnxnFactory");
    }

    private void nioSetup() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NIOServerCnxnFactory");
    }

    private void testZKHelper(boolean shouldFail) throws Exception {
        super.setUp();
        final ZooKeeper zk = createClient();
        final String path = "/a";
        try {
            // make sure zkclient works
            zk.create(path, "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL);
            Stat stats = zk.exists(path, false);
            if (stats != null) {
                int length = stats.getDataLength();
            }
            if (shouldFail) {
                fail("sendResponse() should have thrown IOException");
            }
        }
        finally {
            try {
                zk.close();
            }
            catch (Exception e) {
                // IGNORE any exception during close
                LOG.debug("Exception during close: {}", e);
            }
        }
    }
}
