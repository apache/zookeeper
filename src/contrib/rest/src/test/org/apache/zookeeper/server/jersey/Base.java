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

package org.apache.zookeeper.server.jersey;

import java.io.ByteArrayInputStream;

import junit.framework.TestCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.jersey.SetTest.MyWatcher;
import org.apache.zookeeper.server.jersey.cfg.RestCfg;
import org.junit.After;
import org.junit.Before;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;

/**
 * Test stand-alone server.
 * 
 */
public class Base extends TestCase {
   protected static final Logger LOG = LoggerFactory.getLogger(Base.class);

   protected static final String CONTEXT_PATH = "/zk";
   protected static final int GRIZZLY_PORT = 10104;
   protected static final String BASEURI = String.format(
           "http://localhost:%d%s", GRIZZLY_PORT, CONTEXT_PATH);
   protected static final String ZKHOSTPORT = "localhost:22182";
   protected Client client;
   protected WebResource znodesr, sessionsr;

   protected ZooKeeper zk;

   private RestMain rest;

   @Before
   public void setUp() throws Exception {
       super.setUp();

       RestCfg cfg = new RestCfg(new ByteArrayInputStream(String.format(
               "rest.port=%s\n" + 
               "rest.endpoint.1=%s;%s\n",
               GRIZZLY_PORT, CONTEXT_PATH, ZKHOSTPORT).getBytes()));

       rest = new RestMain(cfg);
       rest.start();

       zk = new ZooKeeper(ZKHOSTPORT, 30000, new MyWatcher());

       client = Client.create();
       znodesr = client.resource(BASEURI).path("znodes/v1");
       sessionsr = client.resource(BASEURI).path("sessions/v1/");
   }

   @After
   public void tearDown() throws Exception {
       super.tearDown();

       client.destroy();
       zk.close();
       rest.stop();
   }

   protected static String createBaseZNode() throws Exception {
       ZooKeeper zk = new ZooKeeper(ZKHOSTPORT, 30000, new MyWatcher());

       String baseZnode = zk.create("/test-", null, Ids.OPEN_ACL_UNSAFE,
               CreateMode.PERSISTENT_SEQUENTIAL);
       zk.close();

       return baseZnode;
   }
}
