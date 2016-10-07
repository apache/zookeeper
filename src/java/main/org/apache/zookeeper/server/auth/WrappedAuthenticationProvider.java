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
 * Provides backwards compatibility between older {@link AuthenticationProvider}
 * implementations and newer {@link AuthenticationProvider} implementations.
 */
class WrappedAuthenticationProvider implements ServerAuthenticationProvider {
    private final AuthenticationProvider implementation;

    static ServerAuthenticationProvider wrap(AuthenticationProvider provider) {
        return (provider instanceof ServerAuthenticationProvider) ? (ServerAuthenticationProvider)provider
                : new WrappedAuthenticationProvider(provider);
    }

    private WrappedAuthenticationProvider(AuthenticationProvider implementation) {
        this.implementation = implementation;
    }

    @Override
    public void setZooKeeperServer(ZooKeeperServer zks) {
        // NOP
    }

    /**
     * {@inheritDoc}
     *
     * forwards to older method {@link #matches(String, String)}
     */
    @Override
    public boolean matchesOp(String path, String id, String aclExpr, int perm, List<ACL> setAcls) {
        return matches(id, aclExpr);
    }

    @Override
    public String getScheme() {
        return implementation.getScheme();
    }

    @Override
    public KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        return implementation.handleAuthentication(cnxn, authData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean matches(String id, String aclExpr) {
        return implementation.matches(id, aclExpr);
    }

    @Override
    public boolean isAuthenticated() {
        return implementation.isAuthenticated();
    }

    @Override
    public boolean isValid(String id) {
        return implementation.isValid(id);
    }
}
