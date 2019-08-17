/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.util.ArrayList;
import java.util.List;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.data.ACL;

/**
 * Provides a builder style interface for doing multiple updates.  This is
 * really just a thin layer on top of Zookeeper.multi().
 *
 * @since 3.4.0
 *
 */
@InterfaceAudience.Public
public class Transaction {

    private ZooKeeper zk;
    private List<Op> ops = new ArrayList<Op>();

    protected Transaction(ZooKeeper zk) {
        this.zk = zk;
    }

    public Transaction create(final String path, byte[] data, List<ACL> acl, CreateMode createMode) {
        ops.add(Op.create(path, data, acl, createMode.toFlag()));
        return this;
    }

    public Transaction delete(final String path, int version) {
        ops.add(Op.delete(path, version));
        return this;
    }

    public Transaction check(String path, int version) {
        ops.add(Op.check(path, version));
        return this;
    }

    public Transaction setData(final String path, byte[] data, int version) {
        ops.add(Op.setData(path, data, version));
        return this;
    }

    public List<OpResult> commit() throws InterruptedException, KeeperException {
        return zk.multi(ops);
    }

    public void commit(MultiCallback cb, Object ctx) {
        zk.multi(ops, cb, ctx);
    }

}
