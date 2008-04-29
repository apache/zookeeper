/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.quorum;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.server.ServerConfig;
import com.yahoo.zookeeper.server.ZooLog;
import com.yahoo.zookeeper.server.quorum.QuorumPeer.QuorumServer;

public class QuorumPeerConfig extends ServerConfig {
    private static final Logger LOG = Logger.getLogger(QuorumPeerConfig.class);

    private int tickTime;
    private int initLimit;
    private int syncLimit;
    private int electionAlg;
    private int electionPort;
    private ArrayList<QuorumServer> servers = null;
    private long serverId;

    private QuorumPeerConfig(int port, String dataDir, String dataLogDir) {
        super(port, dataDir, dataLogDir);
    }

    public static void parse(String[] args) {
        if(instance!=null)
            return;
        
        try {
            if (args.length != 1) {
                System.err.println("USAGE: configFile");
                System.exit(2);
            }
            File zooCfgFile = new File(args[0]);
            if (!zooCfgFile.exists()) {
                LOG.error(zooCfgFile.toString() + " file is missing");
                System.exit(2);
            }
            Properties cfg = new Properties();
            cfg.load(new FileInputStream(zooCfgFile));
            ArrayList<QuorumServer> servers = new ArrayList<QuorumServer>();
            String dataDir = null;
            String dataLogDir = null;
            int clientPort = 0;
            int tickTime = 0;
            int initLimit = 0;
            int syncLimit = 0;
            int electionAlg = 0;
            int electionPort = 0;
            for (Entry<Object, Object> entry : cfg.entrySet()) {
                String key = entry.getKey().toString();
                String value = entry.getValue().toString();
                if (key.equals("dataDir")) {
                    dataDir = value;
                } else if (key.equals("dataLogDir")) {
                    dataLogDir = value;
                } else if (key.equals("traceFile")) {
                    System.setProperty("requestTraceFile", value);
                } else if (key.equals("clientPort")) {
                    clientPort = Integer.parseInt(value);
                } else if (key.equals("tickTime")) {
                    tickTime = Integer.parseInt(value);
                } else if (key.equals("initLimit")) {
                    initLimit = Integer.parseInt(value);
                } else if (key.equals("syncLimit")) {
                    syncLimit = Integer.parseInt(value);
                } else if (key.equals("electionAlg")) {
                    electionAlg = Integer.parseInt(value);
                } else if (key.equals("electionPort")) {
                    electionPort = Integer.parseInt(value);
                } else if (key.startsWith("server.")) {
                    int dot = key.indexOf('.');
                    long sid = Long.parseLong(key.substring(dot + 1));
                    String parts[] = value.split(":");
                    if (parts.length != 2) {
                        LOG.error(value
                                + " does not have the form host:port");
                    }
                    InetSocketAddress addr = new InetSocketAddress(parts[0],
                            Integer.parseInt(parts[1]));
                    servers.add(new QuorumServer(sid, addr));
                } else {
                    System.setProperty("zookeeper." + key, value);
                }
            }
            if (dataDir == null) {
                LOG.error("dataDir is not set");
                System.exit(2);
            }
            if (dataLogDir == null) {
                dataLogDir = dataDir;
            } else {
                if (!new File(dataLogDir).isDirectory()) {
                    LOG.error("dataLogDir " + dataLogDir+ " is missing.");
                    System.exit(2);
                }
            }
            if (clientPort == 0) {
                LOG.error("clientPort is not set");
                System.exit(2);
            }
            if (tickTime == 0) {
                LOG.error("tickTime is not set");
                System.exit(2);
            }
            if (servers.size() > 1 && initLimit == 0) {
                LOG.error("initLimit is not set");
                System.exit(2);
            }
            if (servers.size() > 1 && syncLimit == 0) {
                LOG.error("syncLimit is not set");
                System.exit(2);
            }
            QuorumPeerConfig conf = new QuorumPeerConfig(clientPort, dataDir,
                    dataLogDir);
            conf.tickTime = tickTime;
            conf.initLimit = initLimit;
            conf.syncLimit = syncLimit;
            conf.electionAlg = electionAlg;
            conf.electionPort = electionPort;
            conf.servers = servers;
            if (servers.size() > 1) {
                File myIdFile = new File(dataDir, "myid");
                if (!myIdFile.exists()) {
                    LOG.error(myIdFile.toString() + " file is missing");
                    System.exit(2);
                }
                BufferedReader br = new BufferedReader(new FileReader(myIdFile));
                String myIdString = br.readLine();
                try {
                    conf.serverId = Long.parseLong(myIdString);
                } catch (NumberFormatException e) {
                    LOG.error(myIdString + " is not a number");
                    System.exit(2);
                }
            }
            instance=conf;
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
            System.exit(2);
        }
    }

    protected boolean isStandaloneServer(){
        return QuorumPeerConfig.getServers().size() <= 1;
    }

    public static int getTickTime() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).tickTime;
    }

    public static int getInitLimit() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).initLimit;
    }

    public static int getSyncLimit() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).syncLimit;
    }

    public static int getElectionAlg() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).electionAlg;
    }

    public static int getElectionPort() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).electionPort;
    }
    
    public static ArrayList<QuorumServer> getServers() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).servers;
    }

    public static int getQuorumSize(){
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).servers.size();        
    }
    
    public static long getServerId() {
        assert instance instanceof QuorumPeerConfig;
        return ((QuorumPeerConfig)instance).serverId;
    }
}
