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

package org.apache.zookeeper.server.auth;

import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ZooKeeperServer;

import java.util.List;

/**
 * A variation on {@link AuthenticationProvider} that provides a method
 * for the ZooKeeper server to be set
 */
public interface ServerAuthenticationProvider extends AuthenticationProvider {
    /**
     * Called shortly after construction by ZooKeeper to set the database for later use
     *
     * @param zks ZK db
     */
    void setZooKeeperServer(ZooKeeperServer zks);

    /**
     * This method is called to see if the given id matches the given id
     * expression in the ACL. This allows schemes to use application specific
     * wild cards.
     *
     * @param path
     *                the path of the operation being authenticated
     * @param id
     *                the id to check.
     * @param aclExpr
     *                the expression to match ids against.
     * @param perm
     *                the permission value being authenticated
     * @param setAcls
     *                for set ACL operations, the list of ACLs being set. Otherwise null.
     * @return true if the arguments can be matched by the expression.
     */
    boolean matchesOp(String path, String id, String aclExpr, int perm, List<ACL> setAcls);
}
