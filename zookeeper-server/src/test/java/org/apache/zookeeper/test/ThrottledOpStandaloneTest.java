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

package org.apache.zookeeper.test;

import java.io.IOException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ThrottledOpStandaloneTest extends ClientBase {

    @BeforeAll
    public static void applyMockUps() {
        ThrottledOpHelper.applyMockUps();
    }

    @Test
    public void testThrottledOp() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
            zk = createClient(hostPort);
            ZooKeeperServer zs = serverFactory.getZooKeeperServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledOp(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testThrottledAcl() throws Exception {
        ZooKeeper zk = null;
        try {
            zk = createClient(hostPort);
            ZooKeeperServer zs = serverFactory.getZooKeeperServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledAcl(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }
}
