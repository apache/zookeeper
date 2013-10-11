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

package org.apache.zookeeper.client;

import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.GetSASLRequest;
import org.apache.zookeeper.proto.SetSASLResponse;
import org.apache.zookeeper.server.auth.KerberosName;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages SASL authentication for the client. It
 * allows ClientCnxn to authenticate using SASL with a Zookeeper server.
 */
public class ZooKeeperSaslClient {
    public static final String LOGIN_CONTEXT_NAME_KEY = "zookeeper.sasl.clientconfig";
    public static final String ENABLE_CLIENT_SASL_KEY = "zookeeper.sasl.client";
    public static final String ENABLE_CLIENT_SASL_DEFAULT = "true";

    /**
     * Returns true if the SASL client is enabled. By default, the client
     * is enabled but can be disabled by setting the system property
     * <code>zookeeper.sasl.client</code> to <code>false</code>. See
     * ZOOKEEPER-1657 for more information.
     *
     * @return If the SASL client is enabled.
     */
    public static boolean isEnabled() {
        return Boolean.valueOf(System.getProperty(ENABLE_CLIENT_SASL_KEY, ENABLE_CLIENT_SASL_DEFAULT));
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperSaslClient.class);
    private static Login login = null;
    private SaslClient saslClient;
    private boolean isSASLConfigured = true;

    private byte[] saslToken = new byte[0];

    public enum SaslState {
        INITIAL,INTERMEDIATE,COMPLETE,FAILED
    }

    private SaslState saslState = SaslState.INITIAL;

    private boolean gotLastPacket = false;
    /** informational message indicating the current configuration status */
    private final String configStatus;

    public SaslState getSaslState() {
        return saslState;
    }

    public String getLoginContext() {
        if (login != null)
            return login.getLoginContextName();
        return null;
    }

