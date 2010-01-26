package org.apache.bookkeeper.client;

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

/**
 * This interface determins how entries are distributed among bookies.
 * 
 * Every entry gets replicated to some number of replicas. The first replica for
 * an entry is given a replicaIndex of 0, and so on. To distribute write load,
 * not all entries go to all bookies. Given an entry-id and replica index, an
 * {@link DistributionSchedule} determines which bookie that replica should go
 * to.
 */

interface DistributionSchedule {

    /**
     * 
     * @param entryId
     * @param replicaIndex
     * @return index of bookie that should get this replica
     */
    public int getBookieIndex(long entryId, int replicaIndex);

    /**
     * 
     * @param entryId
     * @param bookieIndex
     * @return -1 if the given bookie index is not a replica for the given
     *         entryId
     */
    public int getReplicaIndex(long entryId, int bookieIndex);

    /**
     * Specifies whether its ok to proceed with recovery given that we have
     * heard back from the given bookie index. These calls will be a made in a
     * sequence and an implementation of this interface should accumulate
     * history about which bookie indexes we have heard from. Once this method
     * has returned true, it wont be called again on the same instance
     * 
     * @param bookieIndexHeardFrom
     * @return true if its ok to proceed with recovery
     */
    public boolean canProceedWithRecovery(int bookieIndexHeardFrom);
}
