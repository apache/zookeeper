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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetEphemeralsTest extends ClientBase {

    private static final String BASE = "/base";
    private static final int PERSISTENT_CNT = 2;
    private static final int EPHEMERAL_CNT = 2;
    private static final String NEWLINE = System.getProperty("line.separator");
    private String[] expected;
    private ZooKeeper zk;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();

        zk = createClient();
        expected = generatePaths(PERSISTENT_CNT, EPHEMERAL_CNT);
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        zk.close();
    }

    @Test
    public void testGetEphemeralsSync() throws KeeperException, InterruptedException {
        List<String> actual = zk.getEphemerals();
        assertEquals(actual.size(), expected.length, "Expected ephemeral count for allPaths");
        for (int i = 0; i < expected.length; i++) {
            String path = expected[i];
            assertTrue(actual.contains(path), String.format("Path=%s exists in get All Ephemerals list ", path));
        }
    }

    @Test
    public void testGetEphemeralsSyncByPath() throws KeeperException, InterruptedException {
        final String prefixPath = BASE + 0;
        List<String> actual = zk.getEphemerals(prefixPath);
        assertEquals(actual.size(), EPHEMERAL_CNT, "Expected ephemeral count for allPaths");
        for (int i = 0; i < EPHEMERAL_CNT; i++) {
            String path = expected[i];
            assertTrue(actual.contains(path), String.format("Path=%s exists in getEphemerals(%s) list ", path, prefixPath));
        }
    }

    @Test
    public void testGetEphemerals() throws IOException, KeeperException, InterruptedException {

        final CountDownLatch doneProcessing = new CountDownLatch(1);
        final List<String> unexpectedBehavior = new ArrayList<String>();
        zk.getEphemerals((rc, ctx, paths) -> {
            if (paths == null) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for"
                                                             + " allPaths to be %d but was null", expected.length));
            } else if (paths.size() != expected.length) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for allPaths to be %d but was %d", expected.length, paths.size()));
            }
            for (int i = 0; i < expected.length; i++) {
                String path = expected[i];
                if (!paths.contains(path)) {
                    unexpectedBehavior.add(String.format("Path=%s exists in getEphemerals list ", path));
                }
            }
            doneProcessing.countDown();
        }, null);
        long waitForCallbackSecs = 2L;
        if (!doneProcessing.await(waitForCallbackSecs, TimeUnit.SECONDS)) {
            fail(String.format("getEphemerals didn't callback within %d seconds", waitForCallbackSecs));
        }
        checkForUnexpectedBehavior(unexpectedBehavior);

    }

    @Test
    public void testGetEphemeralsByPath() throws IOException, KeeperException, InterruptedException {

        final CountDownLatch doneProcessing = new CountDownLatch(1);
        final String checkPath = BASE + "0";
        final List<String> unexpectedBehavior = new ArrayList<String>();
        zk.getEphemerals(checkPath, (rc, ctx, paths) -> {
            if (paths == null) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for %s to be %d but was null", checkPath, expected.length));
            } else if (paths.size() != EPHEMERAL_CNT) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for %s to be %d but was %d", checkPath, EPHEMERAL_CNT, paths.size()));
            }
            for (int i = 0; i < EPHEMERAL_CNT; i++) {
                String path = expected[i];
                if (!paths.contains(path)) {
                    unexpectedBehavior.add(String.format("Expected path=%s didn't exist "
                                                                 + "in getEphemerals list.", path));
                }
            }
            doneProcessing.countDown();
        }, null);
        long waitForCallbackSecs = 2L;
        if (!doneProcessing.await(waitForCallbackSecs, TimeUnit.SECONDS)) {
            fail(String.format("getEphemerals(%s) didn't callback within %d seconds", checkPath, waitForCallbackSecs));
        }
        checkForUnexpectedBehavior(unexpectedBehavior);
    }

    @Test
    public void testGetEphemeralsEmpty() throws IOException, KeeperException, InterruptedException {

        final CountDownLatch doneProcessing = new CountDownLatch(1);
        final String checkPath = "/unknownPath";
        final int expectedSize = 0;
        final List<String> unexpectedBehavior = new ArrayList<String>();
        zk.getEphemerals(checkPath, (rc, ctx, paths) -> {
            if (paths == null) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for %s to be %d but was null", checkPath, expectedSize));
            } else if (paths.size() != expectedSize) {
                unexpectedBehavior.add(String.format("Expected ephemeral count for %s to be %d but was %d", checkPath, expectedSize, paths.size()));
            }
            doneProcessing.countDown();
        }, null);
        long waitForCallbackSecs = 2L;
        if (!doneProcessing.await(waitForCallbackSecs, TimeUnit.SECONDS)) {
            fail(String.format("getEphemerals(%s) didn't callback within %d seconds", checkPath, waitForCallbackSecs));
        }
        checkForUnexpectedBehavior(unexpectedBehavior);
    }

    @Test
    public void testGetEphemeralsErrors() throws KeeperException {
        try {
            zk.getEphemerals(null, null, null);
            fail("Should have thrown a IllegalArgumentException for a null prefixPath");
        } catch (IllegalArgumentException e) {
            //pass
        }

        try {
            zk.getEphemerals("no leading slash", null, null);
            fail("Should have thrown a IllegalArgumentException " + "for a prefix with no leading slash");
        } catch (IllegalArgumentException e) {
            //pass
        }
    }

    private String[] generatePaths(int persistantCnt, int ephemeralCnt) throws KeeperException, InterruptedException {

        final String[] expected = new String[persistantCnt * ephemeralCnt];
        for (int p = 0; p < persistantCnt; p++) {
            String base = BASE + p;
            zk.create(base, base.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            for (int e = 0; e < ephemeralCnt; e++) {
                String ephem = base + "/ephem" + e;
                zk.create(ephem, ephem.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                expected[p * ephemeralCnt + e] = ephem;
            }
        }
        return expected;
    }

    private void checkForUnexpectedBehavior(List<String> unexpectedBehavior) {
        if (unexpectedBehavior.size() > 0) {
            StringBuilder b = new StringBuilder("The test failed for the following reasons:");
            b.append(NEWLINE);
            for (String error : unexpectedBehavior) {
                b.append("ERROR: ").append(error).append(NEWLINE);
            }
            fail(b.toString());
        }
    }

}
