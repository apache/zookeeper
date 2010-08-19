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
package org.apache.hedwig.zookeeper;

import java.util.List;

import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.ACL;

import org.apache.hedwig.util.PathUtils;

public class ZkUtils {

    static Logger logger = Logger.getLogger(ZkUtils.class);

    public static void createFullPathOptimistic(final ZooKeeper zk, final String originalPath, final byte[] data,
            final List<ACL> acl, final CreateMode createMode, final AsyncCallback.StringCallback callback,
            final Object ctx) {

        zk.create(originalPath, data, acl, createMode, new SafeAsyncZKCallback.StringCallback() {
            @Override
            public void safeProcessResult(int rc, String path, Object ctx, String name) {

                if (rc != Code.NONODE.intValue()) {
                    callback.processResult(rc, path, ctx, name);
                    return;
                }

                // Since I got a nonode, it means that my parents don't exist
                // create mode is persistent since ephemeral nodes can't be
                // parents
                ZkUtils.createFullPathOptimistic(zk, PathUtils.parent(originalPath), new byte[0], acl,
                        CreateMode.PERSISTENT, new SafeAsyncZKCallback.StringCallback() {

                            @Override
                            public void safeProcessResult(int rc, String path, Object ctx, String name) {
                                if (rc == Code.OK.intValue() || rc == Code.NODEEXISTS.intValue()) {
                                    // succeeded in creating the parent, now
                                    // create the original path
                                    ZkUtils.createFullPathOptimistic(zk, originalPath, data, acl, createMode, callback,
                                            ctx);
                                } else {
                                    callback.processResult(rc, path, ctx, name);
                                }
                            }
                        }, ctx);
            }
        }, ctx);

    }

    public static KeeperException logErrorAndCreateZKException(String msg, String path, int rc) {
        KeeperException ke = KeeperException.create(Code.get(rc), path);
        logger.error(msg + ",zkPath: " + path, ke);
        return ke;
    }

}
