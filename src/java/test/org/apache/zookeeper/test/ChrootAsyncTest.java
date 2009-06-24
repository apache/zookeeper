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

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Before;

public class ChrootAsyncTest extends AsyncOpsTest {
    private static final Logger LOG = Logger.getLogger(ChrootAsyncTest.class);

    @Before
    @Override
    protected void setUp() throws Exception {
        String hp = hostPort;
        hostPort = hostPort + "/chrootasynctest";

        super.setUp();

        LOG.info("STARTING " + getName());

        ZooKeeper zk = createClient(hp);
        try {
            zk.create("/chrootasynctest", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        } finally {
            zk.close();
        }
    }
}