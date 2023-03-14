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
import java.util.ArrayList;
import java.util.Arrays;
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
    public static final String ENFORCE_AUTH_SCHEMES = "zookeeper.enforce.auth.schemes";
    public static final String SESSION_REQUIRE_CLIENT_SASL_AUTH =
        "zookeeper.sessionRequireClientSASLAuth";
    public static final String SASL_AUTH_SCHEME = "sasl";

    private boolean enforceAuthEnabled;
    private List<String> enforceAuthSchemes = new ArrayList<>();
    private boolean saslAuthRequired;

    public AuthenticationHelper() {
        initConfigurations();
    }

    private void initConfigurations() {
        if (Boolean.parseBoolean(System.getProperty(SESSION_REQUIRE_CLIENT_SASL_AUTH, "false"))) {
            enforceAuthEnabled = true;
            enforceAuthSchemes.add(SASL_AUTH_SCHEME);
        } else {
            enforceAuthEnabled =
                Boolean.parseBoolean(System.getProperty(ENFORCE_AUTH_ENABLED, "false"));
            String enforceAuthSchemesProp = System.getProperty(ENFORCE_AUTH_SCHEMES);
            if (enforceAuthSchemesProp != null) {
                Arrays.stream(enforceAuthSchemesProp.split(",")).forEach(s -> {
                    enforceAuthSchemes.add(s.trim());
                });
            }
        }
        LOG.info("{} = {}", ENFORCE_AUTH_ENABLED, enforceAuthEnabled);
        LOG.info("{} = {}", ENFORCE_AUTH_SCHEMES, enforceAuthSchemes);
        validateConfiguredProperties();
        saslAuthRequired = enforceAuthEnabled && enforceAuthSchemes.contains(SASL_AUTH_SCHEME);
    }

    private void validateConfiguredProperties() {
        if (enforceAuthEnabled) {
            if (enforceAuthSchemes.isEmpty()) {
                String msg =
                    ENFORCE_AUTH_ENABLED + " is true " + ENFORCE_AUTH_SCHEMES + " must be  "
                        + "configured.";
                LOG.error(msg);
                throw new IllegalArgumentException(msg);
            }
            enforceAuthSchemes.forEach(scheme -> {
                if (ProviderRegistry.getProvider(scheme) == null) {
                    String msg = "Authentication scheme " + scheme + " is not available.";
                    LOG.error(msg);
                    throw new IllegalArgumentException(msg);
                }
            });
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
            if (enforceAuthSchemes.contains(id.getScheme())) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnforceAuthEnabled() {
        return enforceAuthEnabled;
    }

    /**
     * Returns true when authentication enforcement was success otherwise returns false
     * also closes the connection
     *
     * @param connection server connection
     * @param xid        current operation xid
     * @return true when authentication enforcement is success otherwise false
     */
    public boolean enforceAuthentication(ServerCnxn connection, int xid) throws IOException {
        if (isEnforceAuthEnabled() && !isCnxnAuthenticated(connection)) {
            //Un authenticated connection, lets inform user with response and then close the session
            LOG.error("Client authentication scheme(s) {} does not match with any of the expected "
                    + "authentication scheme {}, closing session.", getAuthSchemes(connection),
                enforceAuthSchemes);
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
