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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;


import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.VerifyingFileFactory;


public class QuorumPeerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerConfig.class);

    protected InetSocketAddress clientPortAddress;
    protected File dataDir;
    protected File dataLogDir;
    protected boolean configBackwardCompatibilityMode = false;
    protected String dynamicConfigFileStr = null;
    protected String configFileStr = null;
    protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME;
    protected int maxClientCnxns = 60;
    /** defaults to -1 if not set explicitly */
    protected int minSessionTimeout = -1;
    /** defaults to -1 if not set explicitly */
    protected int maxSessionTimeout = -1;

    protected int initLimit;
    protected int syncLimit;
    protected int electionAlg = 3;
    protected int electionPort = 2182;

    protected long serverId;

    protected QuorumVerifier quorumVerifier = null, lastSeenQuorumVerifier = null;
    protected int snapRetainCount = 3;
    protected int purgeInterval = 0;

    protected LearnerType peerType = LearnerType.PARTICIPANT;

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
            } finally {
                in.close();
            }
            
            parseProperties(cfg);
            
            // backward compatibility - dynamic configuration in the same file as static configuration params
            // see writeDynamicConfig() - we change the config file to new format if reconfig happens
            if (dynamicConfigFileStr == null) {
                configBackwardCompatibilityMode = true;
                configFileStr = path;                
                parseDynamicConfig(cfg, electionAlg, true);
                checkValidity();                
            }

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
               } finally {
                   inConfig.close();
               }
               parseDynamicConfig(dynamicCfg, electionAlg, true);
               checkValidity();
           
           } catch (IOException e) {
               throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
           } catch (IllegalArgumentException e) {
               throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
           }        
           File nextDynamicConfigFile = new File(dynamicConfigFileStr + ".next");
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

    /**
     * Parse config from a Properties.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public void parseProperties(Properties zkProp)
    throws IOException, ConfigException {
        int clientPort = 0;
        String clientPortAddress = null;
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
            } else if (key.equals("clientPortAddress")) {
                clientPortAddress = value.trim();
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
            } else if (key.equals("peerType")) {
                if (value.toLowerCase().equals("observer")) {
                    peerType = LearnerType.OBSERVER;
                } else if (value.toLowerCase().equals("participant")) {
                    peerType = LearnerType.PARTICIPANT;
                } else
                {
                    throw new ConfigException("Unrecognised peertype: " + value);
                }
            } else if (key.equals("dynamicConfigFile")){
               dynamicConfigFileStr = value;
            } else if (key.equals("autopurge.snapRetainCount")) {
                snapRetainCount = Integer.parseInt(value);
            } else if (key.equals("autopurge.purgeInterval")) {
                purgeInterval = Integer.parseInt(value);
            } else if ((key.startsWith("server.") || key.startsWith("group") || key.startsWith("weight")) && zkProp.containsKey("dynamicConfigFile")){                
               throw new ConfigException("parameter: " + key + " must be in a separate dynamic config file");
            } else {
                System.setProperty("zookeeper." + key, value);
            }
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
        } else {
            if (!dataLogDir.isDirectory()) {
                throw new IllegalArgumentException("dataLogDir " + dataLogDir
                        + " is missing.");
            }
        }
        if (clientPortAddress != null) {
           if (clientPort == 0) {
               throw new IllegalArgumentException("clientPortAddress is set but clientPort is not set");
        }
             this.clientPortAddress = new InetSocketAddress(
                      InetAddress.getByName(clientPortAddress), clientPort);
        } else if (clientPort!=0){
             this.clientPortAddress = new InetSocketAddress(clientPort);
        }    
        if (tickTime == 0) {
            throw new IllegalArgumentException("tickTime is not set");
        }
        if (minSessionTimeout > maxSessionTimeout) {
            throw new IllegalArgumentException(
                    "minSessionTimeout must not be larger than maxSessionTimeout");
        }          
    }
    
    /**
     * Writes dynamic configuration file, updates static config file if needed. 
     * @param dynamicConfigFilename
     * @param configFileStr
     * @param configBackwardCompatibilityMode
     * @param qv
     */
    public static void writeDynamicConfig(String dynamicConfigFilename, String configFileStr, 
            boolean configBackwardCompatibilityMode, QuorumVerifier qv) throws IOException {                             
        FileOutputStream outConfig = null;
       try {
           byte b[] = qv.toString().getBytes();                                            
           if (configBackwardCompatibilityMode) {
               dynamicConfigFilename = configFileStr + ".dynamic";
           }
           String tmpFilename = dynamicConfigFilename + ".tmp";
           outConfig = new FileOutputStream(tmpFilename);
           
           outConfig.write(b);
           outConfig.close();
           File curFile = new File(dynamicConfigFilename);
           File tmpFile = new File(tmpFilename);
           if (!tmpFile.renameTo(curFile)) {
               throw new IOException("renaming " + tmpFile.toString() + " to " + curFile.toString() + " failed!");
           }
       } finally{
           if (outConfig!=null) { 
               outConfig.close();
           }
       }
       // the following is for users who run without a dynamic config file (old config file)
       // if the configuration changes (reconfiguration executes), we create a dynamic config
       // file, remove all the dynamic definitions from the config file and add a pointer
       // to the config file. The dynamic config file's name will be the same as the config file's
       // with ".dynamic" appended to it
       
        if (configBackwardCompatibilityMode) {
           BufferedWriter out = null;
               try {
                   File configFile = (new VerifyingFileFactory.Builder(LOG)
                       .warnForRelativePath()
                       .failForNonExistingPath()
                       .build()).create(configFileStr);
                       
                   Properties cfg = new Properties();
                   FileInputStream in = new FileInputStream(configFile);
                   try {
                       cfg.load(in);
                   } finally {
                       in.close();
                   }
                   String tmpFilename = configFileStr + ".tmp";                    
                   FileWriter fstream = new FileWriter(tmpFilename);
                   out = new BufferedWriter(fstream);                 
                   
                   for (Entry<Object, Object> entry : cfg.entrySet()) {
                       String key = entry.getKey().toString().trim();
                       String value = entry.getValue().toString().trim();    
                       if (!key.startsWith("server.") && !key.startsWith("group") 
                               && !key.startsWith("weight") && !key.equals("clientPort") && !key.equals("clientPortAddress")){
                           out.write(key.concat("=").concat(value).concat("\n"));
                       }
                   }                      
                   out.write("dynamicConfigFile=".concat(dynamicConfigFilename).concat("\n"));
                   out.close();
                   File tmpFile = new File(tmpFilename);
                   if (!tmpFile.renameTo(configFile)) {
                       throw new IOException("renaming " + tmpFile.toString() + " to " + configFile.toString() + " failed!");
                   }
               } finally{
                   if (out!=null) {
                           out.close();
                   }
               }
           }
   } 
    public static void deleteFile(String filename){        
       File f = new File(filename);
       if (f.exists()) {
           try{ 
               f.delete();
           } catch (Exception e) {
               LOG.warn("deleting " + filename + " failed");
           }
       }                   
    }
    
    
    private QuorumVerifier createQuorumVerifier(Properties dynamicConfigProp, boolean isHierarchical) throws ConfigException{
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
    
    /**
     * Parse dynamic configuration file.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public void parseDynamicConfig(Properties dynamicConfigProp, int eAlg, boolean warnings)
    throws IOException, ConfigException {
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
        
        quorumVerifier = createQuorumVerifier(dynamicConfigProp, isHierarchical);                      
               
        int numParticipators = quorumVerifier.getVotingMembers().size();
        int numObservers = quorumVerifier.getObservingMembers().size();        
        if (numParticipators == 0) {
            if (numObservers > 0) {
                throw new IllegalArgumentException("Observers w/o participants is an invalid configuration");
            }
            // Not a quorum configuration so return immediately - not an error
            // case (for b/w compatibility), server will default to standalone
            // mode.
            return;
        } else if (numParticipators == 1) {            
            if (numObservers > 0) {
                throw new IllegalArgumentException("Observers w/o quorum is an invalid configuration");
            }

            // HBase currently adds a single server line to the config, for
            // b/w compatibility reasons we need to keep this here.
            LOG.error("Invalid configuration, only one server specified (ignoring)");
            //servers.clear();
        } else if (numParticipators > 1) {
           if (warnings) {
                if (numParticipators == 2) {
                    LOG.warn("No server failure will be tolerated. " +
                        "You need at least 3 servers.");
                } else if (numParticipators % 2 == 0) {
                    LOG.warn("Non-optimial configuration, consider an odd number of servers.");
                }
           }
            /*
             * If using FLE, then every server requires a separate election
             * port.
             */            
           if (eAlg != 0) {
               for (QuorumServer s : quorumVerifier.getVotingMembers().values()) {
                   if (s.electionAddr == null)
                       throw new IllegalArgumentException(
                               "Missing election port for server: " + s.id);
               }
           }   
        }
    }
    

    public void checkValidity() throws IOException, ConfigException{

       if (quorumVerifier.getVotingMembers().size() > 1) {
           if (initLimit == 0) {
               throw new IllegalArgumentException("initLimit is not set");
           }
           if (syncLimit == 0) {
               throw new IllegalArgumentException("syncLimit is not set");
           }
            
                                     
            File myIdFile = new File(dataDir, "myid");
            if (!myIdFile.exists()) {
                throw new IllegalArgumentException(myIdFile.toString()
                        + " file is missing");
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

            QuorumServer qs = quorumVerifier.getAllMembers().get(serverId);
            if (clientPortAddress!=null && qs!=null && qs.clientAddr!=null){ 
               if ((!clientPortAddress.getAddress().isAnyLocalAddress()
                       && !clientPortAddress.equals(qs.clientAddr)) || 
                   (clientPortAddress.getAddress().isAnyLocalAddress() 
                       && clientPortAddress.getPort()!=qs.clientAddr.getPort())) 
               throw new ConfigException("client address for this server (id = " + serverId + ") in static config file is " + clientPortAddress + " is different from client address found in dynamic file: " + qs.clientAddr);                    
           } 
            if (qs!=null && qs.clientAddr != null) clientPortAddress = qs.clientAddr;                       
            
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
       
    }
    
    public InetSocketAddress getClientPortAddress() { return clientPortAddress; }
    public File getDataDir() { return dataDir; }
    public File getDataLogDir() { return dataLogDir; }
    public int getTickTime() { return tickTime; }
    public int getMaxClientCnxns() { return maxClientCnxns; }
    public int getMinSessionTimeout() { return minSessionTimeout; }
    public int getMaxSessionTimeout() { return maxSessionTimeout; }

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

    public boolean isDistributed() { return (quorumVerifier!=null && quorumVerifier.getVotingMembers().size() > 1); }

    public LearnerType getPeerType() {
        return peerType;
    }
    
    public String getDynamicConfigFilename() {
       return dynamicConfigFileStr;
    }
    
    public String getConfigFilename(){
        return configFileStr;
    }
    
    public boolean getConfigBackwardCompatibility(){
        return configBackwardCompatibilityMode;
    }
    
}
