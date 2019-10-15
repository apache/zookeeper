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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jute.Index;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReferenceCountedACLCache {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceCountedACLCache.class);

    final Map<Long, List<ACL>> longKeyMap = new HashMap<Long, List<ACL>>();

    final Map<List<ACL>, Long> aclKeyMap = new HashMap<List<ACL>, Long>();

    final Map<Long, AtomicLongWithEquals> referenceCounter = new HashMap<Long, AtomicLongWithEquals>();
    private static final long OPEN_UNSAFE_ACL_ID = -1L;

    /**
     * these are the number of acls that we have in the datatree
     */
    long aclIndex = 0;

    /**
     * converts the list of acls to a long.
     * Increments the reference counter for this ACL.
     * @param acls
     * @return a long that map to the acls
     */
    public synchronized Long convertAcls(List<ACL> acls) {
        if (acls == null) {
            return OPEN_UNSAFE_ACL_ID;
        }

        // get the value from the map
        Long ret = aclKeyMap.get(acls);
        if (ret == null) {
            ret = incrementIndex();
            longKeyMap.put(ret, acls);
            aclKeyMap.put(acls, ret);
        }

        addUsage(ret);

        return ret;
    }

    /**
     * converts a long to a list of acls.
     *
     * @param longVal
     * @return a list of ACLs that map to the long
     */
    public synchronized List<ACL> convertLong(Long longVal) {
        if (longVal == null) {
            return null;
        }
        if (longVal == OPEN_UNSAFE_ACL_ID) {
            return ZooDefs.Ids.OPEN_ACL_UNSAFE;
        }
        List<ACL> acls = longKeyMap.get(longVal);
        if (acls == null) {
            LOG.error("ERROR: ACL not available for long {}", longVal);
            throw new RuntimeException("Failed to fetch acls for " + longVal);
        }
        return acls;
    }

    private long incrementIndex() {
        return ++aclIndex;
    }

    public void deserialize(InputArchive ia) throws IOException {
        clear();
        int i = ia.readInt("map");

        LinkedHashMap<Long, List<ACL>> deserializedMap = new LinkedHashMap<>();
        // keep read operations out of synchronization block
        while (i > 0) {
            Long val = ia.readLong("long");
            List<ACL> aclList = new ArrayList<ACL>();
            Index j = ia.startVector("acls");
            if (j == null) {
                throw new RuntimeException("Incorrent format of InputArchive when deserialize DataTree - missing acls");
            }
            while (!j.done()) {
                ACL acl = new ACL();
                acl.deserialize(ia, "acl");
                aclList.add(acl);
                j.incr();
            }

            deserializedMap.put(val, aclList);
            i--;
        }

        synchronized (this) {
            for (Map.Entry<Long, List<ACL>> entry : deserializedMap.entrySet()) {
                Long val = entry.getKey();
                List<ACL> aclList = entry.getValue();
                if (aclIndex < val) {
                    aclIndex = val;
                }

                longKeyMap.put(val, aclList);
                aclKeyMap.put(aclList, val);
                referenceCounter.put(val, new AtomicLongWithEquals(0));
            }
        }
    }

    public void serialize(OutputArchive oa) throws IOException {
        Map<Long, List<ACL>> clonedLongKeyMap;
        synchronized (this) {
            clonedLongKeyMap = new HashMap<>(longKeyMap);
        }
        oa.writeInt(clonedLongKeyMap.size(), "map");
        for (Map.Entry<Long, List<ACL>> val : clonedLongKeyMap.entrySet()) {
            oa.writeLong(val.getKey(), "long");
            List<ACL> aclList = val.getValue();
            oa.startVector(aclList, "acls");
            for (ACL acl : aclList) {
                acl.serialize(oa, "acl");
            }
            oa.endVector(aclList, "acls");
        }
    }

    public int size() {
        return aclKeyMap.size();
    }

    private void clear() {
        aclKeyMap.clear();
        longKeyMap.clear();
        referenceCounter.clear();
    }

    public synchronized void addUsage(Long acl) {
        if (acl == OPEN_UNSAFE_ACL_ID) {
            return;
        }

        if (!longKeyMap.containsKey(acl)) {
            LOG.info("Ignoring acl {} as it does not exist in the cache", acl);
            return;
        }

        AtomicLong count = referenceCounter.get(acl);
        if (count == null) {
            referenceCounter.put(acl, new AtomicLongWithEquals(1));
        } else {
            count.incrementAndGet();
        }
    }

    public synchronized void removeUsage(Long acl) {
        if (acl == OPEN_UNSAFE_ACL_ID) {
            return;
        }

        if (!longKeyMap.containsKey(acl)) {
            LOG.info("Ignoring acl {} as it does not exist in the cache", acl);
            return;
        }

        long newCount = referenceCounter.get(acl).decrementAndGet();
        if (newCount <= 0) {
            referenceCounter.remove(acl);
            aclKeyMap.remove(longKeyMap.get(acl));
            longKeyMap.remove(acl);
        }
    }

    public synchronized void purgeUnused() {
        Iterator<Map.Entry<Long, AtomicLongWithEquals>> refCountIter = referenceCounter.entrySet().iterator();
        while (refCountIter.hasNext()) {
            Map.Entry<Long, AtomicLongWithEquals> entry = refCountIter.next();
            if (entry.getValue().get() <= 0) {
                Long acl = entry.getKey();
                aclKeyMap.remove(longKeyMap.get(acl));
                longKeyMap.remove(acl);
                refCountIter.remove();
            }
        }
    }

    private static class AtomicLongWithEquals extends AtomicLong {

        private static final long serialVersionUID = 3355155896813725462L;

        public AtomicLongWithEquals(long i) {
            super(i);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            return equals((AtomicLongWithEquals) o);
        }

        public boolean equals(AtomicLongWithEquals that) {
            return get() == that.get();
        }

        @Override
        public int hashCode() {
            return 31 * Long.valueOf(get()).hashCode();
        }

    }

}
