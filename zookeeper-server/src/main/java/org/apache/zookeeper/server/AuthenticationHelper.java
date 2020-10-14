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
import java.util.List;
import java.util.stream.Collectors;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains helper methods to enforce authentication
 */
public class AuthenticationHelper {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationHelper.class);

    public static final String ENFORCE_AUTH_ENABLED = "zookeeper.enforce.auth.enabled";
    public static final String ENFORCE_AUTH_SCHEME = "zookeeper.enforce.auth.scheme";
    public static final String SESSION_REQUIRE_CLIENT_SASL_AUTH =
        "zookeeper.sessionRequireClientSASLAuth";
    public static final String SASL_AUTH_SCHEME = "sasl";

    private boolean enforceAuthEnabled;
    private String enforceAuthScheme;
    private boolean saslAuthRequired;

    public AuthenticationHelper() {
        initConfigurations();
    }

    private void initConfigurations() {
        if (Boolean.parseBoolean(System.getProperty(SESSION_REQUIRE_CLIENT_SASL_AUTH, "false"))) {
            enforceAuthEnabled = true;
            enforceAuthScheme = SASL_AUTH_SCHEME;
        } else {
            enforceAuthEnabled =
                Boolean.parseBoolean(System.getProperty(ENFORCE_AUTH_ENABLED, "false"));
            enforceAuthScheme = System.getProperty(ENFORCE_AUTH_SCHEME);
        }
        LOG.info("{} = {}", ENFORCE_AUTH_ENABLED, enforceAuthEnabled);
        LOG.info("{} = {}", ENFORCE_AUTH_SCHEME, enforceAuthScheme);
        validateConfiguredProperties();
        saslAuthRequired = enforceAuthEnabled && SASL_AUTH_SCHEME.equals(enforceAuthScheme);
    }

    private void validateConfiguredProperties() {
        if (enforceAuthEnabled) {
            if (enforceAuthScheme == null) {
                String msg = ENFORCE_AUTH_ENABLED + " is true " + ENFORCE_AUTH_SCHEME + " must be  "
                    + "configured.";
                LOG.error(msg);
                throw new IllegalArgumentException(msg);
            }
            if (ProviderRegistry.getProvider(enforceAuthScheme) == null) {
                String msg = "Authentication scheme " + enforceAuthScheme + " is not available.";
                LOG.error(msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    /**
     * Checks if connection is authenticated or not.
     *
     * @param cnxn server connection
     * @return boolean
     */
    private boolean isCnxnAuthenticated(ServerCnxn cnxn) {
        for (Id id : cnxn.getAuthInfo()) {
            if (id.getScheme().equals(enforceAuthScheme)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnforceAuthEnabled() {
        return enforceAuthEnabled;
    }

    /**
     * Returns true when authentication enforcement was success otherwise returns false also closes the connection
     *
     * @param connection server connection
     * @param xid        current operation xid
     * @return true when authentication enforcement is success otherwise false
     */
    public boolean enforceAuthentication(ServerCnxn connection, int xid) throws IOException {
        if (isEnforceAuthEnabled() && !isCnxnAuthenticated(connection)) {
            //Un authenticated connection, lets inform user with response and then close the session
            LOG.error(
                "Client authentication scheme(s) {} does not match with expected  authentication scheme {}, "
                    + "closing session.", getAuthSchemes(connection), enforceAuthScheme);
            ReplyHeader replyHeader = new ReplyHeader(xid, 0,
                KeeperException.Code.SESSIONCLOSEDREQUIRESASLAUTH.intValue());
            connection.sendResponse(replyHeader, null, "response");
            connection.sendCloseSession();
            connection.disableRecv();
            return false;
        }
        return true;
    }

    private List<String> getAuthSchemes(ServerCnxn connection) {
        return connection.getAuthInfo().stream().map(Id::getScheme).collect(Collectors.toList());
    }

    public boolean isSaslAuthRequired() {
        return saslAuthRequired;
    }
}
