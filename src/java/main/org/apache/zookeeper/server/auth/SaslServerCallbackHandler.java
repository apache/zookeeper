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

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;

public class SaslServerCallbackHandler implements CallbackHandler {
    private String userName = null;
    private Map<String,String> credentials = new HashMap<String,String>();
    Logger LOG = LoggerFactory.getLogger(SaslServerCallbackHandler.class);

    public SaslServerCallbackHandler(Configuration configuration) throws IOException {
        AppConfigurationEntry configurationEntries[] = configuration.getAppConfigurationEntry("Server");

        if (configurationEntries == null) {
            String errorMessage = "could not find a 'Server' entry in this configuration: server cannot start.";
            LOG.error(errorMessage);
            throw(new IOException(errorMessage));
        }
        credentials.clear();
        for(AppConfigurationEntry entry: configurationEntries) {
            Map<String,?> options = entry.getOptions();
            // Populate DIGEST-MD5 user -> password map with JAAS configuration entries from the "Server" section.
            // Usernames are distinguished from other options by prefixing the username with a "user_" prefix.
            Iterator it = options.entrySet().iterator();
            while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
                    String key = (String)pair.getKey();
                    if (key.substring(0,5).equals("user_")) {
                        String userName = key.substring(5);
                        credentials.put(userName,(String)pair.getValue());
                    }
                }
            }
            return;
        }

        public void handle(Callback[] callbacks) throws
                UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callback;
                    // check to see if this user is in the user password database.
                    if (credentials.get(nc.getDefaultName()) != null) {
                        nc.setName(nc.getDefaultName());
                        this.userName = nc.getDefaultName();
                    }
                    else { // no such user.
                        LOG.warn("User '" + nc.getDefaultName() + "' not found in list of DIGEST-MD5 authenticateable users.");
                    }
                }
                else {
                    if (callback instanceof PasswordCallback) {
                        PasswordCallback pc = (PasswordCallback) callback;

                        if ((this.userName.equals("super")
                              &&
                              (System.getProperty("zookeeper.SASLAuthenticationProvider.superPassword") != null))) {
                            // superuser: use Java system property for password, if available.
                            pc.setPassword(System.getProperty("zookeeper.SASLAuthenticationProvider.superPassword").toCharArray());
                        }
                        else {
                            if (this.credentials.get(this.userName) != null) {
                                pc.setPassword(this.credentials.get(this.userName).toCharArray());
                            }
                            else {
                                LOG.warn("No password found for user: " + this.userName);
                            }
                        }
                    }
                    else {
                        if (callback instanceof RealmCallback) {
                            RealmCallback rc = (RealmCallback) callback;
                            LOG.debug("client supplied realm: " + rc.getDefaultText());
                            rc.setText(rc.getDefaultText());
                        }
                        else {
                            if (callback instanceof AuthorizeCallback) {
                                AuthorizeCallback ac = (AuthorizeCallback) callback;

                                String authenticationID = ac.getAuthenticationID();
                                String authorizationID = ac.getAuthorizationID();

                                LOG.info("Successfully authenticated client: authenticationID=" + authenticationID + ";  authorizationID=" + authorizationID + ".");
                                if (authenticationID.equals(authorizationID)) {
                                    LOG.debug("setAuthorized(true) since " + authenticationID + "==" + authorizationID);
                                    ac.setAuthorized(true);
                                } else {
                                    LOG.debug("setAuthorized(true), even though " + authenticationID + "!=" + authorizationID + ".");
                                    ac.setAuthorized(true);
                                }
                                if (ac.isAuthorized()) {
                                    LOG.debug("isAuthorized() since ac.isAuthorized() == true");
                                    // canonicalize authorization id according to system properties:
                                    // kerberos.removeRealmFromPrincipal(={true,false})
                                    // kerberos.removeHostFromPrincipal(={true,false})
                                    KerberosName kerberosName = new KerberosName(authenticationID);
                                    try {
                                        String userName = kerberosName.getShortName();
                                        if (!removeHost() && (kerberosName.getHostName() != null)) {
                                            userName += "/" + kerberosName.getServiceName();
                                        }
                                        if (!removeRealm() && (kerberosName.getRealm() != null)) {
                                            userName += "@" + kerberosName.getRealm();
                                        }
                                        LOG.info("Setting authorizedID: " + userName);
                                        ac.setAuthorizedID(userName);
                                    }
                                    catch (IOException e) {
                                        LOG.error("Failed to set name based on Kerberos authentication rules.");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private boolean removeRealm() {
        return ((System.getProperty("zookeeper.kerberos.removeRealmFromPrincipal") != null) &&
          (System.getProperty("zookeeper.kerberos.removeRealmFromPrincipal").equals("true")));
    }

    private boolean removeHost() {
        return ((System.getProperty("zookeeper.kerberos.removeHostFromPrincipal") != null) &&
          (System.getProperty("zookeeper.kerberos.removeHostFromPrincipal").equals("true")));
    }
}
