/*
 * ServerConfig.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ServerConfig_HH_
#define ServerConfig_HH_

#include "Efc.hh"

#include "./quorum/QuorumPeerConfig.hh"

namespace efc {
namespace ezk {

/**
 * Server configuration storage.
 *
 * We use this instead of Properties as it's typed.
 *
 */

class ServerConfig {
public:
    ////
    //// If you update the configuration parameters be sure
    //// to update the "conf" 4letter word
    ////
    sp<EInetSocketAddress> clientPortAddress;
    EString dataDir;
    EString dataLogDir;
    int tickTime;// = ZooKeeperServer.DEFAULT_TICK_TIME;
    int maxClientCnxns;
    /** defaults to -1 if not set explicitly */
    int minSessionTimeout;// = -1;
    /** defaults to -1 if not set explicitly */
    int maxSessionTimeout;// = -1;

public:
    ServerConfig() :
    	tickTime(ZooKeeperServer::DEFAULT_TICK_TIME),
    	minSessionTimeout(-1),
    	maxSessionTimeout(-1) {
    	//
    }

    /**
     * Parse arguments for server configuration
     * @param args clientPort dataDir and optional tickTime and maxClientCnxns
     * @return ServerConfig configured wrt arguments
     * @throws IllegalArgumentException on invalid usage
     */
    void parse(EA<EString*>& args) {
    	if (args.length() < 2 || args.length() > 4) {
            throw EIllegalArgumentException(__FILE__, __LINE__,
            		("Invalid number of arguments:" + EArrays::toString(&args)).c_str());
        }

        clientPortAddress = new EInetSocketAddress(EInteger::parseInt(args[0]->c_str()));
        dataDir = args[1];
        dataLogDir = dataDir;
        if (args.length() >= 3) {
            tickTime = EInteger::parseInt(args[2]->c_str());
        }
        if (args.length() == 4) {
            maxClientCnxns = EInteger::parseInt(args[3]->c_str());
        }
    }

    /**
     * Parse a ZooKeeper configuration file
     * @param path the patch of the configuration file
     * @return ServerConfig configured wrt arguments
     * @throws ConfigException error processing configuration
     */
    void parse(EString path) THROWS(ConfigException) {
        QuorumPeerConfig config;// = new QuorumPeerConfig();
        config.parse(path);

        // let qpconfig parse the file and then pull the stuff we are
        // interested in
        readFrom(config);
    }

    /**
     * Read attributes from a QuorumPeerConfig.
     * @param config
     */
    void readFrom(QuorumPeerConfig& config) {
      clientPortAddress = config.getClientPortAddress();
      dataDir = config.getDataDir();
      dataLogDir = config.getDataLogDir();
      tickTime = config.getTickTime();
      maxClientCnxns = config.getMaxClientCnxns();
      minSessionTimeout = config.getMinSessionTimeout();
      maxSessionTimeout = config.getMaxSessionTimeout();
    }

    sp<EInetSocketAddress> getClientPortAddress() {
        return clientPortAddress;
    }
    EString getDataDir() { return dataDir; }
    EString getDataLogDir() { return dataLogDir; }
    int getTickTime() { return tickTime; }
    int getMaxClientCnxns() { return maxClientCnxns; }
    /** minimum session timeout in milliseconds, -1 if unset */
    int getMinSessionTimeout() { return minSessionTimeout; }
    /** maximum session timeout in milliseconds, -1 if unset */
    int getMaxSessionTimeout() { return maxSessionTimeout; }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ServerConfig_HH_ */
