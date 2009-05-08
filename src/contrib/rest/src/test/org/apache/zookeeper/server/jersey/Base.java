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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.jersey.SetTest.MyWatcher;
import org.junit.After;
import org.junit.Before;

import com.sun.grizzly.http.SelectorThread;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;


/**
 * Test stand-alone server.
 *
 */
public class Base extends TestCase {
    protected static final Logger LOG = Logger.getLogger(Base.class);

    protected static final String BASEURI = "http://localhost:10104/";
    protected static final String ZKHOSTPORT = "localhost:22182";
    protected Client c;
    protected WebResource r;

    protected ZooKeeper zk;

    private SelectorThread threadSelector;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        ZooKeeperService.mapUriBase(BASEURI, ZKHOSTPORT);

        RestMain main = new RestMain(BASEURI);
        threadSelector = main.execute();

        zk = new ZooKeeper(ZKHOSTPORT, 30000, new MyWatcher());

        c = Client.create();
        r = c.resource(BASEURI);
        r = r.path("znodes/v1");
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        c.destroy();

        zk.close();
        ZooKeeperService.close(BASEURI);

        threadSelector.stopEndpoint();
    }

    protected static String createBaseZNode() throws Exception {
        ZooKeeper zk = new ZooKeeper(ZKHOSTPORT, 30000, new MyWatcher());

        String baseZnode = zk.create("/test-", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL);
        zk.close();

        return baseZnode;
    }
}
