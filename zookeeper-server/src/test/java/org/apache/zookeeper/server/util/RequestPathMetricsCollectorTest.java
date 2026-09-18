/*
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

package org.apache.zookeeper.server.util;

import static org.apache.zookeeper.ZooDefs.OpCode.addWatch;
import static org.apache.zookeeper.ZooDefs.OpCode.auth;
import static org.apache.zookeeper.ZooDefs.OpCode.check;
import static org.apache.zookeeper.ZooDefs.OpCode.checkWatches;
import static org.apache.zookeeper.ZooDefs.OpCode.closeSession;
import static org.apache.zookeeper.ZooDefs.OpCode.create;
import static org.apache.zookeeper.ZooDefs.OpCode.create2;
import static org.apache.zookeeper.ZooDefs.OpCode.createContainer;
import static org.apache.zookeeper.ZooDefs.OpCode.createSession;
import static org.apache.zookeeper.ZooDefs.OpCode.createTTL;
import static org.apache.zookeeper.ZooDefs.OpCode.delete;
import static org.apache.zookeeper.ZooDefs.OpCode.deleteContainer;
import static org.apache.zookeeper.ZooDefs.OpCode.error;
import static org.apache.zookeeper.ZooDefs.OpCode.exists;
import static org.apache.zookeeper.ZooDefs.OpCode.getACL;
import static org.apache.zookeeper.ZooDefs.OpCode.getAllChildrenNumber;
import static org.apache.zookeeper.ZooDefs.OpCode.getChildren;
import static org.apache.zookeeper.ZooDefs.OpCode.getChildren2;
import static org.apache.zookeeper.ZooDefs.OpCode.getData;
import static org.apache.zookeeper.ZooDefs.OpCode.getEphemerals;
import static org.apache.zookeeper.ZooDefs.OpCode.multi;
import static org.apache.zookeeper.ZooDefs.OpCode.multiRead;
import static org.apache.zookeeper.ZooDefs.OpCode.notification;
import static org.apache.zookeeper.ZooDefs.OpCode.ping;
import static org.apache.zookeeper.ZooDefs.OpCode.reconfig;
import static org.apache.zookeeper.ZooDefs.OpCode.removeWatches;
import static org.apache.zookeeper.ZooDefs.OpCode.sasl;
import static org.apache.zookeeper.ZooDefs.OpCode.setACL;
import static org.apache.zookeeper.ZooDefs.OpCode.setData;
import static org.apache.zookeeper.ZooDefs.OpCode.setWatches;
import static org.apache.zookeeper.ZooDefs.OpCode.setWatches2;
import static org.apache.zookeeper.ZooDefs.OpCode.sync;
import static org.apache.zookeeper.ZooDefs.OpCode.whoAmI;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class RequestPathMetricsCollectorTest {

    @BeforeEach
    public void setUp() {
        System.setProperty("zookeeper.pathStats.enabled", "true");
        System.setProperty("zookeeper.pathStats.slotCapacity", "60");
        System.setProperty("zookeeper.pathStats.slotDuration", "1");
        System.setProperty("zookeeper.pathStats.maxDepth", "6");
        System.setProperty("zookeeper.pathStats.sampleRate", "1.0");
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("zookeeper.pathStats.enabled");
        System.clearProperty("zookeeper.pathStats.slotCapacity");
        System.clearProperty("zookeeper.pathStats.slotDuration");
        System.clearProperty("zookeeper.pathStats.maxDepth");
        System.clearProperty("zookeeper.pathStats.sampleRate");
    }

    @Test
    public void testTrimPath() {
        //normal cases
        String trimmedPath = RequestPathMetricsCollector.trimPathDepth("/p1/p2/p3", 1);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1"));
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("/p1/p2/p3", 2);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1/p2"));
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("/p1/p2/p3", 3);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1/p2/p3"));
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("/p1/p2/p3", 4);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1/p2/p3"));
        //some extra symbols
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("//p1 next/p2.index/p3:next", 3);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1 next/p2.index/p3:next"));
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("//p1 next/p2.index/p3:next", 2);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1 next/p2.index"));
        trimmedPath = RequestPathMetricsCollector.trimPathDepth("//p1 next/p2.index/p3:next", 6);
        assertTrue(trimmedPath.equalsIgnoreCase("/p1 next/p2.index/p3:next"));
    }

    @Test
    public void testQueueMapReduce() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector();
        RequestPathMetricsCollector.PathStatsQueue pathStatsQueue = requestPathMetricsCollector.new PathStatsQueue(create2);
        Thread path7 = new Thread(() -> {
            for (int i = 0; i < 1000000; i++) {
                pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6/path7" + "_" + i);
            }
        });
        path7.start();
        Thread path6 = new Thread(() -> {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6");
            for (int i = 1; i < 100000; i++) {
                pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6" + "_" + i);
            }
        });
        path6.start();
        for (int i = 0; i < 1; i++) {
            pathStatsQueue.registerRequest("/path1");
        }
        for (int i = 0; i < 10; i++) {
            pathStatsQueue.registerRequest("/path1/path2" + "_" + i);
        }
        for (int i = 0; i < 100; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3" + "_" + i);
        }
        for (int i = 0; i < 1000; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4" + "_" + i);
        }
        for (int i = 0; i < 10000; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5" + "_" + i);
        }
        path6.join();
        path7.join();
        Map<String, Integer> newSlot = pathStatsQueue.mapReducePaths(1, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 1);
        assertTrue(newSlot.get("/path1").compareTo(1111111) == 0);
        //cut up to 2
        newSlot = pathStatsQueue.mapReducePaths(2, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 12);
        assertTrue(newSlot.get("/path1").compareTo(1) == 0);
        assertTrue(newSlot.get("/path1/path2").compareTo(1111100) == 0);
        //cut up to 3
        newSlot = pathStatsQueue.mapReducePaths(3, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 112);
        assertTrue(newSlot.get("/path1").compareTo(1) == 0);
        assertTrue(newSlot.get("/path1/path2/path3").compareTo(1111000) == 0);
        //cut up to 4
        newSlot = pathStatsQueue.mapReducePaths(4, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 1112);
        assertTrue(newSlot.get("/path1/path2/path3/path4").compareTo(1110000) == 0);
        //cut up to 5
        newSlot = pathStatsQueue.mapReducePaths(5, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 11112);
        assertTrue(newSlot.get("/path1/path2/path3/path4/path5").compareTo(1100000) == 0);
        //cut up to 6
        newSlot = pathStatsQueue.mapReducePaths(6, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 111111);
        assertTrue(newSlot.get("/path1/path2/path3/path4/path5/path6").compareTo(1000001) == 0);
        //cut up to 7
        newSlot = pathStatsQueue.mapReducePaths(7, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.size() == 1111111);
    }

    @Test
    public void testCollectEmptyStats() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector();
        RequestPathMetricsCollector.PathStatsQueue pathStatsQueue = requestPathMetricsCollector.new PathStatsQueue(getChildren);
        Thread.sleep(5000);
        Map<String, Integer> newSlot = pathStatsQueue.mapReducePaths(3, pathStatsQueue.getCurrentSlot());
        assertTrue(newSlot.isEmpty());
        pathStatsQueue.start();
        Thread.sleep(15000);
        newSlot = pathStatsQueue.collectStats(1);
        assertTrue(newSlot.size() == 0);
        newSlot = pathStatsQueue.collectStats(2);
        assertTrue(newSlot.size() == 0);
        newSlot = pathStatsQueue.collectStats(5);
        assertTrue(newSlot.size() == 0);
    }

    @Test
    @Disabled
    public void testCollectStats() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector(true);
        RequestPathMetricsCollector.PathStatsQueue pathStatsQueue = requestPathMetricsCollector.new PathStatsQueue(getChildren);
        pathStatsQueue.start();
        Thread path7 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 100000; j++) {
                    pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6/path7" + "_" + i + "_" + j);
                }
            }
        });
        path7.start();
        Thread path6 = new Thread(() -> {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6");
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 10000; j++) {
                    pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5/path6" + "_" + i + "_" + j);
                }
            }
        });
        path6.start();
        for (int i = 0; i < 1; i++) {
            pathStatsQueue.registerRequest("/path1");
        }
        for (int i = 0; i < 10; i++) {
            pathStatsQueue.registerRequest("/path1/path2" + "_" + i);
        }
        for (int i = 0; i < 100; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3" + "_" + i);
        }
        for (int i = 0; i < 1000; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4" + "_" + i);
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < 10000; i++) {
            pathStatsQueue.registerRequest("/path1/path2/path3/path4/path5" + "_" + i);
        }
        path6.join();
        path7.join();
        Map<String, Integer> newSlot = pathStatsQueue.collectStats(1);
        assertEquals(newSlot.size(), 1);
        assertEquals(newSlot.get("/path1").intValue(), 1111112);
        //cut up to 2
        newSlot = pathStatsQueue.collectStats(2);
        assertEquals(newSlot.size(), 12);
        assertEquals(newSlot.get("/path1").intValue(), 1);
        assertEquals(newSlot.get("/path1/path2").intValue(), 1111101);
        //cut up to 3
        newSlot = pathStatsQueue.collectStats(3);
        assertEquals(newSlot.size(), 112);
        assertEquals(newSlot.get("/path1").intValue(), 1);
        assertEquals(newSlot.get("/path1/path2/path3").intValue(), 1111001);
        //cut up to 4
        newSlot = pathStatsQueue.collectStats(4);
        assertEquals(newSlot.size(), 1112);
        assertEquals(newSlot.get("/path1/path2/path3/path4").intValue(), 1110001);
        //cut up to 5
        newSlot = pathStatsQueue.collectStats(5);
        assertEquals(newSlot.size(), 11112);
        assertEquals(newSlot.get("/path1/path2/path3/path4/path5").intValue(), 1100001);
        //cut up to 6
        newSlot = pathStatsQueue.collectStats(6);
        assertEquals(newSlot.size(), 111112);
        assertEquals(newSlot.get("/path1/path2/path3/path4/path5/path6").intValue(), 1000001);
    }

    @Test
    public void testAggregate() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector(true);
        Thread path7 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 100000; j++) {
                    requestPathMetricsCollector.registerRequest(getData, "/path1/path2/path3/path4/path5/path6/path7"
                                                                                 + "_"
                                                                                 + i
                                                                                 + "_"
                                                                                 + j);
                }
            }
        });
        path7.start();
        Thread path6 = new Thread(() -> {
            requestPathMetricsCollector.registerRequest(getChildren2, "/path1/path2/path3/path4/path5/path6");
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 10000; j++) {
                    requestPathMetricsCollector.registerRequest(getChildren, "/path1/path2/path3/path4/path5/path6"
                                                                                     + "_"
                                                                                     + i
                                                                                     + "_"
                                                                                     + j);
                }
            }
        });
        path6.start();
        for (int i = 0; i < 1; i++) {
            requestPathMetricsCollector.registerRequest(create2, "/path1");
        }
        for (int i = 0; i < 10; i++) {
            requestPathMetricsCollector.registerRequest(create, "/path1/path2" + "_" + i);
        }
        for (int i = 0; i < 100; i++) {
            requestPathMetricsCollector.registerRequest(delete, "/path1/path2/path3" + "_" + i);
        }
        for (int i = 0; i < 1000; i++) {
            requestPathMetricsCollector.registerRequest(setData, "/path1/path2/path3/path4" + "_" + i);
        }
        for (int i = 0; i < 10000; i++) {
            requestPathMetricsCollector.registerRequest(exists, "/path1/path2/path3/path4/path5" + "_" + i);
        }
        path6.join();
        path7.join();
        Map<String, Integer> newSlot = requestPathMetricsCollector.aggregatePaths(2, queue -> true);
        assertEquals(newSlot.size(), 12);
        assertEquals(newSlot.get("/path1").intValue(), 1);
        assertEquals(newSlot.get("/path1/path2").intValue(), 1111101);
        //cut up to 3
        newSlot = requestPathMetricsCollector.aggregatePaths(3, queue -> true);
        assertEquals(newSlot.size(), 112);
        assertEquals(newSlot.get("/path1").intValue(), 1);
        assertEquals(newSlot.get("/path1/path2/path3").intValue(), 1111001);
        //cut up to 4
        newSlot = requestPathMetricsCollector.aggregatePaths(4, queue -> true);
        assertEquals(newSlot.size(), 1112);
        assertEquals(newSlot.get("/path1/path2/path3/path4").intValue(), 1110001);
        //cut up to 5
        newSlot = requestPathMetricsCollector.aggregatePaths(5, queue -> true);
        assertEquals(newSlot.size(), 11112);
        assertEquals(newSlot.get("/path1/path2/path3/path4/path5").intValue(), 1100001);
        //cut up to 6
        newSlot = requestPathMetricsCollector.aggregatePaths(6, queue -> true);
        assertEquals(newSlot.size(), 111112);
        assertEquals(newSlot.get("/path1/path2/path3/path4/path5/path6").intValue(), 1000001);
        //cut up to 7 but the initial mapReduce kept only 6
        newSlot = requestPathMetricsCollector.aggregatePaths(7, queue -> true);
        assertEquals(newSlot.size(), 111112);
        assertEquals(newSlot.get("/path1/path2/path3/path4/path5/path6").intValue(), 1000001);
        //test predicate
        //cut up to 4 for all the reads
        newSlot = requestPathMetricsCollector.aggregatePaths(4, queue -> !queue.isWriteOperation());
        assertEquals(newSlot.size(), 1);
        assertEquals(newSlot.get("/path1/path2/path3/path4").intValue(), 1110001);
        //cut up to 4 for all the write
        newSlot = requestPathMetricsCollector.aggregatePaths(4, queue -> queue.isWriteOperation());
        assertEquals(newSlot.size(), 1111);
        //cut up to 3 for all the write
        newSlot = requestPathMetricsCollector.aggregatePaths(3, queue -> queue.isWriteOperation());
        assertEquals(newSlot.size(), 112);
        assertEquals(newSlot.get("/path1/path2/path3").intValue(), 1000);
    }

    @Test
    public void testTopPath() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector(true);
        Thread path7 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 100000; j++) {
                    requestPathMetricsCollector.registerRequest(getData, "/path1/path2/path3/path4/path5/path6/path7"
                                                                                 + "_"
                                                                                 + i
                                                                                 + "_"
                                                                                 + j);
                }
            }
        });
        path7.start();
        Thread path6 = new Thread(() -> {
            requestPathMetricsCollector.registerRequest(getChildren2, "/path1/path2/path3/path4/path5/path6");
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int j = 0; j < 10000; j++) {
                    requestPathMetricsCollector.registerRequest(getChildren, "/path1/path2/path3/path4/path5/path6"
                                                                                     + "_"
                                                                                     + i
                                                                                     + "_"
                                                                                     + j);
                }
            }
        });
        path6.start();
        for (int i = 0; i < 1; i++) {
            requestPathMetricsCollector.registerRequest(create2, "/path1");
        }
        for (int i = 0; i < 10; i++) {
            requestPathMetricsCollector.registerRequest(create, "/path1/path2" + "_" + i);
        }
        for (int i = 0; i < 100; i++) {
            requestPathMetricsCollector.registerRequest(delete, "/path1/path2/path3" + "_" + i);
        }
        for (int i = 0; i < 1000; i++) {
            requestPathMetricsCollector.registerRequest(setData, "/path1/path2/path3/path4" + "_" + i);
        }
        for (int i = 0; i < 10000; i++) {
            requestPathMetricsCollector.registerRequest(exists, "/path1/path2/path3/path4/path5" + "_" + i);
        }
        path6.join();
        path7.join();
        StringBuilder sb1 = new StringBuilder();
        Map<String, Integer> newSlot = requestPathMetricsCollector.aggregatePaths(3, queue -> queue.isWriteOperation());
        requestPathMetricsCollector.logTopPaths(newSlot, entry -> sb1.append(entry.getKey()
                                                                                     + " : "
                                                                                     + entry.getValue()
                                                                                     + "\n"));
        assertTrue(sb1.toString().startsWith("/path1/path2/path3 : 1000"));
        StringBuilder sb2 = new StringBuilder();
        newSlot = requestPathMetricsCollector.aggregatePaths(3, queue -> !queue.isWriteOperation());
        requestPathMetricsCollector.logTopPaths(newSlot, entry -> sb2.append(entry.getKey()
                                                                                     + " : "
                                                                                     + entry.getValue()
                                                                                     + "\n"));
        assertTrue(sb2.toString().startsWith("/path1/path2/path3 : 1110001"));
        StringBuilder sb3 = new StringBuilder();
        newSlot = requestPathMetricsCollector.aggregatePaths(4, queue -> true);
        requestPathMetricsCollector.logTopPaths(newSlot, entry -> sb3.append(entry.getKey()
                                                                                     + " : "
                                                                                     + entry.getValue()
                                                                                     + "\n"));
        assertTrue(sb3.toString().startsWith("/path1/path2/path3/path4 : 1110001"));
    }

    @Test
    public void testMultiThreadPerf() throws InterruptedException {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector();
        Random rand = new Random(System.currentTimeMillis());
        Long startTime = System.currentTimeMillis();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        //call 100k get Data
        for (int i = 0; i < 100000; i++) {
            executor.submit(
                () -> requestPathMetricsCollector.registerRequest(getData, "/path1/path2/path" + rand.nextInt(10)));
        }
        //5K create
        for (int i = 0; i < 5000; i++) {
            executor.submit(
                () -> requestPathMetricsCollector.registerRequest(create2, "/path1/path2/path" + rand.nextInt(10)));
        }
        //5K delete
        for (int i = 0; i < 5000; i++) {
            executor.submit(
                () -> requestPathMetricsCollector.registerRequest(delete, "/path1/path2/path" + rand.nextInt(10)));
        }
        //40K getChildren
        for (int i = 0; i < 40000; i++) {
            executor.submit(
                () -> requestPathMetricsCollector.registerRequest(getChildren, "/path1/path2/path" + rand.nextInt(10)));
        }
        executor.shutdown();
        //wait for at most 10 mill seconds
        executor.awaitTermination(10, TimeUnit.MILLISECONDS);
        assertTrue(executor.isTerminated());
        Long endTime = System.currentTimeMillis();
        //less than 2 seconds total time
        assertTrue(TimeUnit.MILLISECONDS.toSeconds(endTime - startTime) < 3);
    }

    static class OpCodeCharacters {
        private final int opcode;
        private final boolean path;

        OpCodeCharacters(int opcode, boolean path) {
            this.opcode = opcode;
            this.path = path;
        }

        public int getOpcode() {
            return opcode;
        }

        public boolean isPath() {
            return path;
        }
    }

    static OpCodeCharacters[] OPCODES = new OpCodeCharacters[] {
        new OpCodeCharacters(notification, false),
        new OpCodeCharacters(create, true),
        new OpCodeCharacters(delete, true),
        new OpCodeCharacters(exists, true),
        new OpCodeCharacters(getData, true),
        new OpCodeCharacters(setData, true),
        new OpCodeCharacters(getACL, true),
        new OpCodeCharacters(setACL, true),
        new OpCodeCharacters(getChildren, true),
        new OpCodeCharacters(sync, true),
        new OpCodeCharacters(ping, false),
        new OpCodeCharacters(getChildren2, true),
        new OpCodeCharacters(check, true),
        new OpCodeCharacters(multi, false),
        new OpCodeCharacters(create2, true),
        new OpCodeCharacters(reconfig, true),
        new OpCodeCharacters(checkWatches, true),
        new OpCodeCharacters(removeWatches, true),
        new OpCodeCharacters(createContainer, true),
        new OpCodeCharacters(deleteContainer, true),
        new OpCodeCharacters(createTTL, true),
        new OpCodeCharacters(multiRead, false),
        new OpCodeCharacters(auth, false),
        new OpCodeCharacters(setWatches, false),
        new OpCodeCharacters(sasl, false),
        new OpCodeCharacters(getEphemerals, true),
        new OpCodeCharacters(getAllChildrenNumber, true),
        new OpCodeCharacters(setWatches2, false),
        new OpCodeCharacters(addWatch, true),
        new OpCodeCharacters(whoAmI, false),
        new OpCodeCharacters(createSession, false),
        new OpCodeCharacters(closeSession, false),
        new OpCodeCharacters(error, false),
    };

    @Test
    public void testOpCodeRequestPath() throws Exception {
        RequestPathMetricsCollector requestPathMetricsCollector = new RequestPathMetricsCollector();
        Map<Integer, OpCodeCharacters> opcodes = Arrays.stream(OPCODES).collect(HashMap::new, (map, opcode) -> map.put(opcode.getOpcode(), opcode), HashMap::putAll);

        Class<?> clazz = ZooDefs.OpCode.class;
        Field[] fields = clazz.getFields();

        int n = 0;
        for (Field field : fields) {
            int opCode = field.getInt(null);
            String opString = Request.op2String(opCode);

            OpCodeCharacters opCodeCharacters = opcodes.get(opCode);
            // Fails missing opcode to gain attention.
            assertNotNull(opCodeCharacters, String.format("no characters for opcode %s", opString));

            if (!opCodeCharacters.isPath()) {
                continue;
            }

            n += 1;

            String path = "/path/" + opString;
            IntStream.range(0, n).forEach(ignored -> {
                requestPathMetricsCollector.registerRequest(opCode, path);
            });

            StringWriter output = new StringWriter();
            requestPathMetricsCollector.dumpTopPaths(new PrintWriter(output, true), 10);

            String counts = String.format("%s : %d", path, n);
            assertThat(output.toString(), containsString(counts));
        }
    }
}
