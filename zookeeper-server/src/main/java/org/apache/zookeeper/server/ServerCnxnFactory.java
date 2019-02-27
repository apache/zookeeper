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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;

import org.apache.zookeeper.Environment;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.auth.SaslServerCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public abstract class ServerCnxnFactory {

    public static final String ZOOKEEPER_SERVER_CNXN_FACTORY = "zookeeper.serverCnxnFactory";
    
    private static final Logger LOG = LoggerFactory.getLogger(ServerCnxnFactory.class);

    // Tells whether SSL is enabled on this ServerCnxnFactory
    protected boolean secure;

    /**
     * The buffer will cause the connection to be close when we do a send.
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    public abstract int getLocalPort();
    
    public abstract Iterable<ServerCnxn> getConnections();

    public int getNumAliveConnections() {
        return cnxns.size();
    }

    public ZooKeeperServer getZooKeeperServer() {
        return zkServer;
    }

    /**
     * @return true if the cnxn that contains the sessionId exists in this ServerCnxnFactory
     *         and it's closed. Otherwise false.
     */
    public abstract boolean closeSession(long sessionId);

    public void configure(InetSocketAddress addr, int maxcc) throws IOException {
        configure(addr, maxcc, false);
    }

    public abstract void configure(InetSocketAddress addr, int maxcc, boolean secure)
            throws IOException;

    public abstract void reconfigure(InetSocketAddress addr);

    protected SaslServerCallbackHandler saslServerCallbackHandler;
    public Login login;

    /** Maximum number of connections allowed from particular host (ip) */
    public abstract int getMaxClientCnxnsPerHost();

    /** Maximum number of connections allowed from particular host (ip) */
    public abstract void setMaxClientCnxnsPerHost(int max);

    public boolean isSecure() {
        return secure;
    }

    public void startup(ZooKeeperServer zkServer) throws IOException, InterruptedException {
        startup(zkServer, true);
    }

    // This method is to maintain compatiblity of startup(zks) and enable sharing of zks
    // when we add secureCnxnFactory.
    public abstract void startup(ZooKeeperServer zkServer, boolean startServer)
            throws IOException, InterruptedException;

    public abstract void join() throws InterruptedException;

    public abstract void shutdown();

    public abstract void start();

    protected ZooKeeperServer zkServer;
    final public void setZooKeeperServer(ZooKeeperServer zks) {
        this.zkServer = zks;
        if (zks != null) {
            if (secure) {
                zks.setSecureServerCnxnFactory(this);
            } else {
                zks.setServerCnxnFactory(this);
            }
        }
    }

    public abstract void closeAll();
    
    static public ServerCnxnFactory createFactory() throws IOException {
        String serverCnxnFactoryName =
            System.getProperty(ZOOKEEPER_SERVER_CNXN_FACTORY);
        if (serverCnxnFactoryName == null) {
            serverCnxnFactoryName = NIOServerCnxnFactory.class.getName();
        }
        try {
            ServerCnxnFactory serverCnxnFactory = (ServerCnxnFactory) Class.forName(serverCnxnFactoryName)
                    .getDeclaredConstructor().newInstance();
            LOG.info("Using {} as server connection factory", serverCnxnFactoryName);
            return serverCnxnFactory;
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate "
                    + serverCnxnFactoryName);
            ioe.initCause(e);
            throw ioe;
        }
    }
    
    static public ServerCnxnFactory createFactory(int clientPort,
            int maxClientCnxns) throws IOException
    {
        return createFactory(new InetSocketAddress(clientPort), maxClientCnxns);
    }

    static public ServerCnxnFactory createFactory(InetSocketAddress addr,
            int maxClientCnxns) throws IOException
    {
        ServerCnxnFactory factory = createFactory();
        factory.configure(addr, maxClientCnxns);
        return factory;
    }

    public abstract InetSocketAddress getLocalAddress();

    public abstract void resetAllConnectionStats();

    public abstract Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief);

    private final ConcurrentHashMap<ServerCnxn, ConnectionBean> connectionBeans =
        new ConcurrentHashMap<ServerCnxn, ConnectionBean>();

    // Connection set is relied on heavily by four letter commands
    // Construct a ConcurrentHashSet using a ConcurrentHashMap
    protected final Set<ServerCnxn> cnxns = Collections.newSetFromMap(
        new ConcurrentHashMap<ServerCnxn, Boolean>());
    public void unregisterConnection(ServerCnxn serverCnxn) {
        ConnectionBean jmxConnectionBean = connectionBeans.remove(serverCnxn);
        if (jmxConnectionBean != null){
            MBeanRegistry.getInstance().unregister(jmxConnectionBean);
        }
    }
    
    public void registerConnection(ServerCnxn serverCnxn) {
        if (zkServer != null) {
            ConnectionBean jmxConnectionBean = new ConnectionBean(serverCnxn, zkServer);
            try {
                MBeanRegistry.getInstance().register(jmxConnectionBean, zkServer.jmxServerBean);
                connectionBeans.put(serverCnxn, jmxConnectionBean);
            } catch (JMException e) {
                LOG.warn("Could not register connection", e);
            }
        }

    }

    /**
     * Initialize the server SASL if specified.
     *
     * If the user has specified a "ZooKeeperServer.LOGIN_CONTEXT_NAME_KEY"
     * or a jaas.conf using "java.security.auth.login.config"
     * the authentication is required and an exception is raised.
     * Otherwise no authentication is configured and no exception is raised.
     *
     * @throws IOException if jaas.conf is missing or there's an error in it.
     */
    protected void configureSaslLogin() throws IOException {
        String serverSection = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY,
                                                  ZooKeeperSaslServer.DEFAULT_LOGIN_CONTEXT_NAME);

        // Note that 'Configuration' here refers to javax.security.auth.login.Configuration.
        AppConfigurationEntry entries[] = null;
        SecurityException securityException = null;
        try {
            entries = Configuration.getConfiguration().getAppConfigurationEntry(serverSection);
        } catch (SecurityException e) {
            // handle below: might be harmless if the user doesn't intend to use JAAS authentication.
            securityException = e;
        }

        // No entries in jaas.conf
        // If there's a configuration exception fetching the jaas section and
        // the user has required sasl by specifying a LOGIN_CONTEXT_NAME_KEY or a jaas file
        // we throw an exception otherwise we continue without authentication.
        if (entries == null) {
            String jaasFile = System.getProperty(Environment.JAAS_CONF_KEY);
            String loginContextName = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY);
            if (securityException != null && (loginContextName != null || jaasFile != null)) {
                String errorMessage = "No JAAS configuration section named '" + serverSection +  "' was found";
                if (jaasFile != null) {
                    errorMessage += " in '" + jaasFile + "'.";
                }
                if (loginContextName != null) {
                    errorMessage += " But " + ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY + " was set.";
                }
                LOG.error(errorMessage);
                throw new IOException(errorMessage);
            }
            return;
        }

        // jaas.conf entry available
        try {
            saslServerCallbackHandler = new SaslServerCallbackHandler(Configuration.getConfiguration());
            login = new Login(serverSection, saslServerCallbackHandler, new ZKConfig() );
            login.startThreadIfNeeded();
        } catch (LoginException e) {
            throw new IOException("Could not configure server because SASL configuration did not allow the "
              + " ZooKeeper server to authenticate itself properly: " + e);
        }
    }
}