    public ZooKeeperSaslClient(final String serverPrincipal)
            throws LoginException {
        /**
         * ZOOKEEPER-1373: allow system property to specify the JAAS
         * configuration section that the zookeeper client should use.
         * Default to "Client".
         */
        String clientSection = System.getProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY, "Client");
        // Note that 'Configuration' here refers to javax.security.auth.login.Configuration.
        AppConfigurationEntry entries[] = null;
        RuntimeException runtimeException = null;
        try {
            entries = Configuration.getConfiguration().getAppConfigurationEntry(clientSection);
        } catch (SecurityException e) {
            // handle below: might be harmless if the user doesn't intend to use JAAS authentication.
            runtimeException = e;
        } catch (IllegalArgumentException e) {
            // third party customized getAppConfigurationEntry could throw IllegalArgumentException when JAAS
            // configuration isn't set. We can reevaluate whether to catch RuntimeException instead when more 
            // different types of RuntimeException found
            runtimeException = e;
        }
        if (entries != null) {
            this.configStatus = "Will attempt to SASL-authenticate using Login Context section '" + clientSection + "'";
            this.saslClient = createSaslClient(serverPrincipal, clientSection);
        } else {
            // Handle situation of clientSection's being null: it might simply because the client does not intend to 
            // use SASL, so not necessarily an error.
            saslState = SaslState.FAILED;
            String explicitClientSection = System.getProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY);
            if (explicitClientSection != null) {
                // If the user explicitly overrides the default Login Context, they probably expected SASL to
                // succeed. But if we got here, SASL failed.
                if (runtimeException != null) {
                    throw new LoginException("Zookeeper client cannot authenticate using the " + explicitClientSection +
                            " section of the supplied JAAS configuration: '" +
                            System.getProperty(Environment.JAAS_CONF_KEY) + "' because of a " +
                            "RuntimeException: " + runtimeException);
                } else {
                    throw new LoginException("Client cannot SASL-authenticate because the specified JAAS configuration " +
                            "section '" + explicitClientSection + "' could not be found.");
                }
            } else {
                // The user did not override the default context. It might be that they just don't intend to use SASL,
                // so log at INFO, not WARN, since they don't expect any SASL-related information.
                String msg = "Will not attempt to authenticate using SASL ";
                if (runtimeException != null) {
                    msg += "(" + runtimeException + ")";
                } else {
                    msg += "(unknown error)";
                }
                this.configStatus = msg;
                this.isSASLConfigured = false;
            }
            if (System.getProperty(Environment.JAAS_CONF_KEY)  != null) {
                // Again, the user explicitly set something SASL-related, so they probably expected SASL to succeed.
                if (runtimeException != null) {
                    throw new LoginException("Zookeeper client cannot authenticate using the '" +
                            System.getProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY, "Client") +
                            "' section of the supplied JAAS configuration: '" +
                            System.getProperty(Environment.JAAS_CONF_KEY) + "' because of a " +
                            "RuntimeException: " + runtimeException);
                } else {
                    throw new LoginException("No JAAS configuration section named '" +
                            System.getProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY, "Client") +
                            "' was found in specified JAAS configuration file: '" +
                            System.getProperty(Environment.JAAS_CONF_KEY) + "'.");
                }
            }
        }
    }
    
    /**
     * @return informational message indicating the current configuration status.
     */
    public String getConfigStatus() {
        return configStatus;
    }

    public boolean isComplete() {
        return (saslState == SaslState.COMPLETE);
    }

    public boolean isFailed() {
        return (saslState == SaslState.FAILED);
    }

    public static class ServerSaslResponseCallback implements AsyncCallback.DataCallback {
        public void processResult(int rc, String path, Object ctx, byte data[], Stat stat) {
            // processResult() is used by ClientCnxn's sendThread to respond to
            // data[] contains the Zookeeper Server's SASL token.
            // ctx is the ZooKeeperSaslClient object. We use this object's respondToServer() method
            // to reply to the Zookeeper Server's SASL token
            ZooKeeperSaslClient client = ((ClientCnxn)ctx).zooKeeperSaslClient;
            if (client == null) {
                LOG.warn("sasl client was unexpectedly null: cannot respond to Zookeeper server.");
                return;
            }
            byte[] usedata = data;
            if (data != null) {
                LOG.debug("ServerSaslResponseCallback(): saslToken server response: (length="+usedata.length+")");
            }
            else {
                usedata = new byte[0];
                LOG.debug("ServerSaslResponseCallback(): using empty data[] as server response (length="+usedata.length+")");
            }
            client.respondToServer(usedata, (ClientCnxn)ctx);
        }
    }

    synchronized private SaslClient createSaslClient(final String servicePrincipal,
                                                     final String loginContext) throws LoginException {
        try {
            if (login == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("JAAS loginContext is: " + loginContext);
                }
                // note that the login object is static: it's shared amongst all zookeeper-related connections.
                // createSaslClient() must be declared synchronized so that login is initialized only once.
                login = new Login(loginContext, new ClientCallbackHandler(null));
                login.startThreadIfNeeded();
            }
            Subject subject = login.getSubject();
            SaslClient saslClient;
            // Use subject.getPrincipals().isEmpty() as an indication of which SASL mechanism to use:
            // if empty, use DIGEST-MD5; otherwise, use GSSAPI.
            if (subject.getPrincipals().isEmpty()) {
                // no principals: must not be GSSAPI: use DIGEST-MD5 mechanism instead.
                LOG.info("Client will use DIGEST-MD5 as SASL mechanism.");
                String[] mechs = {"DIGEST-MD5"};
                String username = (String)(subject.getPublicCredentials().toArray()[0]);
                String password = (String)(subject.getPrivateCredentials().toArray()[0]);
                // "zk-sasl-md5" is a hard-wired 'domain' parameter shared with zookeeper server code (see ServerCnxnFactory.java)
                saslClient = Sasl.createSaslClient(mechs, username, "zookeeper", "zk-sasl-md5", null, new ClientCallbackHandler(password));
                return saslClient;
            }
            else { // GSSAPI.
            	boolean usingNativeJgss =
            			Boolean.getBoolean("sun.security.jgss.native");
            	if (usingNativeJgss) {
            		// http://docs.oracle.com/javase/6/docs/technotes/guides/security/jgss/jgss-features.html
            		// """
            		// In addition, when performing operations as a particular
            		// Subject, e.g. Subject.doAs(...) or Subject.doAsPrivileged(...),
            		// the to-be-used GSSCredential should be added to Subject's
            		// private credential set. Otherwise, the GSS operations will
            		// fail since no credential is found.
            		// """
            		try {
            			GSSManager manager = GSSManager.getInstance();
            			Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
            			GSSCredential cred = manager.createCredential(null,
            					GSSContext.DEFAULT_LIFETIME,
            					krb5Mechanism,
            					GSSCredential.INITIATE_ONLY);
            			subject.getPrivateCredentials().add(cred);
            			if (LOG.isDebugEnabled()) {
            				LOG.debug("Added private credential to subject: " + cred);
            			}
            		} catch (GSSException ex) {
            			LOG.warn("Cannot add private credential to subject; " +
            					"authentication at the server may fail", ex);
            		}
            	}
                final Object[] principals = subject.getPrincipals().toArray();
                // determine client principal from subject.
                final Principal clientPrincipal = (Principal)principals[0];
                final KerberosName clientKerberosName = new KerberosName(clientPrincipal.getName());
                // assume that server and client are in the same realm (by default; unless the system property
                // "zookeeper.server.realm" is set).
                String serverRealm = System.getProperty("zookeeper.server.realm",clientKerberosName.getRealm());
                KerberosName serviceKerberosName = new KerberosName(servicePrincipal+"@"+serverRealm);
                final String serviceName = serviceKerberosName.getServiceName();
                final String serviceHostname = serviceKerberosName.getHostName();
                final String clientPrincipalName = clientKerberosName.toString();
                try {
                    saslClient = Subject.doAs(subject,new PrivilegedExceptionAction<SaslClient>() {
                        public SaslClient run() throws SaslException {
                            LOG.info("Client will use GSSAPI as SASL mechanism.");
                            String[] mechs = {"GSSAPI"};
                            LOG.debug("creating sasl client: client="+clientPrincipalName+";service="+serviceName+";serviceHostname="+serviceHostname);
                            SaslClient saslClient = Sasl.createSaslClient(mechs,clientPrincipalName,serviceName,serviceHostname,null,new ClientCallbackHandler(null));
                            return saslClient;
                        }
                    });
                    return saslClient;
                }
                catch (Exception e) {
                	LOG.error("Exception while trying to create SASL client", e);
                    e.printStackTrace();
                    return null;
                }
            }
        } catch (LoginException e) {
            // We throw LoginExceptions...
            throw e;
        } catch (Exception e) {
            // ..but consume (with a log message) all other types of exceptions.
            LOG.error("Exception while trying to create SASL client: " + e);
            return null;
        }
    }

    public void respondToServer(byte[] serverToken, ClientCnxn cnxn) {
        if (saslClient == null) {
            LOG.error("saslClient is unexpectedly null. Cannot respond to server's SASL message; ignoring.");
            return;
        }

        if (!(saslClient.isComplete())) {
            try {
                saslToken = createSaslToken(serverToken);
                if (saslToken != null) {
                    sendSaslPacket(saslToken, cnxn);
                }
            } catch (SaslException e) {
                LOG.error("SASL authentication failed using login context '" +
                        this.getLoginContext() + "'.");
                saslState = SaslState.FAILED;
                gotLastPacket = true;
            }
        }

        if (saslClient.isComplete()) {
            // GSSAPI: server sends a final packet after authentication succeeds
            // or fails.
            if ((serverToken == null) && (saslClient.getMechanismName().equals("GSSAPI")))
                gotLastPacket = true;
            // non-GSSAPI: no final packet from server.
            if (!saslClient.getMechanismName().equals("GSSAPI")) {
                gotLastPacket = true;
            }
            // SASL authentication is completed, successfully or not:
            // enable the socket's writable flag so that any packets waiting for authentication to complete in
            // the outgoing queue will be sent to the Zookeeper server.
            cnxn.enableWrite();
        }
    }

    private byte[] createSaslToken() throws SaslException {
        saslState = SaslState.INTERMEDIATE;
        return createSaslToken(saslToken);
    }

    private byte[] createSaslToken(final byte[] saslToken) throws SaslException {
        if (saslToken == null) {
            // TODO: introspect about runtime environment (such as jaas.conf)
            saslState = SaslState.FAILED;
            throw new SaslException("Error in authenticating with a Zookeeper Quorum member: the quorum member's saslToken is null.");
        }

        Subject subject = login.getSubject();
        if (subject != null) {
            synchronized(login) {
                try {
                    final byte[] retval =
                        Subject.doAs(subject, new PrivilegedExceptionAction<byte[]>() {
                                public byte[] run() throws SaslException {
                                    LOG.debug("saslClient.evaluateChallenge(len="+saslToken.length+")");
                                    return saslClient.evaluateChallenge(saslToken);
                                }
                            });
                    return retval;
                }
                catch (PrivilegedActionException e) {
                    String error = "An error: (" + e + ") occurred when evaluating Zookeeper Quorum Member's " +
                      " received SASL token.";
                    // Try to provide hints to use about what went wrong so they can fix their configuration.
                    // TODO: introspect about e: look for GSS information.
                    final String UNKNOWN_SERVER_ERROR_TEXT =
                      "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
                    if (e.toString().indexOf(UNKNOWN_SERVER_ERROR_TEXT) > -1) {
                        error += " This may be caused by Java's being unable to resolve the Zookeeper Quorum Member's" +
                          " hostname correctly. You may want to try to adding" +
                          " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment.";
                    }
                    error += " Zookeeper Client will go to AUTH_FAILED state.";
                    LOG.error(error);
                    saslState = SaslState.FAILED;
                    throw new SaslException(error);
                }
            }
        }
        else {
            throw new SaslException("Cannot make SASL token without subject defined. " +
              "For diagnosis, please look for WARNs and ERRORs in your log related to the Login class.");
        }
    }

    private void sendSaslPacket(byte[] saslToken, ClientCnxn cnxn)
      throws SaslException{
        if (LOG.isDebugEnabled()) {
            LOG.debug("ClientCnxn:sendSaslPacket:length="+saslToken.length);
        }

        GetSASLRequest request = new GetSASLRequest();
        request.setToken(saslToken);
        SetSASLResponse response = new SetSASLResponse();
        ServerSaslResponseCallback cb = new ServerSaslResponseCallback();

        try {
            cnxn.sendPacket(request,response,cb, ZooDefs.OpCode.sasl);
        } catch (IOException e) {
            throw new SaslException("Failed to send SASL packet to server.",
                e);
        }
    }

    private void sendSaslPacket(ClientCnxn cnxn) throws SaslException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ClientCnxn:sendSaslPacket:length="+saslToken.length);
        }
        GetSASLRequest request = new GetSASLRequest();
        request.setToken(createSaslToken());
        SetSASLResponse response = new SetSASLResponse();
        ServerSaslResponseCallback cb = new ServerSaslResponseCallback();
        try {
            cnxn.sendPacket(request,response,cb, ZooDefs.OpCode.sasl);
        } catch (IOException e) {
            throw new SaslException("Failed to send SASL packet to server due " +
              "to IOException:", e);
        }
    }

    // used by ClientCnxn to know whether to emit a SASL-related event: either AuthFailed or SaslAuthenticated,
    // or none, if not ready yet. Sets saslState to COMPLETE as a side-effect.
    public KeeperState getKeeperState() {
        if (saslClient != null) {
            if (saslState == SaslState.FAILED) {
              return KeeperState.AuthFailed;
            }
            if (saslClient.isComplete()) {
                if (saslState == SaslState.INTERMEDIATE) {
                    saslState = SaslState.COMPLETE;
                    return KeeperState.SaslAuthenticated;
                }
            }
        }
        // No event ready to emit yet.
        return null;
    }

    // Initialize the client's communications with the Zookeeper server by sending the server the first
    // authentication packet.
    public void initialize(ClientCnxn cnxn) throws SaslException {
        if (saslClient == null) {
            saslState = SaslState.FAILED;
            throw new SaslException("saslClient failed to initialize properly: it's null.");
        }
        if (saslState == SaslState.INITIAL) {
            if (saslClient.hasInitialResponse()) {
                sendSaslPacket(cnxn);
            }
            else {
                byte[] emptyToken = new byte[0];
                sendSaslPacket(emptyToken, cnxn);
            }
            saslState = SaslState.INTERMEDIATE;
        }
    }

    // The CallbackHandler interface here refers to
    // javax.security.auth.callback.CallbackHandler.
    // It should not be confused with Zookeeper packet callbacks like
    //  org.apache.zookeeper.server.auth.SaslServerCallbackHandler.
    public static class ClientCallbackHandler implements CallbackHandler {
        private String password = null;

        public ClientCallbackHandler(String password) {
            this.password = password;
        }

        public void handle(Callback[] callbacks) throws
          UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callback;
                    nc.setName(nc.getDefaultName());
                }
                else {
                    if (callback instanceof PasswordCallback) {
                        PasswordCallback pc = (PasswordCallback)callback;
                        if (password != null) {
                            pc.setPassword(this.password.toCharArray());
                        } else {
                            LOG.warn("Could not login: the client is being asked for a password, but the Zookeeper" +
                              " client code does not currently support obtaining a password from the user." +
                              " Make sure that the client is configured to use a ticket cache (using" +
                              " the JAAS configuration setting 'useTicketCache=true)' and restart the client. If" +
                              " you still get this message after that, the TGT in the ticket cache has expired and must" +
                              " be manually refreshed. To do so, first determine if you are using a password or a" +
                              " keytab. If the former, run kinit in a Unix shell in the environment of the user who" +
                              " is running this Zookeeper client using the command" +
                              " 'kinit <princ>' (where <princ> is the name of the client's Kerberos principal)." +
                              " If the latter, do" +
                              " 'kinit -k -t <keytab> <princ>' (where <princ> is the name of the Kerberos principal, and" +
                              " <keytab> is the location of the keytab file). After manually refreshing your cache," +
                              " restart this client. If you continue to see this message after manually refreshing" +
                              " your cache, ensure that your KDC host's clock is in sync with this host's clock.");
                        }
                    }
                    else {
                        if (callback instanceof RealmCallback) {
                            RealmCallback rc = (RealmCallback) callback;
                            rc.setText(rc.getDefaultText());
                        }
                        else {
                            if (callback instanceof AuthorizeCallback) {
                                AuthorizeCallback ac = (AuthorizeCallback) callback;
                                String authid = ac.getAuthenticationID();
                                String authzid = ac.getAuthorizationID();
                                if (authid.equals(authzid)) {
                                    ac.setAuthorized(true);
                                } else {
                                    ac.setAuthorized(false);
                                }
                                if (ac.isAuthorized()) {
                                    ac.setAuthorizedID(authzid);
                                }
                            }
                            else {
                                throw new UnsupportedCallbackException(callback,"Unrecognized SASL ClientCallback");
                            }
                        }
                    }
                }
            }
        }
    }

    public boolean clientTunneledAuthenticationInProgress() {
    	if (!isSASLConfigured) {
    	    return false;
        } 
        // TODO: Rather than checking a disjunction here, should be a single member
        // variable or method in this class to determine whether the client is
        // configured to use SASL. (see also ZOOKEEPER-1455).
        try {
  	    if ((System.getProperty(Environment.JAAS_CONF_KEY) != null) ||
              ((javax.security.auth.login.Configuration.getConfiguration() != null) &&
                  (javax.security.auth.login.Configuration.getConfiguration().
                       getAppConfigurationEntry(System.
                       getProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY,"Client")) 
                           != null))) {
                // Client is configured to use a valid login Configuration, so
                // authentication is either in progress, successful, or failed.

                // 1. Authentication hasn't finished yet: we must wait for it to do so.
                if ((isComplete() == false) &&
                    (isFailed() == false)) {
                    return true;
                }

                // 2. SASL authentication has succeeded or failed..
                if (isComplete() || isFailed()) {
                    if (gotLastPacket == false) {
                        // ..but still in progress, because there is a final SASL
                        // message from server which must be received.
                    return true;
                    }
                }
            }
            // Either client is not configured to use a tunnelled authentication
            // scheme, or tunnelled authentication has completed (successfully or
            // not), and all server SASL messages have been received.
            return false;
        } catch (SecurityException e) {
            // Thrown if the caller does not have permission to retrieve the Configuration.
            // In this case, simply returning false is correct.
            if (LOG.isDebugEnabled() == true) {
                LOG.debug("Could not retrieve login configuration: " + e);
            }
            return false;
        }
    }


}
