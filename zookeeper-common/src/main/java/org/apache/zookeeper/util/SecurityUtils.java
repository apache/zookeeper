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

package org.apache.zookeeper.util;

import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.apache.zookeeper.SaslClientCallbackHandler;
import org.apache.zookeeper.server.auth.KerberosName;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;

public final class SecurityUtils {

    public static final String QUORUM_HOSTNAME_PATTERN = "_HOST";

    /**
     * Create an instance of a SaslClient. It will return null if there is an exception.
     *
     * @param subject subject
     * @param servicePrincipal principal
     * @param protocol name of the protocol for which the authentication is being performed
     * @param serverName name of the server to authenticate to
     * @param LOG logger
     * @param entity can be either zookeeper client or quorum learner
     *
     * @return saslclient object
     * @throws SaslException
     */
    public static SaslClient createSaslClient(final Subject subject,
            final String servicePrincipal, final String protocol,
            final String serverName, final Logger LOG, final String entity) throws SaslException {
        SaslClient saslClient;
        // Use subject.getPrincipals().isEmpty() as an indication of which SASL
        // mechanism to use: if empty, use DIGEST-MD5; otherwise, use GSSAPI.
        if (subject.getPrincipals().isEmpty()) {
            // no principals: must not be GSSAPI: use DIGEST-MD5 mechanism
            // instead.
            LOG.info("{} will use DIGEST-MD5 as SASL mechanism.", entity);
            String[] mechs = { "DIGEST-MD5" };
            String username = (String) (subject.getPublicCredentials()
                    .toArray()[0]);
            String password = (String) (subject.getPrivateCredentials()
                    .toArray()[0]);
            // 'domain' parameter is hard-wired between the server and client
            saslClient = Sasl.createSaslClient(mechs, username, protocol,
                    serverName, null, new SaslClientCallbackHandler(password, entity));
            return saslClient;
        } else { // GSSAPI.
            final Object[] principals = subject.getPrincipals().toArray();
            // determine client principal from subject.
            final Principal clientPrincipal = (Principal) principals[0];
            boolean usingNativeJgss = Boolean
                    .getBoolean("sun.security.jgss.native");
            if (usingNativeJgss) {
                // http://docs.oracle.com/javase/6/docs/technotes/guides/security/jgss/jgss-features.html
                // """
                // In addition, when performing operations as a particular
                // Subject, e.g. Subject.doAs(...) or
                // Subject.doAsPrivileged(...),
                // the to-be-used GSSCredential should be added to Subject's
                // private credential set. Otherwise, the GSS operations will
                // fail since no credential is found.
                // """
                try {
                    GSSManager manager = GSSManager.getInstance();
                    Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
                    GSSCredential cred = manager.createCredential(null,
                            GSSContext.DEFAULT_LIFETIME, krb5Mechanism,
                            GSSCredential.INITIATE_ONLY);
                    subject.getPrivateCredentials().add(cred);
                    LOG.debug("Added private credential to {} principal name: '{}'",
                            entity, clientPrincipal);
                } catch (GSSException ex) {
                    LOG.warn("Cannot add private credential to subject; "
                                    + "authentication at the server may fail", ex);
                }
            }
            final KerberosName clientKerberosName = new KerberosName(
                    clientPrincipal.getName());
            // assume that server and client are in the same realm (by default;
            // unless the system property
            // "zookeeper.server.realm" is set).
            String serverRealm = System.getProperty("zookeeper.server.realm",
                    clientKerberosName.getRealm());
            KerberosName serviceKerberosName = new KerberosName(
                    servicePrincipal + "@" + serverRealm);
            final String serviceName = serviceKerberosName.getServiceName();
            final String serviceHostname = serviceKerberosName.getHostName();
            final String clientPrincipalName = clientKerberosName.toString();
            try {
                saslClient = Subject.doAs(subject,
                        new PrivilegedExceptionAction<SaslClient>() {
                            public SaslClient run() throws SaslException {
                                LOG.info("{} will use GSSAPI as SASL mechanism.", entity);
                                String[] mechs = { "GSSAPI" };
                                LOG.debug("creating sasl client: {}={};service={};serviceHostname={}",
                                        new Object[] { entity, clientPrincipalName, serviceName, serviceHostname });
                                SaslClient saslClient = Sasl.createSaslClient(
                                        mechs, clientPrincipalName, serviceName,
                                        serviceHostname, null,
                                        new SaslClientCallbackHandler(null, entity));
                                return saslClient;
                            }
                        });
                return saslClient;
            } catch (Exception e) {
                LOG.error("Exception while trying to create SASL client", e);
                return null;
            }
        }
    }

