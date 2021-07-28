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

package org.apache.zookeeper.server.persistence;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ReferenceCountedACLCache;
import org.apache.zookeeper.server.TransactionChangeRecord;

/**
 * snapshot interface for the persistence layer.
 * implement this interface for implementing
 * snapshots.
 */
public interface SnapShot extends AutoCloseable {

    /**
     * deserialize a data tree from the last valid snapshot and
     * return the last zxid that was deserialized
     *
     * @param dt       the datatree to be deserialized into
     * @param sessions the sessions to be deserialized into
     * @return the last zxid that was deserialized from the snapshot
     * @throws IOException
     */
    long deserialize(DataTree dt, Map<Long, Integer> sessions) throws IOException;

    /**
     * persist the datatree and the sessions into a persistence storage
     *
     * @param dt       the datatree to be serialized
     * @param sessions the session timeouts to be serialized
     * @param lastZxid the last processed zxid before serialization
     * @param fsync    sync the snapshot immediately after write
     * @throws IOException
     */
    void serialize(DataTree dt, Map<Long, Integer> sessions, long lastZxid, boolean fsync) throws IOException;

    /**
     * find the most recent snapshot file
     *
     * @return the most recent snapshot file
     * @throws IOException
     */
    File findMostRecentSnapshot() throws IOException;

    /**
     * get information of the last saved/restored snapshot
     *
     * @return info of last snapshot
     */
    SnapshotInfo getLastSnapshotInfo();

    /**
     * serialize the sessions into a snapshot
     *
     * @param sessions the session timeouts to be serialized
     * @throws IOException
     */
    void serializeSessions(Map<Long, Integer> sessions) throws IOException;

    /**
     * deserialize the sessions from the last valid snapshot
     *
     * @param sessions the session timeouts to be deserialized into
     * @throws IOException
     */
    void deserializeSessions(Map<Long, Integer> sessions) throws IOException;

    /**
     * serialize the ACL lists into a snapshot
     *
     * @param aclCache the acl cache to be serialized
     * @throws IOException
     */
    void serializeACL(ReferenceCountedACLCache aclCache) throws IOException;

    /**
     * deserialize the ACL lists from the last valid snapshot
     *
     * @param aclCache the acl cache to be deserialized into
     * @throws IOException
     */
    void deserializeACL(ReferenceCountedACLCache aclCache) throws IOException;

    /**
     * serialize the nodes of data tree into a snapshot
     *
     * @param pathString the path of the node to be serialized
     * @param node       the data tree node to be serialized
     * @throws IOException
     */
    void writeNode(String pathString, DataNode node) throws IOException;

    /**
     * mark the end of the serialized data of the data tree
     *
     * @throws IOException
     */
    void markEnd() throws IOException;

    /**
     * deserialize the data tree node and its path from the last valid snapshot
     *
     * @param node the data tree node to be deserialized into
     * @return the path of the node
     * @throws IOException
     */
    String readNode(DataNode node) throws IOException;

    /**
     * Serialize the zxid digest of a data tree into a snapshot.
     *
     * @param dt The DataTree
     * @return if ZxidDigest was serialized successfully
     * @throws IOException
     */
    boolean serializeZxidDigest(DataTree dt) throws IOException;

    /**
     * Deserialize the zxid digest from the last valid snapshot
     *
     * @param dt The DataTree
     * @return if ZxidDigest was deserialized successfully
     * @throws IOException
     */
    boolean deserializeZxidDigest(DataTree dt) throws IOException;

    /**
     * apply all the changes in the txn to the snapshot
     *
     * @param changeList a list of changes of in memory database made by the txn
     */
    void applyTxn(List<TransactionChangeRecord> changeList, long zxid) throws IOException;


    /**
     * free resources from this snapshot immediately
     *
     * @throws IOException
     */
    void close() throws IOException;

}
