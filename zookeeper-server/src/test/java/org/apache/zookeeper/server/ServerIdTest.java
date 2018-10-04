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
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZKParameterized;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class ServerIdTest extends ClientBase {
    private final TestType testType;

    private static class TestType {
        final boolean ttlsEnabled;
        final int serverId;

        TestType(boolean ttlsEnabled, int serverId) {
            this.ttlsEnabled = ttlsEnabled;
            this.serverId = serverId;
        }
    }

    @Parameterized.Parameters
    public static List<TestType> data() {
        List<TestType> testTypes = new ArrayList<>();
        for ( boolean ttlsEnabled : new boolean[]{true, false} ) {
            for ( int serverId = 0; serverId <= 255; ++serverId ) {
                testTypes.add(new TestType(ttlsEnabled, serverId));
            }
        }
        return testTypes;
    }

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        System.clearProperty("zookeeper.extendedTypesEnabled");
    }

    public ServerIdTest(TestType testType) {
        this.testType = testType;
    }

    @Before
    @Override
    public void setUp() throws Exception {
        System.setProperty("zookeeper.extendedTypesEnabled", Boolean.toString(testType.ttlsEnabled));
        LOG.info("ttlsEnabled: {} - ServerId: {}", testType.ttlsEnabled, testType.serverId);
        try {
            super.setUpWithServerId(testType.serverId);
        } catch (RuntimeException e) {
            if ( testType.ttlsEnabled && (testType.serverId >= EphemeralType.MAX_EXTENDED_SERVER_ID) ) {
                return; // expected
            }
            throw e;
        }
    }

    @Test
    public void doTest() throws Exception {
        if ( testType.ttlsEnabled && (testType.serverId >= EphemeralType.MAX_EXTENDED_SERVER_ID) ) {
            return;
        }

        TestableZooKeeper zk = null;
        try {
            zk = createClient();

            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/foo", -1);

            if ( testType.ttlsEnabled ) {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), 1000);  // should work
            } else {
                try {
                    zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), 1000);
                    Assert.fail("Should have thrown KeeperException.UnimplementedException");
                } catch (KeeperException.UnimplementedException e) {
                    // expected
                }
            }
        } finally {
            if ( zk != null ) {
                zk.close();
            }
        }
    }
}
