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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.AfterClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.fail;

/**
 * Unit tests to test different exceptions scenarious in sendResponse
 */
public class ServerCxnExceptionsTest extends ClientBase {

  private static final Logger LOG = LoggerFactory.getLogger(ServerCxnExceptionsTest.class);

  private String exceptionType;

  private void NettySetup() throws Exception {
    System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
      "org.apache.zookeeper.server.NettyServerCnxnFactory");
    System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN, "org.apache.zookeeper.server.MockNettyServerCnxn");
    System.setProperty("exception.type", "NoException");
  }

  private void NIOSetup() throws Exception {
    System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
      "org.apache.zookeeper.server.NIOServerCnxnFactory");
    System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN, "org.apache.zookeeper.server.MockNIOServerCnxn");
    System.setProperty("exception.type", "NoException");
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
    System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN);
    System.clearProperty("exception.type");
  }

  @Test (timeout = 60000)
  public void testNettyIOException() throws Exception {
    tearDown();
    NettySetup();
    testIOExceptionHelper();
  }

  @Test (timeout = 60000)
  public void testNIOIOException() throws Exception {
    tearDown();
    NIOSetup();
    testIOExceptionHelper();
  }

  private void testIOExceptionHelper() throws Exception {
    System.setProperty("exception.type", "IOException");
    super.setUp();
    final ZooKeeper zk = createClient();
    final String path = "/a";
    try {
      // make sure zkclient works
      zk.create(path, "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL);
      fail("Should not come here");
      Stat stats = zk.exists(path, false);
      if (stats != null) {
        int length = stats.getDataLength();
      }
    } catch (KeeperException.ConnectionLossException cle) {
      LOG.info("ConnectionLossException: {}", cle);
    } finally {
      zk.close();
    }
  }

  @Test (timeout = 10000)
  public void testNettyNoException() throws Exception {
    tearDown();
    NettySetup();
    testZKNoExceptionHelper();
  }

  @Test (timeout = 10000)
  public void testNIONoException() throws Exception {
    tearDown();
    NIOSetup();
    testZKNoExceptionHelper();
  }

  private void testZKNoExceptionHelper() throws Exception {
    System.setProperty("exception.type", "NoException");
    super.setUp();
    final ZooKeeper zk = createClient();
    final String path = "/a";
    try {
      // make sure zkclient works
      zk.create(path, "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL);
      Stat stats = zk.exists(path, false);
      if ( stats != null ) {
        int length = stats.getDataLength();
      }
    } catch (KeeperException.ConnectionLossException cle) {
      LOG.error("ConnectionLossException: {}", cle);
      fail("No exception should be thrown");
    } catch (Throwable t) {
      // error
      LOG.error("Throwable {}", t);
      fail("No exception should be thrown");
    } finally {
      zk.close();
    }
  }
  @Test (timeout = 10000)
  public void testNettyRunTimeException() throws Exception {
    tearDown();
    NettySetup();
    testZKRunTimeExceptionHelper();
  }

  @Test (timeout = 10000)
  public void testNIORunTimeException() throws Exception {
    tearDown();
    NIOSetup();
    testZKRunTimeExceptionHelper();
  }

  private void testZKRunTimeExceptionHelper() throws Exception {
    System.setProperty("exception.type", "RunTimeException");
    super.setUp();
    final ZooKeeper zk = createClient();
    final String path = "/a";
    try {
      // make sure zkclient works
      String returnPath = zk.create(path, "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL);
      Stat stats = zk.exists(returnPath, false);
      if ( stats != null ) {
        int length = stats.getDataLength();
      }
      fail("should not reach here");
    } catch (KeeperException.ConnectionLossException cle) {
      LOG.info("ConnectionLossException: {}", cle);
    } finally {
      zk.close();
    }
  }
}
