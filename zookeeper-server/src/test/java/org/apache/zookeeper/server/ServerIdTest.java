/*
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

import static org.junit.jupiter.api.Assertions.fail;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ServerIdTest extends ClientBase {

    public static Stream<Arguments> data() throws Exception {
        List<Arguments> testTypes = new ArrayList<>();
        for (boolean ttlsEnabled : new boolean[]{true, false}) {
            for (int serverId = 0; serverId <= 255; ++serverId) {
                testTypes.add(Arguments.of(ttlsEnabled, serverId));
            }
        }
        return testTypes.stream();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        System.clearProperty("zookeeper.extendedTypesEnabled");
    }

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        //since parameterized test methods need a parameterized setUp method
        //the inherited method has to be overridden with an empty function body
    }

    public void setUp(boolean ttlsEnabled, int serverId) throws Exception {
        System.setProperty("zookeeper.extendedTypesEnabled", Boolean.toString(ttlsEnabled));
        LOG.info("ttlsEnabled: {} - ServerId: {}", ttlsEnabled, serverId);
        try {
            super.setUpWithServerId(serverId);
        } catch (RuntimeException e) {
            if (ttlsEnabled && (serverId >= EphemeralType.MAX_EXTENDED_SERVER_ID)) {
                return; // expected
            }
            throw e;
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void doTest(boolean ttlsEnabled, int serverId) throws Exception {
        setUp(ttlsEnabled, serverId);
        if (ttlsEnabled && (serverId >= EphemeralType.MAX_EXTENDED_SERVER_ID)) {
            return;
        }

        TestableZooKeeper zk = null;
        try {
            zk = createClient();

            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/foo", -1);

            if (ttlsEnabled) {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), 1000);  // should work
            } else {
                try {
                    zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), 1000);
                    fail("Should have thrown KeeperException.UnimplementedException");
                } catch (KeeperException.UnimplementedException e) {
                    // expected
                }
            }
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

}
