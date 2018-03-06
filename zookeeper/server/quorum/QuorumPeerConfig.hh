/*
 * QuorumPeerConfig.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumPeerConfig_HH_
#define QuorumPeerConfig_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./QuorumAuth.hh"
#include "./QuorumServer.hh"
#include "./QuorumMaj.hh"
#include "./QuorumHierarchical.hh"
#include "../ZooKeeperServer.hh"

namespace efc {
namespace ezk {

class QuorumPeerConfig {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumPeerConfig.class);

    /**
     * Minimum snapshot retain count.
     * @see org.apache.zookeeper.server.PurgeTxnLog#purge(File, File, int)
     */
    static const int MIN_SNAP_RETAIN_COUNT = 3;

	static EArray<EString*> splitWithLeadingHostname(EString s)
			THROWS(ConfigException)
	{
		/* Does it start with an IPv6 literal? */
		if (s.startsWith("[")) {
			int i = s.indexOf("]:");
			if (i < 0) {
				throw ConfigException(__FILE__, __LINE__, s + " starts with '[' but has no matching ']:'");
			}

			/* @see:
			String[] sa = s.substring(i + 2).split(":");
			String[] nsa = new String[sa.length + 1];
			nsa[0] = s.substring(1, i);
			System.arraycopy(sa, 0, nsa, 1, sa.length);
			return nsa;
			*/

			EArray<EString*> sa = EPattern::split(":", s.substring(i + 2).c_str(), 0);
			EArray<EString*> nsa(sa.length() + 1);
			nsa[0] = new EString(s.substring(1, i));
			for (int i=0; i<sa.length(); i++) {
				nsa[i+1] = sa[i];
			}
			sa.setAutoFree(false);
			return nsa;
		} else {
			return EPattern::split(":", s.c_str(), 0);
		}
	}

//protected:
public:
    sp<EInetSocketAddress> clientPortAddress;
    EString dataDir;
    EString dataLogDir;
    int tickTime;// = ZooKeeperServer.DEFAULT_TICK_TIME;
    int maxClientCnxns;// = 60;
    /** defaults to -1 if not set explicitly */
    int minSessionTimeout;// = -1;
    /** defaults to -1 if not set explicitly */
    int maxSessionTimeout;// = -1;

    int initLimit;
    int syncLimit;
    int electionAlg;// = 3;
    int electionPort;// = 2182;
    boolean quorumListenOnAllIPs;// = false;
    EHashMap<llong, QuorumServer*> servers;// = new HashMap<Long, QuorumServer>();
    EHashMap<llong, QuorumServer*> observers;// = new HashMap<Long, QuorumServer>();

    llong serverId;
    sp<EHashMap<llong, ELLong*> > serverWeight;// = new HashMap<Long, Long>();
    sp<EHashMap<llong, ELLong*> > serverGroup;// = new HashMap<Long, Long>();
    int numGroups;// = 0;
    sp<QuorumVerifier> quorumVerifier;
    int snapRetainCount;// = 3;
    int purgeInterval;// = 0;
    boolean syncEnabled;// = true;

    QuorumServer::LearnerType peerType;// = LearnerType.PARTICIPANT;

    /** Configurations for the quorumpeer-to-quorumpeer sasl authentication */
    boolean quorumServerRequireSasl;// = false;
    boolean quorumLearnerRequireSasl;// = false;
    boolean quorumEnableSasl;// = false;
    EString quorumServicePrincipal;// = QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE;
    EString quorumLearnerLoginContext;// = QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    EString quorumServerLoginContext;// = QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    int quorumCnxnThreadsSize;

public:
    class ConfigException : public EException {
    public:
    	ConfigException(const char* _file_, int _line_, EString msg) :
    		EException(_file_, _line_, msg.c_str()) {
        }
        ConfigException(const char* _file_, int _line_, EString msg, EException* e) :
    		EException(_file_, _line_, msg.c_str(), e) {
        }
    };

    virtual ~QuorumPeerConfig() {
    	//
    }

    QuorumPeerConfig() {
    	tickTime = ZooKeeperServer::DEFAULT_TICK_TIME;
    	maxClientCnxns = 60;
    	minSessionTimeout = -1;
    	maxSessionTimeout = -1;
    	initLimit = 0;
    	syncLimit = 0;
    	electionAlg = 3;
    	electionPort = 2182;
    	quorumListenOnAllIPs = false;
    	numGroups = 0;
    	snapRetainCount = 3;
    	purgeInterval = 0;
    	syncEnabled = true;
    	peerType = QuorumServer::LearnerType::PARTICIPANT;
    	quorumServerRequireSasl = false;
    	quorumLearnerRequireSasl = false;
    	quorumEnableSasl = false;
    	quorumServicePrincipal = QuorumAuth::QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE;
    	quorumLearnerLoginContext = QuorumAuth::QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    	quorumServerLoginContext = QuorumAuth::QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    	quorumCnxnThreadsSize = 0;

    	serverWeight = new EHashMap<llong, ELLong*>(false);
    	serverGroup = new EHashMap<llong, ELLong*>(false);
    }

    /**
     * Parse a ZooKeeper configuration file
     * @param path the patch of the configuration file
     * @throws ConfigException error processing configuration
     */
    void parse(EString path) THROWS(ConfigException) {
        EFile configFile(path.c_str());

        LOG->info("Reading configuration from: " + configFile.toString());

        try {
            if (!configFile.exists()) {
                throw EIllegalArgumentException(__FILE__, __LINE__, (configFile.toString()
                        + " file is missing").c_str());
            }

            EProperties cfg;
            EFileInputStream in(&configFile);
            ON_FINALLY_NOTHROW(
				in.close();
            ) {
            	cfg.load(&in);
            }}

            parseProperties(&cfg);
        } catch (EIOException& e) {
            throw ConfigException(__FILE__, __LINE__, "Error processing " + path, &e);
        } catch (EIllegalArgumentException& e) {
            throw ConfigException(__FILE__, __LINE__, "Error processing " + path, &e);
        }
    }

    /**
     * Parse config from a Properties.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    void parseProperties(EProperties* zkProp) THROWS2(EIOException, ConfigException);

	sp<EInetSocketAddress> getClientPortAddress() {
		return clientPortAddress;
	}
	EString getDataDir() {
		return dataDir;
	}
	EString getDataLogDir() {
		return dataLogDir;
	}
	int getTickTime() {
		return tickTime;
	}
	int getMaxClientCnxns() {
		return maxClientCnxns;
	}
	int getMinSessionTimeout() {
		return minSessionTimeout;
	}
	int getMaxSessionTimeout() {
		return maxSessionTimeout;
	}

	int getInitLimit() {
		return initLimit;
	}
	int getSyncLimit() {
		return syncLimit;
	}
	int getElectionAlg() {
		return electionAlg;
	}
	int getElectionPort() {
		return electionPort;
	}
    
    int getSnapRetainCount() {
        return snapRetainCount;
    }

    int getPurgeInterval() {
        return purgeInterval;
    }
    
    boolean getSyncEnabled() {
        return syncEnabled;
    }

    sp<QuorumVerifier> getQuorumVerifier() {
        return quorumVerifier;
    }

    EHashMap<llong, QuorumServer*>* getServers() {
        //@see: return Collections.unmodifiableMap(servers);
    	return &servers;
    }

    llong getServerId() {
    	return serverId;
    }

    boolean isDistributed() {
    	return servers.size() > 1;
    }

    QuorumServer::LearnerType getPeerType() {
        return peerType;
    }

    boolean getQuorumListenOnAllIPs() {
        return quorumListenOnAllIPs;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumPeerConfig_HH_ */
