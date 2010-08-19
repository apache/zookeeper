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
package org.apache.hedwig.server.persistence;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.hedwig.util.FileUtils;
import org.apache.hedwig.zookeeper.ZooKeeperTestBase;

/**
 * This is a base class for any tests that require a BookKeeper client/server
 * setup.
 * 
 */
public class BookKeeperTestBase extends ZooKeeperTestBase {

    // BookKeeper Server variables
    private List<BookieServer> bookiesList;
    private int initialPort = 5000;

    // String constants used for creating the bookie server files.
    private static final String PREFIX = "bookie";
    private static final String SUFFIX = "test";

    // Variable to decide how many bookie servers to set up.
    private final int numBookies;
    // BookKeeper client instance
    protected BookKeeper bk;

    // Constructor
    public BookKeeperTestBase(int numBookies) {
        this.numBookies = numBookies;
    }

    public BookKeeperTestBase() {
        // By default, use 3 bookies.
        this(3);
    }

    // Getter for the ZooKeeper client instance that the parent class sets up.
    protected ZooKeeper getZooKeeperClient() {
        return zk;
    }

    // Give junit a fake test so that its happy
    @Test
    public void testNothing() throws Exception {

    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        // Initialize the zk client with values
        try {
            zk.create("/ledgers", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/ledgers/available", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Create Bookie Servers
        bookiesList = new LinkedList<BookieServer>();

        for (int i = 0; i < numBookies; i++) {
            File tmpDir = FileUtils.createTempDirectory(PREFIX + i, SUFFIX);
            BookieServer bs = new BookieServer(initialPort + i, hostPort, tmpDir, new File[] { tmpDir });
            bs.start();
            bookiesList.add(bs);
        }

        // Create the BookKeeper client
        bk = new BookKeeper(hostPort);
    }

    public String getZkHostPort() {
        return hostPort;
    }

    @Override
    @After
    public void tearDown() throws Exception {
        // Shutdown all of the bookie servers
        try {
            for (BookieServer bs : bookiesList) {
                bs.shutdown();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // Close the BookKeeper client
        bk.halt();
        super.tearDown();
    }

}
