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

package org.apache.zookeeper.server.quorum;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.common.ZKConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.apache.zookeeper.common.AtomicFileWritingIdiom;
import org.apache.zookeeper.common.AtomicFileWritingIdiom.OutputStreamStatement;
import org.apache.zookeeper.common.AtomicFileWritingIdiom.WriterStatement;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.VerifyingFileFactory;

import static org.apache.zookeeper.common.NetUtils.formatInetAddr;
import org.apache.zookeeper.metrics.impl.NullMetricsProvider;

@InterfaceAudience.Public
public class QuorumPeerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerConfig.class);
    private static final int UNSET_SERVERID = -1;
    public static final String nextDynamicConfigFileSuffix = ".dynamic.next";

    private static boolean standaloneEnabled = true;
    private static boolean reconfigEnabled = false;

    protected InetSocketAddress clientPortAddress;
    protected InetSocketAddress secureClientPortAddress;
    protected File dataDir;
    protected File dataLogDir;
    protected String dynamicConfigFileStr = null;
    protected String configFileStr = null;
    protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME;
    protected int maxClientCnxns = 60;
    /** defaults to -1 if not set explicitly */
    protected int minSessionTimeout = -1;
    /** defaults to -1 if not set explicitly */
    protected int maxSessionTimeout = -1;
    protected String metricsProviderClassName = NullMetricsProvider.class.getName();
    protected Properties metricsProviderConfiguration = new Properties();
    protected boolean localSessionsEnabled = false;
    protected boolean localSessionsUpgradingEnabled = false;

    protected int initLimit;
    protected int syncLimit;
    protected int electionAlg = 3;
    protected int electionPort = 2182;
    protected boolean quorumListenOnAllIPs = false;

    protected long serverId = UNSET_SERVERID;

    protected QuorumVerifier quorumVerifier = null, lastSeenQuorumVerifier = null;
    protected int snapRetainCount = 3;
    protected int purgeInterval = 0;
    protected boolean syncEnabled = true;

    protected LearnerType peerType = LearnerType.PARTICIPANT;

    /**
     * Configurations for the quorumpeer-to-quorumpeer sasl authentication
     */
    protected boolean quorumServerRequireSasl = false;
    protected boolean quorumLearnerRequireSasl = false;
    protected boolean quorumEnableSasl = false;
    protected String quorumServicePrincipal = QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE;
    protected String quorumLearnerLoginContext = QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    protected String quorumServerLoginContext = QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    protected int quorumCnxnThreadsSize;

    /**
     * Minimum snapshot retain count.
     * @see org.apache.zookeeper.server.PurgeTxnLog#purge(File, File, int)
     */
    private final int MIN_SNAP_RETAIN_COUNT = 3;

    @SuppressWarnings("serial")
    public static class ConfigException extends Exception {
        public ConfigException(String msg) {
            super(msg);
        }
        public ConfigException(String msg, Exception e) {
            super(msg, e);
        }
    }

    /**
     * Parse a ZooKeeper configuration file
     * @param path the patch of the configuration file
     * @throws ConfigException error processing configuration
     */
    public void parse(String path) throws ConfigException {
        LOG.info("Reading configuration from: " + path);
       
        try {
            File configFile = (new VerifyingFileFactory.Builder(LOG)
                .warnForRelativePath()
                .failForNonExistingPath()
                .build()).create(path);
                
            Properties cfg = new Properties();
            FileInputStream in = new FileInputStream(configFile);
            try {
                cfg.load(in);
                configFileStr = path;
            } finally {
                in.close();
            }
            
            parseProperties(cfg);
        } catch (IOException e) {
            throw new ConfigException("Error processing " + path, e);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Error processing " + path, e);
        }   
        
        if (dynamicConfigFileStr!=null) {
           try {           
               Properties dynamicCfg = new Properties();
               FileInputStream inConfig = new FileInputStream(dynamicConfigFileStr);
               try {
                   dynamicCfg.load(inConfig);
                   if (dynamicCfg.getProperty("version") != null) {
                       throw new ConfigException("dynamic file shouldn't have version inside");
                   }

                   String version = getVersionFromFilename(dynamicConfigFileStr);
                   // If there isn't any version associated with the filename,
                   // the default version is 0.
                   if (version != null) {
                       dynamicCfg.setProperty("version", version);
                   }
               } finally {
                   inConfig.close();
               }
               setupQuorumPeerConfig(dynamicCfg, false);

           } catch (IOException e) {
               throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
           } catch (IllegalArgumentException e) {
               throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
           }        
           File nextDynamicConfigFile = new File(configFileStr + nextDynamicConfigFileSuffix);
           if (nextDynamicConfigFile.exists()) {
               try {           
                   Properties dynamicConfigNextCfg = new Properties();
                   FileInputStream inConfigNext = new FileInputStream(nextDynamicConfigFile);       
                   try {
                       dynamicConfigNextCfg.load(inConfigNext);
                   } finally {
                       inConfigNext.close();
                   }
                   boolean isHierarchical = false;
                   for (Entry<Object, Object> entry : dynamicConfigNextCfg.entrySet()) {
                       String key = entry.getKey().toString().trim();  
                       if (key.startsWith("group") || key.startsWith("weight")) {
                           isHierarchical = true;
                           break;
                       }
                   }
                   lastSeenQuorumVerifier = createQuorumVerifier(dynamicConfigNextCfg, isHierarchical);
               } catch (IOException e) {
                   LOG.warn("NextQuorumVerifier is initiated to null");
               }
           }
        }
    }

    // This method gets the version from the end of dynamic file name.
    // For example, "zoo.cfg.dynamic.0" returns initial version "0".
    // "zoo.cfg.dynamic.1001" returns version of hex number "0x1001".
    // If a dynamic file name doesn't have any version at the end of file,
    // e.g. "zoo.cfg.dynamic", it returns null.
    public static String getVersionFromFilename(String filename) {
        int i = filename.lastIndexOf('.');
        if(i < 0 || i >= filename.length())
            return null;

        String hexVersion = filename.substring(i + 1);
        try {
            long version = Long.parseLong(hexVersion, 16);
            return Long.toHexString(version);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse config from a Properties.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public void parseProperties(Properties zkProp)
    throws IOException, ConfigException {
        int clientPort = 0;
        int secureClientPort = 0;
        String clientPortAddress = null;
        String secureClientPortAddress = null;
        VerifyingFileFactory vff = new VerifyingFileFactory.Builder(LOG).warnForRelativePath().build();
        for (Entry<Object, Object> entry : zkProp.entrySet()) {
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            if (key.equals("dataDir")) {
                dataDir = vff.create(value);
            } else if (key.equals("dataLogDir")) {
                dataLogDir = vff.create(value);
            } else if (key.equals("clientPort")) {
                clientPort = Integer.parseInt(value);
            } else if (key.equals("localSessionsEnabled")) {
                localSessionsEnabled = Boolean.parseBoolean(value);
            } else if (key.equals("localSessionsUpgradingEnabled")) {
                localSessionsUpgradingEnabled = Boolean.parseBoolean(value);
            } else if (key.equals("clientPortAddress")) {
                clientPortAddress = value.trim();
            } else if (key.equals("secureClientPort")) {
                secureClientPort = Integer.parseInt(value);
            } else if (key.equals("secureClientPortAddress")){
                secureClientPortAddress = value.trim();
            } else if (key.equals("tickTime")) {
                tickTime = Integer.parseInt(value);
            } else if (key.equals("maxClientCnxns")) {
                maxClientCnxns = Integer.parseInt(value);
            } else if (key.equals("minSessionTimeout")) {
                minSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("maxSessionTimeout")) {
                maxSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("initLimit")) {
                initLimit = Integer.parseInt(value);
            } else if (key.equals("syncLimit")) {
                syncLimit = Integer.parseInt(value);
            } else if (key.equals("electionAlg")) {
                electionAlg = Integer.parseInt(value);
                if (electionAlg != 1 && electionAlg != 2 && electionAlg != 3) {
                    throw new ConfigException("Invalid electionAlg value. Only 1, 2, 3 are supported.");
                }
            } else if (key.equals("quorumListenOnAllIPs")) {
                quorumListenOnAllIPs = Boolean.parseBoolean(value);
            } else if (key.equals("peerType")) {
                if (value.toLowerCase().equals("observer")) {
                    peerType = LearnerType.OBSERVER;
                } else if (value.toLowerCase().equals("participant")) {
                    peerType = LearnerType.PARTICIPANT;
                } else
                {
                    throw new ConfigException("Unrecognised peertype: " + value);
                }
            } else if (key.equals( "syncEnabled" )) {
                syncEnabled = Boolean.parseBoolean(value);
            } else if (key.equals("dynamicConfigFile")){
                dynamicConfigFileStr = value;
            } else if (key.equals("autopurge.snapRetainCount")) {
                snapRetainCount = Integer.parseInt(value);
            } else if (key.equals("autopurge.purgeInterval")) {
                purgeInterval = Integer.parseInt(value);
            } else if (key.equals("standaloneEnabled")) {
                if (value.toLowerCase().equals("true")) {
                    setStandaloneEnabled(true);
                } else if (value.toLowerCase().equals("false")) {
                    setStandaloneEnabled(false);
                } else {
                    throw new ConfigException("Invalid option " + value + " for standalone mode. Choose 'true' or 'false.'");
                }
            } else if (key.equals("reconfigEnabled")) {
                if (value.toLowerCase().equals("true")) {
                    setReconfigEnabled(true);
                } else if (value.toLowerCase().equals("false")) {
                    setReconfigEnabled(false);
                } else {
                    throw new ConfigException("Invalid option " + value + " for reconfigEnabled flag. Choose 'true' or 'false.'");
                }
            } else if ((key.startsWith("server.") || key.startsWith("group") || key.startsWith("weight")) && zkProp.containsKey("dynamicConfigFile")) {
                throw new ConfigException("parameter: " + key + " must be in a separate dynamic config file");
            } else if (key.equals(QuorumAuth.QUORUM_SASL_AUTH_ENABLED)) {
                quorumEnableSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED)) {
                quorumServerRequireSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED)) {
                quorumLearnerRequireSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT)) {
                quorumLearnerLoginContext = value;
            } else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT)) {
                quorumServerLoginContext = value;
            } else if (key.equals(QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL)) {
                quorumServicePrincipal = value;
            } else if (key.equals("quorum.cnxn.threads.size")) {
                quorumCnxnThreadsSize = Integer.parseInt(value);
            } else if (key.equals("metricsProvider.className")) {
                metricsProviderClassName = value;
            } else if (key.startsWith("metricsProvider.")) {
                String keyForMetricsProvider = key.substring(16);
                metricsProviderConfiguration.put(keyForMetricsProvider, value);
            } else {
                System.setProperty("zookeeper." + key, value);
            }
        }

        if (!quorumEnableSasl && quorumServerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_SASL_AUTH_ENABLED
                            + " is disabled, so cannot enable "
                            + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
        }
        if (!quorumEnableSasl && quorumLearnerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_SASL_AUTH_ENABLED
                            + " is disabled, so cannot enable "
                            + QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED);
        }
        // If quorumpeer learner is not auth enabled then self won't be able to
        // join quorum. So this condition is ensuring that the quorumpeer learner
        // is also auth enabled while enabling quorum server require sasl.
        if (!quorumLearnerRequireSasl && quorumServerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED
                            + " is disabled, so cannot enable "
                            + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
        }

        // Reset to MIN_SNAP_RETAIN_COUNT if invalid (less than 3)
        // PurgeTxnLog.purge(File, File, int) will not allow to purge less
        // than 3.
        if (snapRetainCount < MIN_SNAP_RETAIN_COUNT) {
            LOG.warn("Invalid autopurge.snapRetainCount: " + snapRetainCount
                    + ". Defaulting to " + MIN_SNAP_RETAIN_COUNT);
            snapRetainCount = MIN_SNAP_RETAIN_COUNT;
        }

        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir is not set");
        }
        if (dataLogDir == null) {
            dataLogDir = dataDir;
        }

        if (clientPort == 0) {
            LOG.info("clientPort is not set");
            if (clientPortAddress != null) {
                throw new IllegalArgumentException("clientPortAddress is set but clientPort is not set");
            }
        } else if (clientPortAddress != null) {
            this.clientPortAddress = new InetSocketAddress(
                    InetAddress.getByName(clientPortAddress), clientPort);
            LOG.info("clientPortAddress is {}", formatInetAddr(this.clientPortAddress));
        } else {
            this.clientPortAddress = new InetSocketAddress(clientPort);
            LOG.info("clientPortAddress is {}", formatInetAddr(this.clientPortAddress));
        }

        if (secureClientPort == 0) {
            LOG.info("secureClientPort is not set");
            if (secureClientPortAddress != null) {
                throw new IllegalArgumentException("secureClientPortAddress is set but secureClientPort is not set");
            }
        } else if (secureClientPortAddress != null) {
            this.secureClientPortAddress = new InetSocketAddress(
                    InetAddress.getByName(secureClientPortAddress), secureClientPort);
            LOG.info("secureClientPortAddress is {}", formatInetAddr(this.secureClientPortAddress));
        } else {
            this.secureClientPortAddress = new InetSocketAddress(secureClientPort);
            LOG.info("secureClientPortAddress is {}", formatInetAddr(this.secureClientPortAddress));
        }
        if (this.secureClientPortAddress != null) {
            configureSSLAuth();
        }

        if (tickTime == 0) {
            throw new IllegalArgumentException("tickTime is not set");
        }

        minSessionTimeout = minSessionTimeout == -1 ? tickTime * 2 : minSessionTimeout;
        maxSessionTimeout = maxSessionTimeout == -1 ? tickTime * 20 : maxSessionTimeout;

        if (minSessionTimeout > maxSessionTimeout) {
            throw new IllegalArgumentException(
                    "minSessionTimeout must not be larger than maxSessionTimeout");
        }

        LOG.info("metricsProvider.className is {}", metricsProviderClassName);
        try {
            Class.forName(metricsProviderClassName, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException error) {
            throw new IllegalArgumentException("metrics provider class was not found", error);
        }

        // backward compatibility - dynamic configuration in the same file as
        // static configuration params see writeDynamicConfig()
        if (dynamicConfigFileStr == null) {
            setupQuorumPeerConfig(zkProp, true);
            if (isDistributed() && isReconfigEnabled()) {
                // we don't backup static config for standalone mode.
                // we also don't backup if reconfig feature is disabled.
                backupOldConfig();
            }
        }
    }

    /**
     * Configure SSL authentication only if it is not configured.
     * 
     * @throws ConfigException
     *             If authentication scheme is configured but authentication
     *             provider is not configured.
     */
    private void configureSSLAuth() throws ConfigException {
        String sslAuthProp = "zookeeper.authProvider." + System.getProperty(ZKConfig.SSL_AUTHPROVIDER, "x509");
        if (System.getProperty(sslAuthProp) == null) {
            if ("zookeeper.authProvider.x509".equals(sslAuthProp)) {
                System.setProperty("zookeeper.authProvider.x509",
                        "org.apache.zookeeper.server.auth.X509AuthenticationProvider");
            } else {
                throw new ConfigException("No auth provider configured for the SSL authentication scheme '"
                        + System.getProperty(ZKConfig.SSL_AUTHPROVIDER) + "'.");
            }
        }
    }

    /**
     * Backward compatibility -- It would backup static config file on bootup
     * if users write dynamic configuration in "zoo.cfg".
     */
    private void backupOldConfig() throws IOException {
        new AtomicFileWritingIdiom(new File(configFileStr + ".bak"), new OutputStreamStatement() {
            @Override
            public void write(OutputStream output) throws IOException {
                InputStream input = null;
                try {
                    input = new FileInputStream(new File(configFileStr));
                    byte[] buf = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = input.read(buf)) > 0) {
                        output.write(buf, 0, bytesRead);
                    }
                } finally {
                    if( input != null) {
                        input.close();
                    }
                }
            }
        });
    }

    /**
     * Writes dynamic configuration file
     */
    public static void writeDynamicConfig(final String dynamicConfigFilename,
                                          final QuorumVerifier qv,
                                          final boolean needKeepVersion)
            throws IOException {

        new AtomicFileWritingIdiom(new File(dynamicConfigFilename), new WriterStatement() {
            @Override
            public void write(Writer out) throws IOException {
                Properties cfg = new Properties();
                cfg.load( new StringReader(
                        qv.toString()));

                List<String> servers = new ArrayList<String>();
                for (Entry<Object, Object> entry : cfg.entrySet()) {
                    String key = entry.getKey().toString().trim();
                    if ( !needKeepVersion && key.startsWith("version"))
                        continue;

                    String value = entry.getValue().toString().trim();
                    servers.add(key
                            .concat("=")
                            .concat(value));
                }

                Collections.sort(servers);
                out.write(StringUtils.joinStrings(servers, "\n"));
            }
        });
    }

    /**
     * Edit static config file.
     * If there are quorum information in static file, e.g. "server.X", "group",
     * it will remove them.
     * If it needs to erase client port information left by the old config,
     * "eraseClientPortAddress" should be set true.
     * It should also updates dynamic file pointer on reconfig.
     */
    public static void editStaticConfig(final String configFileStr,
                                        final String dynamicFileStr,
                                        final boolean eraseClientPortAddress)
            throws IOException {
        // Some tests may not have a static config file.
        if (configFileStr == null)
            return;

        File configFile = (new VerifyingFileFactory.Builder(LOG)
                .warnForRelativePath()
                .failForNonExistingPath()
                .build()).create(configFileStr);

        final File dynamicFile = (new VerifyingFileFactory.Builder(LOG)
                .warnForRelativePath()
                .failForNonExistingPath()
                .build()).create(dynamicFileStr);
        
        final Properties cfg = new Properties();
        FileInputStream in = new FileInputStream(configFile);
        try {
            cfg.load(in);
        } finally {
            in.close();
        }

        new AtomicFileWritingIdiom(new File(configFileStr), new WriterStatement() {
            @Override
            public void write(Writer out) throws IOException {
                for (Entry<Object, Object> entry : cfg.entrySet()) {
                    String key = entry.getKey().toString().trim();

                    if (key.startsWith("server.")
                        || key.startsWith("group")
                        || key.startsWith("weight")
                        || key.startsWith("dynamicConfigFile")
                        || key.startsWith("peerType")
                        || (eraseClientPortAddress
                            && (key.startsWith("clientPort")
                                || key.startsWith("clientPortAddress")))) {
                        // not writing them back to static file
                        continue;
                    }

                    String value = entry.getValue().toString().trim();
                    out.write(key.concat("=").concat(value).concat("\n"));
                }

                // updates the dynamic file pointer
                String dynamicConfigFilePath = PathUtils.normalizeFileSystemPath(dynamicFile.getCanonicalPath());
                out.write("dynamicConfigFile="
                         .concat(dynamicConfigFilePath)
                         .concat("\n"));
            }
        });
    }


    public static void deleteFile(String filename){
       if (filename == null) return;
       File f = new File(filename);
       if (f.exists()) {
           try{ 
               f.delete();
           } catch (Exception e) {
               LOG.warn("deleting " + filename + " failed");
           }
       }                   
    }
    
    
    private static QuorumVerifier createQuorumVerifier(Properties dynamicConfigProp, boolean isHierarchical) throws ConfigException{
       if(isHierarchical){
            return new QuorumHierarchical(dynamicConfigProp);
        } else {
           /*
             * The default QuorumVerifier is QuorumMaj
             */        
            //LOG.info("Defaulting to majority quorums");
            return new QuorumMaj(dynamicConfigProp);            
        }          
    }

    void setupQuorumPeerConfig(Properties prop, boolean configBackwardCompatibilityMode)
            throws IOException, ConfigException {
        quorumVerifier = parseDynamicConfig(prop, electionAlg, true, configBackwardCompatibilityMode);
        setupMyId();
        setupClientPort();
        setupPeerType();
        checkValidity();
    }

    /**
     * Parse dynamic configuration file and return
     * quorumVerifier for new configuration.
     * @param dynamicConfigProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public static QuorumVerifier parseDynamicConfig(Properties dynamicConfigProp, int eAlg, boolean warnings,
	   boolean configBackwardCompatibilityMode) throws IOException, ConfigException {
       boolean isHierarchical = false;
        for (Entry<Object, Object> entry : dynamicConfigProp.entrySet()) {
            String key = entry.getKey().toString().trim();                    
            if (key.startsWith("group") || key.startsWith("weight")) {
               isHierarchical = true;
            } else if (!configBackwardCompatibilityMode && !key.startsWith("server.") && !key.equals("version")){ 
               LOG.info(dynamicConfigProp.toString());
               throw new ConfigException("Unrecognised parameter: " + key);                
            }
        }
        
        QuorumVerifier qv = createQuorumVerifier(dynamicConfigProp, isHierarchical);
               
        int numParticipators = qv.getVotingMembers().size();
        int numObservers = qv.getObservingMembers().size();
        if (numParticipators == 0) {
            if (!standaloneEnabled) {
                throw new IllegalArgumentException("standaloneEnabled = false then " +
                        "number of participants should be >0");
            }
            if (numObservers > 0) {
                throw new IllegalArgumentException("Observers w/o participants is an invalid configuration");
            }
        } else if (numParticipators == 1 && standaloneEnabled) {
            // HBase currently adds a single server line to the config, for
            // b/w compatibility reasons we need to keep this here. If standaloneEnabled
            // is true, the QuorumPeerMain script will create a standalone server instead
            // of a quorum configuration
            LOG.error("Invalid configuration, only one server specified (ignoring)");
            if (numObservers > 0) {
                throw new IllegalArgumentException("Observers w/o quorum is an invalid configuration");
            }
        } else {
            if (warnings) {
                if (numParticipators <= 2) {
                    LOG.warn("No server failure will be tolerated. " +
                        "You need at least 3 servers.");
                } else if (numParticipators % 2 == 0) {
                    LOG.warn("Non-optimial configuration, consider an odd number of servers.");
                }
            }

            for (QuorumServer s : qv.getVotingMembers().values()) {
                if (s.electionAddr == null)
                    throw new IllegalArgumentException(
                            "Missing election port for server: " + s.id);
            }
        }
        return qv;
    }

    private void setupMyId() throws IOException {
        File myIdFile = new File(dataDir, "myid");
        // standalone server doesn't need myid file.
        if (!myIdFile.isFile()) {
            return;
        }
        BufferedReader br = new BufferedReader(new FileReader(myIdFile));
        String myIdString;
        try {
            myIdString = br.readLine();
        } finally {
            br.close();
        }
        try {
            serverId = Long.parseLong(myIdString);
            MDC.put("myid", myIdString);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("serverid " + myIdString
                    + " is not a number");
        }
    }

    private void setupClientPort() throws ConfigException {
        if (serverId == UNSET_SERVERID) {
            return;
        }
        QuorumServer qs = quorumVerifier.getAllMembers().get(serverId);
        if (clientPortAddress != null && qs != null && qs.clientAddr != null) {
            if ((!clientPortAddress.getAddress().isAnyLocalAddress()
                    && !clientPortAddress.equals(qs.clientAddr)) ||
                    (clientPortAddress.getAddress().isAnyLocalAddress()
                            && clientPortAddress.getPort() != qs.clientAddr.getPort()))
                throw new ConfigException("client address for this server (id = " + serverId +
                        ") in static config file is " + clientPortAddress +
                        " is different from client address found in dynamic file: " + qs.clientAddr);
        }
        if (qs != null && qs.clientAddr != null) clientPortAddress = qs.clientAddr;
        if (qs != null && qs.clientAddr == null) qs.clientAddr = clientPortAddress;
    }

    private void setupPeerType() {
        // Warn about inconsistent peer type
        LearnerType roleByServersList = quorumVerifier.getObservingMembers().containsKey(serverId) ? LearnerType.OBSERVER
                : LearnerType.PARTICIPANT;
        if (roleByServersList != peerType) {
            LOG.warn("Peer type from servers list (" + roleByServersList
                    + ") doesn't match peerType (" + peerType
                    + "). Defaulting to servers list.");

            peerType = roleByServersList;
        }
    }

    public void checkValidity() throws IOException, ConfigException{
        if (isDistributed()) {
            if (initLimit == 0) {
                throw new IllegalArgumentException("initLimit is not set");
            }
            if (syncLimit == 0) {
                throw new IllegalArgumentException("syncLimit is not set");
            }
            if (serverId == UNSET_SERVERID) {
                throw new IllegalArgumentException("myid file is missing");
            }
       }
    }

    public InetSocketAddress getClientPortAddress() { return clientPortAddress; }
    public InetSocketAddress getSecureClientPortAddress() { return secureClientPortAddress; }
    public File getDataDir() { return dataDir; }
    public File getDataLogDir() { return dataLogDir; }
    public int getTickTime() { return tickTime; }
    public int getMaxClientCnxns() { return maxClientCnxns; }
    public int getMinSessionTimeout() { return minSessionTimeout; }
    public int getMaxSessionTimeout() { return maxSessionTimeout; }
    public String getMetricsProviderClassName() { return metricsProviderClassName; }
    public Properties getMetricsProviderConfiguration() { return metricsProviderConfiguration; }
    public boolean areLocalSessionsEnabled() { return localSessionsEnabled; }
    public boolean isLocalSessionsUpgradingEnabled() {
        return localSessionsUpgradingEnabled;
    }

    public int getInitLimit() { return initLimit; }
    public int getSyncLimit() { return syncLimit; }
    public int getElectionAlg() { return electionAlg; }
    public int getElectionPort() { return electionPort; }

    public int getSnapRetainCount() {
        return snapRetainCount;
    }

    public int getPurgeInterval() {
        return purgeInterval;
    }
    
    public boolean getSyncEnabled() {
        return syncEnabled;
    }

    public QuorumVerifier getQuorumVerifier() {
        return quorumVerifier;
    }
    
    public QuorumVerifier getLastSeenQuorumVerifier() {   
        return lastSeenQuorumVerifier;
    }

    public Map<Long,QuorumServer> getServers() {
        // returns all configuration servers -- participants and observers
        return Collections.unmodifiableMap(quorumVerifier.getAllMembers());
    }

    public long getServerId() { return serverId; }

    public boolean isDistributed() {
        return quorumVerifier!=null && (!standaloneEnabled || quorumVerifier.getVotingMembers().size() > 1);
    }

    public LearnerType getPeerType() {
        return peerType;
    }

    public String getConfigFilename(){
        return configFileStr;
    }
    
    public Boolean getQuorumListenOnAllIPs() {
        return quorumListenOnAllIPs;
    }
 
    public static boolean isStandaloneEnabled() {
	return standaloneEnabled;
    }
    
    public static void setStandaloneEnabled(boolean enabled) {
        standaloneEnabled = enabled;
    }

    public static boolean isReconfigEnabled() { return reconfigEnabled; }

    public static void setReconfigEnabled(boolean enabled) {
        reconfigEnabled = enabled;
    }

}
