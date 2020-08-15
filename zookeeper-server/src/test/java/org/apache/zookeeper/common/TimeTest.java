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

package org.apache.zookeeper.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

/**
 * Command line program for demonstrating robustness to clock
 * changes.
 * <p>
 * How to run:
 * ant clean compile-test
 * echo build/test/lib/*.jar build/lib/*.jar build/classes build/test/classes | sed -e 's/ /:/g' &gt; cp
 * java -cp $(cat cp) org.apache.zookeeper.common.TimeTest | tee log-without-patch
 * <p>
 * After test program starts, in another window, do commands:
 * date -s '+1hour'
 * date -s '-1hour'
 * <p>
 * As long as there isn't any expired event, the experiment is successful.
 */
public class TimeTest extends ClientBase {

    private static final long mt0 = System.currentTimeMillis();
    private static final long nt0 = Time.currentElapsedTime();

    private static AtomicInteger watchCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.print("Starting\n");
        final TimeTest test = new TimeTest();
        System.out.print("After construct\n");
        test.setUp();
        ZooKeeper zk = test.createClient();
        zk.create("/ephemeral", new byte[]{1, 2, 3}, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        while (Time.currentElapsedTime() - nt0 < 100000) {
            System.out.printf("%d\t%s\n",
                              discrepancy(),
                              zk.exists("/ephemeral", watchCount.get() == 0 ? createWatcher() : null) != null);
            waitByYielding(500);
        }
    }

    private static Watcher createWatcher() {
        watchCount.incrementAndGet();
        return event -> {
            watchCount.decrementAndGet();
            System.out.printf("%d event = %s\n", discrepancy(), event);
        };

    }

    private static void waitByYielding(long delay) {
        long t0 = Time.currentElapsedTime();
        while (Time.currentElapsedTime() < t0 + delay) {
            Thread.yield();
        }
    }

    private static long discrepancy() {
        return (System.currentTimeMillis() - mt0) - (Time.currentElapsedTime() - nt0);
    }

    @Test
    public void testElapsedTimeToDate() throws Exception {
        long walltime = Time.currentWallTime();
        long elapsedTime = Time.currentElapsedTime();
        Thread.sleep(200);

        Calendar cal = Calendar.getInstance();
        cal.setTime(Time.elapsedTimeToDate(elapsedTime));
        int calculatedDate = cal.get(Calendar.HOUR_OF_DAY);
        cal.setTime(new Date(walltime));
        int realDate = cal.get(Calendar.HOUR_OF_DAY);

        assertEquals(calculatedDate, realDate);
    }

}
