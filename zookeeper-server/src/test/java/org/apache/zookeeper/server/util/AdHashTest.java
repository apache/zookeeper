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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

public class AdHashTest extends ZKTestCase {

    private static Random rand = new Random();

    private static List<Long> generateRandomHashes(int count) {
        ArrayList<Long> list = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            list.add(rand.nextLong());
        }
        return list;
    }

    private static void addListOfDigests(AdHash hash, List<Long> digests) {
        for (long b : digests) {
            hash.addDigest(b);
        }
    }

    private static void removeListOfDigests(AdHash hash, List<Long> digests) {
        for (long b : digests) {
            hash.removeDigest(b);
        }
    }

    /**
     * Test thhe add and remove digest from AdHash is working as expected.
     */
    @Test
    public void testAdHash() throws Exception {
        List<Long> bucket1 = generateRandomHashes(50);
        List<Long> bucket2 = generateRandomHashes(3);
        List<Long> bucket3 = generateRandomHashes(30);
        List<Long> bucket4 = generateRandomHashes(10);
        List<Long> bucket5 = generateRandomHashes(5);

        // adding out of order should result in the same hash
        AdHash hash12 = new AdHash();
        addListOfDigests(hash12, bucket1);
        addListOfDigests(hash12, bucket2);

        AdHash hash21 = new AdHash();
        addListOfDigests(hash21, bucket2);
        addListOfDigests(hash21, bucket1);
        assertEquals(hash12, hash21);

        AdHash hashall = new AdHash();
        addListOfDigests(hashall, bucket1);
        addListOfDigests(hashall, bucket2);
        addListOfDigests(hashall, bucket3);
        addListOfDigests(hashall, bucket4);
        addListOfDigests(hashall, bucket5);
        assertFalse(hashall.equals(hash21), "digest of different set not different");
        removeListOfDigests(hashall, bucket4);
        removeListOfDigests(hashall, bucket5);
        addListOfDigests(hash21, bucket3);
        assertEquals(hashall, hash21, "hashall with 4 & 5 removed should match hash21 with 3 added");

        removeListOfDigests(hashall, bucket3);
        removeListOfDigests(hashall, bucket2);
        removeListOfDigests(hashall, bucket1);
        assertEquals(hashall.toString(), "0", "empty hashall's digest should be 0");

        AdHash hash45 = new AdHash();
        addListOfDigests(hash45, bucket4);
        addListOfDigests(hash45, bucket5);

        addListOfDigests(hashall, bucket4);
        addListOfDigests(hashall, bucket5);
        assertEquals(hashall, hash45, "empty hashall + 4&5 should equal hash45");
    }

}
