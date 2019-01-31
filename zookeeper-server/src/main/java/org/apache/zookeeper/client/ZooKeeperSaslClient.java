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
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.SaslClientCallbackHandler;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.GetSASLRequest;
import org.apache.zookeeper.proto.SetSASLResponse;

import org.apache.zookeeper.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages SASL authentication for the client. It
 * allows ClientCnxn to authenticate using SASL with a ZooKeeper server.
 */
public class ZooKeeperSaslClient {
    /**
     * @deprecated Use {@link ZKClientConfig#LOGIN_CONTEXT_NAME_KEY}
     *             instead.
     */
    @Deprecated
    public static final String LOGIN_CONTEXT_NAME_KEY = "zookeeper.sasl.clientconfig";
    /**
     * @deprecated Use {@link ZKClientConfig#ENABLE_CLIENT_SASL_KEY}
     *             instead.
     */
    @Deprecated
    public static final String ENABLE_CLIENT_SASL_KEY = "zookeeper.sasl.client";
    /**
     * @deprecated Use {@link ZKClientConfig#ENABLE_CLIENT_SASL_DEFAULT}
     *             instead.
     */
    @Deprecated
    public static final String ENABLE_CLIENT_SASL_DEFAULT = "true";
    private volatile boolean initializedLogin = false; 

    /**
     * Returns true if the SASL client is enabled. By default, the client
     * is enabled but can be disabled by setting the system property
     * <code>zookeeper.sasl.client</code> to <code>false</code>. See
     * ZOOKEEPER-1657 for more information.
     *
     * @return true if the SASL client is enabled.
     * @deprecated Use {@link ZKClientConfig#isSaslClientEnabled} instead
     */
    @Deprecated
    public static boolean isEnabled() {
        return Boolean.valueOf(System.getProperty(
                ZKClientConfig.ENABLE_CLIENT_SASL_KEY,
                ZKClientConfig.ENABLE_CLIENT_SASL_DEFAULT));
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperSaslClient.class);
    private Login login = null;
    private SaslClient saslClient;
    private boolean isSASLConfigured = true;
    private final ZKClientConfig clientConfig;

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

    public ZooKeeperSaslClient(final String serverPrincipal, ZKClientConfig clientConfig) throws LoginException {
        /**
         * ZOOKEEPER-1373: allow system property to specify the JAAS
         * configuration section that the zookeeper client should use.
         * Default to "Client".
         */
        String clientSection = clientConfig.getProperty(
                ZKClientConfig.LOGIN_CONTEXT_NAME_KEY,
                ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
        this.clientConfig = clientConfig;
        // Note that 'Configuration' here refers to javax.security.auth.login.Configuration.
        AppConfigurationEntry entries[] = null;
        RuntimeException runtimeException = null;
        try {
            entries = Configuration.getConfiguration()
                    .getAppConfigurationEntry(clientSection);
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
            String explicitClientSection = clientConfig
                    .getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY);
            if (explicitClientSection != null) {
                // If the user explicitly overrides the default Login Context, they probably expected SASL to
                // succeed. But if we got here, SASL failed.
                if (runtimeException != null) {
                    throw new LoginException(
                            "Zookeeper client cannot authenticate using the "
                                    + explicitClientSection
                                    + " section of the supplied JAAS configuration: '"
                                    + clientConfig.getJaasConfKey() + "' because of a "
                                    + "RuntimeException: " + runtimeException);
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
            if (clientConfig.getJaasConfKey() != null) {
                // Again, the user explicitly set something SASL-related, so
                // they probably expected SASL to succeed.
                if (runtimeException != null) {
                    throw new LoginException(
                            "Zookeeper client cannot authenticate using the '"
                                    + clientConfig.getProperty(
                                            ZKClientConfig.LOGIN_CONTEXT_NAME_KEY,
                                            ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT)
                                    + "' section of the supplied JAAS configuration: '"
                                    + clientConfig.getJaasConfKey() + "' because of a "
                                    + "RuntimeException: " + runtimeException);
                } else {
                    throw new LoginException(
                            "No JAAS configuration section named '"
                                    + clientConfig.getProperty(
                                            ZKClientConfig.LOGIN_CONTEXT_NAME_KEY,
                                            ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT)
                                    + "' was found in specified JAAS configuration file: '"
                                    + clientConfig.getJaasConfKey() + "'.");
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

    private SaslClient createSaslClient(final String servicePrincipal, final String loginContext)
            throws LoginException {
        try {
            if (!initializedLogin) {
                synchronized (this) {
                    if (login == null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("JAAS loginContext is: " + loginContext);
                        }
                        // note that the login object is static: it's shared amongst all zookeeper-related connections.
                        // in order to ensure the login is initialized only once, it must be synchronized the code snippet.
                        login = new Login(loginContext, new SaslClientCallbackHandler(null, "Client"), clientConfig);
                        login.startThreadIfNeeded();
                        initializedLogin = true;
                    }
                }
            }
            return SecurityUtils.createSaslClient(login.getSubject(),
                    servicePrincipal, "zookeeper", "zk-sasl-md5", LOG, "Client");
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
                        this.getLoginContext() + "' with exception: {}", e);
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
            cnxn.saslCompleted();
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
                    if (e.toString().contains(UNKNOWN_SERVER_ERROR_TEXT)) {
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

    public boolean clientTunneledAuthenticationInProgress() {
    	if (!isSASLConfigured) {
    	    return false;
        } 
        // TODO: Rather than checking a disjunction here, should be a single member
        // variable or method in this class to determine whether the client is
        // configured to use SASL. (see also ZOOKEEPER-1455).
        try {
            if ((clientConfig.getJaasConfKey() != null)
                    || ((Configuration.getConfiguration() != null) && (Configuration.getConfiguration()
                            .getAppConfigurationEntry(clientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY,
                                    ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT)) != null))) {
                // Client is configured to use a valid login Configuration, so
                // authentication is either in progress, successful, or failed.

                // 1. Authentication hasn't finished yet: we must wait for it to do so.
                if (!isComplete() && !isFailed()) {
                    return true;
                }

                // 2. SASL authentication has succeeded or failed..
                if (!gotLastPacket) {
                    // ..but still in progress, because there is a final SASL
                    // message from server which must be received.
                    return true;
                }
            }
            // Either client is not configured to use a tunnelled authentication
            // scheme, or tunnelled authentication has completed (successfully or
            // not), and all server SASL messages have been received.
            return false;
        } catch (SecurityException e) {
            // Thrown if the caller does not have permission to retrieve the Configuration.
            // In this case, simply returning false is correct.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not retrieve login configuration: " + e);
            }
            return false;
        }
    }

    /**
     * close login thread if running
     */
    public void shutdown() {
        if (null != login) {
            login.shutdown();
        }
    }
}
