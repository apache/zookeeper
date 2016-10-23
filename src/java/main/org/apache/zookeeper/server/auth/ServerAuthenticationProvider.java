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

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;

import java.util.List;

/**
 * A variation on {@link AuthenticationProvider} that provides additional
 * parameters for more detailed authentication
 */
public abstract class ServerAuthenticationProvider implements AuthenticationProvider {
    /**
     * This method is called when a client passes authentication data for this
     * scheme. The authData is directly from the authentication packet. The
     * implementor may attach new ids to the authInfo field of cnxn or may use
     * cnxn to send packets back to the client.
     *
     * @param cnxn
     *                the cnxn that received the authentication information.
     * @param authData
     *                the authentication data received.
     * @return indication of success or failure
     */
    public abstract KeeperException.Code handleAuthentication(ZooKeeperServer zks, ServerCnxn cnxn, byte authData[]);

    /**
     * This method is called to see if the given id matches the given id
     * expression in the ACL. This allows schemes to use application specific
     * wild cards.
     *
     * @param zks
     *                the ZooKeeper server instance
     * @param cnxn
     *                the active server connection being authenticated
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
    public abstract boolean matches(ZooKeeperServer zks, ServerCnxn cnxn, String path, String id, String aclExpr, int perm, List<ACL> setAcls);

    @Override
    public final KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final boolean matches(String id, String aclExpr) {
        throw new UnsupportedOperationException();
    }
}
