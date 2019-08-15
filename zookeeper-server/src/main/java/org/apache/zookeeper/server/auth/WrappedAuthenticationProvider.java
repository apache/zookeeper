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

package org.apache.zookeeper.server.auth;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.ServerCnxn;

/**
 * Provides backwards compatibility between older {@link AuthenticationProvider}
 * implementations and the new {@link ServerAuthenticationProvider} interface.
 */
class WrappedAuthenticationProvider extends ServerAuthenticationProvider {

    private final AuthenticationProvider implementation;

    static ServerAuthenticationProvider wrap(AuthenticationProvider provider) {
        if (provider == null) {
            return null;
        }
        return (provider instanceof ServerAuthenticationProvider)
            ? (ServerAuthenticationProvider) provider
            : new WrappedAuthenticationProvider(provider);
    }

    private WrappedAuthenticationProvider(AuthenticationProvider implementation) {
        this.implementation = implementation;
    }

    /**
     * {@inheritDoc}
     *
     * forwards to older method {@link #handleAuthentication(ServerCnxn, byte[])}
     */
    @Override
    public KeeperException.Code handleAuthentication(ServerObjs serverObjs, byte[] authData) {
        return implementation.handleAuthentication(serverObjs.getCnxn(), authData);
    }

    /**
     * {@inheritDoc}
     *
     * forwards to older method {@link #matches(String, String)}
     */
    @Override
    public boolean matches(ServerObjs serverObjs, MatchValues matchValues) {
        return implementation.matches(matchValues.getId(), matchValues.getAclExpr());
    }

    @Override
    public String getScheme() {
        return implementation.getScheme();
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
