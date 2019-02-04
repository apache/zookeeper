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
    public static class ServerObjs {
        private final ZooKeeperServer zks;
        private final ServerCnxn cnxn;

        /**
         * @param zks
         *                the ZooKeeper server instance
         * @param cnxn
         *                the cnxn that received the authentication information.
         */
        public ServerObjs(ZooKeeperServer zks, ServerCnxn cnxn) {
            this.zks = zks;
            this.cnxn = cnxn;
        }

        public ZooKeeperServer getZks() {
            return zks;
        }

        public ServerCnxn getCnxn() {
            return cnxn;
        }
    }

    public static class MatchValues {
        private final String path;
        private final String id;
        private final String aclExpr;
        private final int perm;
        private final List<ACL> setAcls;

        /**
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
         */
        public MatchValues(String path, String id, String aclExpr, int perm, List<ACL> setAcls) {
            this.path = path;
            this.id = id;
            this.aclExpr = aclExpr;
            this.perm = perm;
            this.setAcls = setAcls;
        }

        public String getPath() {
            return path;
        }

        public String getId() {
            return id;
        }

        public String getAclExpr() {
            return aclExpr;
        }

        public int getPerm() {
            return perm;
        }

        public List<ACL> getSetAcls() {
            return setAcls;
        }
    }

    /**
     * This method is called when a client passes authentication data for this
     * scheme. The authData is directly from the authentication packet. The
     * implementor may attach new ids to the authInfo field of cnxn or may use
     * cnxn to send packets back to the client.
     *
     * @param serverObjs
     *                cnxn/server/etc that received the authentication information.
     * @param authData
     *                the authentication data received.
     * @return indication of success or failure
     */
    public abstract KeeperException.Code handleAuthentication(ServerObjs serverObjs, byte authData[]);

    /**
     * This method is called to see if the given id matches the given id
     * expression in the ACL. This allows schemes to use application specific
     * wild cards.
     *
     * @param serverObjs
     *                cnxn/server/etc that received the authentication information.
     * @param matchValues
     *                values to be matched
     */
    public abstract boolean matches(ServerObjs serverObjs, MatchValues matchValues);

    @Override
    public final KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final boolean matches(String id, String aclExpr) {
        throw new UnsupportedOperationException();
    }
}
