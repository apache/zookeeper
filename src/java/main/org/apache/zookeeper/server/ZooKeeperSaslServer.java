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

package org.apache.zookeeper.server;

import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import org.apache.zookeeper.Login;

public class ZooKeeperSaslServer {
    public static final String LOGIN_CONTEXT_NAME_KEY = "zookeeper.sasl.serverconfig";
    public static final String DEFAULT_LOGIN_CONTEXT_NAME = "Server";

    Logger LOG = LoggerFactory.getLogger(ZooKeeperSaslServer.class);
    private SaslServer saslServer;

    ZooKeeperSaslServer(final Login login) {
        saslServer = createSaslServer(login);
    }

    private SaslServer createSaslServer(final Login login) {
        synchronized (login) {
            Subject subject = login.getSubject();
            if (subject != null) {
                // server is using a JAAS-authenticated subject: determine service principal name and hostname from zk server's subject.
                if (subject.getPrincipals().size() > 0) {
                    try {
                        final Object[] principals = subject.getPrincipals().toArray();
                        final Principal servicePrincipal = (Principal)principals[0];

                        // e.g. servicePrincipalNameAndHostname := "zookeeper/myhost.foo.com@FOO.COM"
                        final String servicePrincipalNameAndHostname = servicePrincipal.getName();

                        int indexOf = servicePrincipalNameAndHostname.indexOf("/");

                        // e.g. servicePrincipalName := "zookeeper"
                        final String servicePrincipalName = servicePrincipalNameAndHostname.substring(0, indexOf);

                        // e.g. serviceHostnameAndKerbDomain := "myhost.foo.com@FOO.COM"
                        final String serviceHostnameAndKerbDomain = servicePrincipalNameAndHostname.substring(indexOf+1,servicePrincipalNameAndHostname.length());

                        indexOf = serviceHostnameAndKerbDomain.indexOf("@");
                        // e.g. serviceHostname := "myhost.foo.com"
                        final String serviceHostname = serviceHostnameAndKerbDomain.substring(0,indexOf);

                        final String mech = "GSSAPI";   // TODO: should depend on zoo.cfg specified mechs, but if subject is non-null, it can be assumed to be GSSAPI.

                        LOG.debug("serviceHostname is '"+ serviceHostname + "'");
                        LOG.debug("servicePrincipalName is "+ servicePrincipalName + "'");
                        LOG.debug("SASL mechanism(mech) is "+ mech +"'");

                        try {
                            return Subject.doAs(subject,new PrivilegedExceptionAction<SaslServer>() {
                                public SaslServer run() {
                                    try {
                                        SaslServer saslServer;
                                        saslServer = Sasl.createSaslServer(mech, servicePrincipalName, serviceHostname, null, login.callbackHandler);
                                        return saslServer;
                                    }
                                    catch (SaslException e) {
                                        LOG.error("Zookeeper Server failed to create a SaslServer to interact with a client during session initiation: " + e);
                                        e.printStackTrace();
                                        return null;
                                    }
                                }
                            }
                            );
                        }
                        catch (PrivilegedActionException e) {
                            // TODO: exit server at this point(?)
                            LOG.error("Zookeeper Quorum member experienced a PrivilegedActionException exception while creating a SaslServer using a JAAS principal context:" + e);
                            e.printStackTrace();
                        }
                    }
                    catch (Exception e) {
                        LOG.error("server principal name/hostname determination error: " + e);
                    }
                }
                else {
                    // JAAS non-GSSAPI authentication: assuming and supporting only DIGEST-MD5 mechanism for now.
                    // TODO: use 'authMech=' value in zoo.cfg.
                    try {
                        SaslServer saslServer = Sasl.createSaslServer("DIGEST-MD5","zookeeper","zk-sasl-md5",null, login.callbackHandler);
                        return saslServer;
                    }
                    catch (SaslException e) {
                        LOG.error("Zookeeper Quorum member failed to create a SaslServer to interact with a client during session initiation: " + e);
                    }
                }
            }
        }
        LOG.error("failed to create saslServer object.");
        return null;
    }

    public byte[] evaluateResponse(byte[] response) throws SaslException {
        return saslServer.evaluateResponse(response);
    }

    public boolean isComplete() {
        return saslServer.isComplete();
    }

    public String getAuthorizationID() {
        return saslServer.getAuthorizationID();
    }

}