    /**
     * Create an instance of a SaslServer. It will return null if there is an exception.
     *
     * @param subject subject
     * @param protocol protocol
     * @param serverName server name
     * @param callbackHandler login callback handler
     * @param LOG logger
     * @return sasl server object
     */
    public static SaslServer createSaslServer(final Subject subject,
            final String protocol, final String serverName,
            final CallbackHandler callbackHandler, final Logger LOG) {
        if (subject != null) {
            // server is using a JAAS-authenticated subject: determine service
            // principal name and hostname from zk server's subject.
            if (subject.getPrincipals().size() > 0) {
                try {
                    final Object[] principals = subject.getPrincipals()
                            .toArray();
                    final Principal servicePrincipal = (Principal) principals[0];

                    // e.g. servicePrincipalNameAndHostname :=
                    // "zookeeper/myhost.foo.com@FOO.COM"
                    final String servicePrincipalNameAndHostname = servicePrincipal
                            .getName();

                    int indexOf = servicePrincipalNameAndHostname.indexOf("/");

                    // e.g. servicePrincipalName := "zookeeper"
                    final String servicePrincipalName = servicePrincipalNameAndHostname
                            .substring(0, indexOf);

                    // e.g. serviceHostnameAndKerbDomain :=
                    // "myhost.foo.com@FOO.COM"
                    final String serviceHostnameAndKerbDomain = servicePrincipalNameAndHostname
                            .substring(indexOf + 1,
                                    servicePrincipalNameAndHostname.length());

                    indexOf = serviceHostnameAndKerbDomain.indexOf("@");
                    // e.g. serviceHostname := "myhost.foo.com"
                    final String serviceHostname = serviceHostnameAndKerbDomain
                            .substring(0, indexOf);

                    // TODO: should depend on zoo.cfg specified mechs, but if
                    // subject is non-null, it can be assumed to be GSSAPI.
                    final String mech = "GSSAPI";

                    LOG.debug("serviceHostname is '" + serviceHostname + "'");
                    LOG.debug("servicePrincipalName is '" + servicePrincipalName
                            + "'");
                    LOG.debug("SASL mechanism(mech) is '" + mech + "'");

                    boolean usingNativeJgss = Boolean
                            .getBoolean("sun.security.jgss.native");
                    if (usingNativeJgss) {
                        // http://docs.oracle.com/javase/6/docs/technotes/guides/security/jgss/jgss-features.html
                        // """
                        // In addition, when performing operations as a
                        // particular
                        // Subject, e.g. Subject.doAs(...) or
                        // Subject.doAsPrivileged(...), the to-be-used
                        // GSSCredential should be added to Subject's
                        // private credential set. Otherwise, the GSS operations
                        // will fail since no credential is found.
                        // """
                        try {
                            GSSManager manager = GSSManager.getInstance();
                            Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
                            GSSName gssName = manager.createName(
                                    servicePrincipalName + "@"
                                            + serviceHostname,
                                    GSSName.NT_HOSTBASED_SERVICE);
                            GSSCredential cred = manager.createCredential(
                                    gssName, GSSContext.DEFAULT_LIFETIME,
                                    krb5Mechanism, GSSCredential.ACCEPT_ONLY);
                            subject.getPrivateCredentials().add(cred);
                            LOG.debug("Added private credential to service principal name: '{}',"
                                            + " GSSCredential name: {}", servicePrincipalName, cred.getName());
                        } catch (GSSException ex) {
                            LOG.warn("Cannot add private credential to subject; "
                                            + "clients authentication may fail", ex);
                        }
                    }
                    try {
                        return Subject.doAs(subject,
                                new PrivilegedExceptionAction<SaslServer>() {
                                    public SaslServer run() {
                                        try {
                                            SaslServer saslServer;
                                            saslServer = Sasl.createSaslServer(
                                                    mech, servicePrincipalName,
                                                    serviceHostname, null,
                                                    callbackHandler);
                                            return saslServer;
                                        } catch (SaslException e) {
                                            LOG.error("Zookeeper Server failed to create a SaslServer to interact with a client during session initiation: ", e);
                                            return null;
                                        }
                                    }
                                });
                    } catch (PrivilegedActionException e) {
                        // TODO: exit server at this point(?)
                        LOG.error("Zookeeper Quorum member experienced a PrivilegedActionException exception while creating a SaslServer using a JAAS principal context:", e);
                    }
                } catch (IndexOutOfBoundsException e) {
                    LOG.error("server principal name/hostname determination error: ", e);
                }
            } else {
                // JAAS non-GSSAPI authentication: assuming and supporting only
                // DIGEST-MD5 mechanism for now.
                // TODO: use 'authMech=' value in zoo.cfg.
                try {
                    SaslServer saslServer = Sasl.createSaslServer("DIGEST-MD5",
                            protocol, serverName, null, callbackHandler);
                    return saslServer;
                } catch (SaslException e) {
                    LOG.error("Zookeeper Quorum member failed to create a SaslServer to interact with a client during session initiation", e);
                }
            }
        }
        return null;
    }

    /**
     * Convert Kerberos principal name pattern to valid Kerberos principal name.
     * If the principal name contains hostname pattern "_HOST" then it replaces
     * with the given hostname, which should be fully-qualified domain name.
     *
     * @param principalConfig
     *            the Kerberos principal name conf value to convert
     * @param hostname
     *            the fully-qualified domain name used for substitution
     * @return converted Kerberos principal name
     */
    public static String getServerPrincipal(String principalConfig,
            String hostname) {
        String[] components = getComponents(principalConfig);
        if (components == null || components.length != 2
                || !components[1].equals(QUORUM_HOSTNAME_PATTERN)) {
            return principalConfig;
        } else {
            return replacePattern(components, hostname);
        }
    }

    private static String[] getComponents(String principalConfig) {
        if (principalConfig == null)
            return null;
        return principalConfig.split("[/]");
    }

    private static String replacePattern(String[] components, String hostname) {
        return components[0] + "/" + hostname.toLowerCase();
    }
}
