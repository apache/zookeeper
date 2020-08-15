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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.junit.jupiter.api.Test;

public class ReferenceCountedACLCacheTest {

    @Test
    public void testSameACLGivesSameID() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);

        List<ACL> testACL2 = createACL("myid");

        assertEquals(aclId, cache.convertAcls(testACL2));
    }

    @Test
    public void testWhetherOrderingMatters() {
        List<ACL> testACL = new ArrayList<ACL>();
        testACL.add(new ACL(ZooDefs.Perms.READ, new Id("scheme", "ro")));
        testACL.add(new ACL(ZooDefs.Perms.WRITE, new Id("scheme", "rw")));

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);

        List<ACL> testACL2 = new ArrayList<ACL>();
        testACL2.add(new ACL(ZooDefs.Perms.WRITE, new Id("scheme", "rw")));
        testACL2.add(new ACL(ZooDefs.Perms.READ, new Id("scheme", "ro")));

        assertFalse(aclId.equals(cache.convertAcls(testACL2)));
    }

    @Test
    public void testBidirectionality() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);

        assertEquals(testACL, cache.convertLong(aclId));
    }

    @Test
    public void testCacheSize() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);
        assertEquals(1, cache.size());

        List<ACL> testACL2 = createACL("myid");

        assertEquals(aclId, cache.convertAcls(testACL2));
        assertEquals(1, cache.size());

        List<ACL> testACL3 = createACL("differentId");

        Long aclId3 = cache.convertAcls(testACL3);
        assertFalse(aclId3.equals(aclId));
        assertEquals(2, cache.size());
    }

    @Test
    public void testAddThenRemove() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);
        assertEquals(1, cache.size());

        cache.removeUsage(aclId);
        assertEquals(0, cache.size());
    }

    @Test
    public void testMultipleAddsAndRemove() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);
        assertEquals(1, cache.size());

        cache.convertAcls(testACL);
        assertEquals(1, cache.size());

        List<ACL> testACL2 = createACL("anotherId");
        cache.convertAcls(testACL2);

        cache.removeUsage(aclId);
        assertEquals(2, cache.size());
        cache.removeUsage(aclId);
        assertEquals(1, cache.size());

        Long newId = cache.convertAcls(testACL);
        assertFalse(aclId.equals(newId));
    }

    @Test
    public void testAddUsage() {
        List<ACL> testACL = createACL("myid");

        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        Long aclId = cache.convertAcls(testACL);
        assertEquals(1, cache.size());

        cache.addUsage(aclId);
        assertEquals(1, cache.size());

        cache.removeUsage(aclId);
        assertEquals(1, cache.size());
        cache.removeUsage(aclId);
        assertEquals(0, cache.size());
    }

    @Test
    public void testAddNonExistentUsage() {
        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();
        cache.addUsage(1L);

        assertEquals(0, cache.size());
        /*
        On startup, it's possible that we'll try calling addUsage of an ID not in the cache.  This is safe to ignore
        as it'll be added later when we traverse the tranlog.  See discussion here:
        http://mail-archives.apache.org/mod_mbox/zookeeper-user/201507.mbox/%3CCAB5oV2_ujhvBA1sEkCG2WRakPjCy%2BNR10620WK2G1GGgmEO44g%40mail.gmail.com%3E

        This test makes sure that we don't add the ID to the cache in this case as that would result in dupes later
        and consequently incorrect counts and entries that will never be cleaned out.
         */
    }

    @Test
    public void testSerializeDeserialize() throws IOException {
        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();

        List<ACL> acl1 = createACL("one");
        List<ACL> acl2 = createACL("two");
        List<ACL> acl3 = createACL("three");
        List<ACL> acl4 = createACL("four");
        List<ACL> acl5 = createACL("five");

        Long aclId1 = convertACLsNTimes(cache, acl1, 1);
        Long aclId2 = convertACLsNTimes(cache, acl2, 2);
        Long aclId3 = convertACLsNTimes(cache, acl3, 3);
        Long aclId4 = convertACLsNTimes(cache, acl4, 4);
        Long aclId5 = convertACLsNTimes(cache, acl5, 5);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive archive = BinaryOutputArchive.getArchive(baos);
        cache.serialize(archive);

        BinaryInputArchive inArchive = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));
        ReferenceCountedACLCache deserializedCache = new ReferenceCountedACLCache();
        deserializedCache.deserialize(inArchive);
        callAddUsageNTimes(deserializedCache, aclId1, 1);
        callAddUsageNTimes(deserializedCache, aclId2, 2);
        callAddUsageNTimes(deserializedCache, aclId3, 3);
        callAddUsageNTimes(deserializedCache, aclId4, 4);
        callAddUsageNTimes(deserializedCache, aclId5, 5);
        assertCachesEqual(cache, deserializedCache);
    }

    @Test
    public void testNPEInDeserialize() throws IOException {
        ReferenceCountedACLCache serializeCache = new ReferenceCountedACLCache() {
            @Override
            public synchronized void serialize(OutputArchive oa) throws IOException {
                oa.writeInt(1, "map");
                oa.writeLong(1, "long");
                oa.startVector(null, "acls");
                oa.endVector(null, "acls");
            }
        };
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive archive = BinaryOutputArchive.getArchive(baos);
        serializeCache.serialize(archive);
        BinaryInputArchive inArchive = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));
        ReferenceCountedACLCache deserializedCache = new ReferenceCountedACLCache();
        try {
            deserializedCache.deserialize(inArchive);
        } catch (NullPointerException e) {
            fail("should not throw NPE while do deserialized");
        } catch (RuntimeException e) {
            // do nothing.
        }
    }

    private void assertCachesEqual(ReferenceCountedACLCache expected, ReferenceCountedACLCache actual) {
        assertEquals(expected.aclIndex, actual.aclIndex);
        assertEquals(expected.aclKeyMap, actual.aclKeyMap);
        assertEquals(expected.longKeyMap, actual.longKeyMap);
        assertEquals(expected.referenceCounter, actual.referenceCounter);
    }

    @Test
    public void testPurgeUnused() throws IOException {
        ReferenceCountedACLCache cache = new ReferenceCountedACLCache();

        List<ACL> acl1 = createACL("one");
        List<ACL> acl2 = createACL("two");
        List<ACL> acl3 = createACL("three");
        List<ACL> acl4 = createACL("four");
        List<ACL> acl5 = createACL("five");

        Long aclId1 = convertACLsNTimes(cache, acl1, 1);
        Long aclId2 = convertACLsNTimes(cache, acl2, 2);
        Long aclId3 = convertACLsNTimes(cache, acl3, 3);
        Long aclId4 = convertACLsNTimes(cache, acl4, 4);
        Long aclId5 = convertACLsNTimes(cache, acl5, 5);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive archive = BinaryOutputArchive.getArchive(baos);
        cache.serialize(archive);

        BinaryInputArchive inArchive = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));
        ReferenceCountedACLCache deserializedCache = new ReferenceCountedACLCache();
        deserializedCache.deserialize(inArchive);
        callAddUsageNTimes(deserializedCache, aclId1, 1);
        callAddUsageNTimes(deserializedCache, aclId2, 2);
        deserializedCache.purgeUnused();

        assertEquals(2, deserializedCache.size());
        assertEquals(aclId1, deserializedCache.convertAcls(acl1));
        assertEquals(aclId2, deserializedCache.convertAcls(acl2));
        assertFalse(acl3.equals(deserializedCache.convertAcls(acl3)));
        assertFalse(acl4.equals(deserializedCache.convertAcls(acl4)));
        assertFalse(acl5.equals(deserializedCache.convertAcls(acl5)));
    }

    private void callAddUsageNTimes(ReferenceCountedACLCache deserializedCache, Long aclId, int num) {
        for (int i = 0; i < num; i++) {
            deserializedCache.addUsage(aclId);
        }
    }

    private Long convertACLsNTimes(ReferenceCountedACLCache cache, List<ACL> acl, int num) {
        if (num <= 0) {
            return -1L;
        }

        for (int i = 0; i < num - 1; i++) {
            cache.convertAcls(acl);
        }

        return cache.convertAcls(acl);
    }

    private List<ACL> createACL(String id) {
        List<ACL> acl1 = new ArrayList<ACL>();
        acl1.add(new ACL(ZooDefs.Perms.ADMIN, new Id("scheme", id)));
        return acl1;
    }

}
