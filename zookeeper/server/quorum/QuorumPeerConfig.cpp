/*
 * QuorumPeerConfig.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./QuorumPeerConfig.hh"

namespace efc {
namespace ezk {

sp<ELogger> QuorumPeerConfig::LOG = ELoggerManager::getLogger("QuorumPeerConfig");

void QuorumPeerConfig::parseProperties(EProperties* zkProp) {
    int clientPort = 0;
    EString clientPortAddress;
    auto iter = zkProp->entrySet()->iterator();
    while (iter->hasNext()) {
    	auto entry = iter->next();
        EString key = entry->getKey()->toString().trim();
        EString value = entry->getValue()->toString().trim();
        if (key.equals("dataDir")) {
            dataDir = value;
        } else if (key.equals("dataLogDir")) {
            dataLogDir = value;
        } else if (key.equals("clientPort")) {
            clientPort = EInteger::parseInt(value.c_str());
        } else if (key.equals("clientPortAddress")) {
            clientPortAddress = value.trim();
        } else if (key.equals("tickTime")) {
            tickTime = EInteger::parseInt(value.c_str());
        } else if (key.equals("maxClientCnxns")) {
            maxClientCnxns = EInteger::parseInt(value.c_str());
        } else if (key.equals("minSessionTimeout")) {
            minSessionTimeout = EInteger::parseInt(value.c_str());
        } else if (key.equals("maxSessionTimeout")) {
            maxSessionTimeout = EInteger::parseInt(value.c_str());
        } else if (key.equals("initLimit")) {
            initLimit = EInteger::parseInt(value.c_str());
        } else if (key.equals("syncLimit")) {
            syncLimit = EInteger::parseInt(value.c_str());
        } else if (key.equals("electionAlg")) {
            electionAlg = EInteger::parseInt(value.c_str());
        } else if (key.equals("quorumListenOnAllIPs")) {
            quorumListenOnAllIPs = EBoolean::parseBoolean(value.c_str());
        } else if (key.equals("peerType")) {
            if (value.toLowerCase().equals("observer")) {
                peerType = QuorumServer::LearnerType::OBSERVER;
            } else if (value.toLowerCase().equals("participant")) {
                peerType = QuorumServer::LearnerType::PARTICIPANT;
            } else
            {
                throw ConfigException(__FILE__, __LINE__, "Unrecognised peertype: " + value);
            }
        } else if (key.equals( "syncEnabled" )) {
            syncEnabled = EBoolean::parseBoolean(value.c_str());
        } else if (key.equals("autopurge.snapRetainCount")) {
            snapRetainCount = EInteger::parseInt(value.c_str());
        } else if (key.equals("autopurge.purgeInterval")) {
            purgeInterval = EInteger::parseInt(value.c_str());
        } else if (key.startsWith("server.")) {
            int dot = key.indexOf('.');
            llong sid = ELLong::parseLLong(key.substring(dot + 1).c_str());
            EArray<EString*> parts = splitWithLeadingHostname(value);
            if ((parts.length() != 2) && (parts.length() != 3) && (parts.length() !=4)) {
                LOG->error(value
                   + " does not have the form host:port or host:port:port " +
                   " or host:port:port:type");
            }
            QuorumServer::LearnerType type = QuorumServer::LearnerType(-1);
            EString* hostname = parts[0];
            int port = EInteger::parseInt(parts[1]->c_str());
            int electionPort = -1;
            if (parts.length() > 2){
            	electionPort=EInteger::parseInt(parts[2]->c_str());
            }
            if (parts.length() > 3){
                if (parts[3]->toLowerCase().equals("observer")) {
                    type = QuorumServer::LearnerType::OBSERVER;
                } else if (parts[3]->toLowerCase().equals("participant")) {
                    type = QuorumServer::LearnerType::PARTICIPANT;
                } else {
                    throw ConfigException(__FILE__, __LINE__, "Unrecognised peertype: " + value);
                }
            }

            //cxxjava: !!!
            if (type == -1) {
            	type = QuorumServer::LearnerType::PARTICIPANT;
            }

            if (type == QuorumServer::LearnerType::OBSERVER){
                observers.put(sid, new QuorumServer(sid, hostname, port, electionPort, type));
            } else {
                servers.put(sid, new QuorumServer(sid, hostname, port, electionPort, type));
            }
        } else if (key.startsWith("group")) {
            int dot = key.indexOf('.');
            llong gid = ELLong::parseLLong(key.substring(dot + 1).c_str());

            numGroups++;

            EArray<EString*> parts = EPattern::split(":", value.c_str(), 0);
            for (int i=0; i<parts.length(); i++) {
            	EString* s = parts[i];
                llong sid = ELLong::parseLLong(s->c_str());
                if(serverGroup->containsKey(sid))
                    throw ConfigException(__FILE__, __LINE__, EString("Server ") + sid + "is in multiple groups");
                else
                	serverGroup->put(sid, new ELLong(gid));
            }

        } else if(key.startsWith("weight")) {
            int dot = key.indexOf('.');
            llong sid = ELLong::parseLLong(key.substring(dot + 1).c_str());
            serverWeight->put(sid, new ELLong(ELLong::parseLLong(value.c_str())));
        } else if (key.equals(QuorumAuth::QUORUM_SASL_AUTH_ENABLED)) {
            quorumEnableSasl = EBoolean::parseBoolean(value.c_str());
        } else if (key.equals(QuorumAuth::QUORUM_SERVER_SASL_AUTH_REQUIRED)) {
            quorumServerRequireSasl = EBoolean::parseBoolean(value.c_str());
        } else if (key.equals(QuorumAuth::QUORUM_LEARNER_SASL_AUTH_REQUIRED)) {
            quorumLearnerRequireSasl = EBoolean::parseBoolean(value.c_str());
        } else if (key.equals(QuorumAuth::QUORUM_LEARNER_SASL_LOGIN_CONTEXT)) {
            quorumLearnerLoginContext = value;
        } else if (key.equals(QuorumAuth::QUORUM_SERVER_SASL_LOGIN_CONTEXT)) {
            quorumServerLoginContext = value;
        } else if (key.equals(QuorumAuth::QUORUM_KERBEROS_SERVICE_PRINCIPAL)) {
            quorumServicePrincipal = value;
        } else if (key.equals("quorum.cnxn.threads.size")) {
            quorumCnxnThreadsSize = EInteger::parseInt(value.c_str());
        } else {
            ESystem::setProperty(("zookeeper." + key).c_str(), value.c_str());
        }
    }
    if (!quorumEnableSasl && quorumServerRequireSasl) {
        throw EIllegalArgumentException(__FILE__, __LINE__,
                (QuorumAuth::QUORUM_SASL_AUTH_ENABLED
                + EString(" is disabled, so cannot enable ")
                + QuorumAuth::QUORUM_SERVER_SASL_AUTH_REQUIRED).c_str());
    }
    if (!quorumEnableSasl && quorumLearnerRequireSasl) {
        throw EIllegalArgumentException(__FILE__, __LINE__,
                (QuorumAuth::QUORUM_SASL_AUTH_ENABLED
                + EString(" is disabled, so cannot enable ")
                + QuorumAuth::QUORUM_LEARNER_SASL_AUTH_REQUIRED).c_str());
    }
    // If quorumpeer learner is not auth enabled then self won't be able to
    // join quorum. So this condition is ensuring that the quorumpeer learner
    // is also auth enabled while enabling quorum server require sasl.
    if (!quorumLearnerRequireSasl && quorumServerRequireSasl) {
        throw EIllegalArgumentException(__FILE__, __LINE__,
                (QuorumAuth::QUORUM_LEARNER_SASL_AUTH_REQUIRED
                + EString(" is disabled, so cannot enable ")
                + QuorumAuth::QUORUM_SERVER_SASL_AUTH_REQUIRED).c_str());
    }
    // Reset to MIN_SNAP_RETAIN_COUNT if invalid (less than 3)
    // PurgeTxnLog.purge(File, File, int) will not allow to purge less
    // than 3.
    if (snapRetainCount < MIN_SNAP_RETAIN_COUNT) {
        LOG->warn(EString("Invalid autopurge.snapRetainCount: ") + snapRetainCount
                + ". Defaulting to " + MIN_SNAP_RETAIN_COUNT);
        snapRetainCount = MIN_SNAP_RETAIN_COUNT;
    }

    if (dataDir.isEmpty()) {
        throw EIllegalArgumentException(__FILE__, __LINE__,"dataDir is not set");
    }
    if (dataLogDir.isEmpty()) {
        dataLogDir = dataDir;
    }
    if (clientPort == 0) {
        throw EIllegalArgumentException(__FILE__, __LINE__, "clientPort is not set");
    }
    if (!clientPortAddress.isEmpty()) {
    	auto ia = EInetAddress::getByName(clientPortAddress.c_str());
        this->clientPortAddress = new EInetSocketAddress(&ia, clientPort);
    } else {
        this->clientPortAddress = new EInetSocketAddress(clientPort);
    }

    if (tickTime == 0) {
        throw EIllegalArgumentException(__FILE__, __LINE__, "tickTime is not set");
    }
    if (minSessionTimeout > maxSessionTimeout) {
        throw EIllegalArgumentException(__FILE__, __LINE__,
                "minSessionTimeout must not be larger than maxSessionTimeout");
    }
    if (servers.size() == 0) {
        if (observers.size() > 0) {
            throw EIllegalArgumentException(__FILE__, __LINE__, "Observers w/o participants is an invalid configuration");
        }
        // Not a quorum configuration so return immediately - not an error
        // case (for b/w compatibility), server will default to standalone
        // mode.
        return;
    } else if (servers.size() == 1) {
        if (observers.size() > 0) {
            throw EIllegalArgumentException(__FILE__, __LINE__, "Observers w/o quorum is an invalid configuration");
        }

        // HBase currently adds a single server line to the config, for
        // b/w compatibility reasons we need to keep this here.
        LOG->error("Invalid configuration, only one server specified (ignoring)");
        servers.clear();
    } else if (servers.size() > 1) {
        if (servers.size() == 2) {
            LOG->warn("No server failure will be tolerated. You need at least 3 servers.");
        } else if (servers.size() % 2 == 0) {
            LOG->warn("Non-optimial configuration, consider an odd number of servers.");
        }
        if (initLimit == 0) {
            throw EIllegalArgumentException(__FILE__, __LINE__, "initLimit is not set");
        }
        if (syncLimit == 0) {
            throw EIllegalArgumentException(__FILE__, __LINE__, "syncLimit is not set");
        }
        /*
         * If using FLE, then every server requires a separate election
         * port.
         */
        if (electionAlg != 0) {
        	auto iter = servers.values()->iterator();
        	while (iter->hasNext()) {
        		QuorumServer* s = iter->next();
                if (s->electionAddr == null)
                    throw EIllegalArgumentException(__FILE__, __LINE__,
                            (EString("Missing election port for server: ") + s->id).c_str());
            }
        }

        /*
         * Default of quorum config is majority
         */
        if(serverGroup->size() > 0){
            if(servers.size() != serverGroup->size())
                throw ConfigException(__FILE__, __LINE__,"Every server must be in exactly one group");
            /*
             * The deafult weight of a server is 1
             */
            auto iter = servers.values()->iterator();
			while (iter->hasNext()) {
				QuorumServer* s = iter->next();
                if(!serverWeight->containsKey(s->id))
                	serverWeight->put(s->id, new ELLong(1));
            }

            /*
             * Set the quorumVerifier to be QuorumHierarchical
             */
            quorumVerifier = new QuorumHierarchical(numGroups,
                    serverWeight, serverGroup);
        } else {
            /*
             * The default QuorumVerifier is QuorumMaj
             */

            LOG->info("Defaulting to majority quorums");
            quorumVerifier = new QuorumMaj(servers.size());
        }

        // Now add observers to servers, once the quorums have been
        // figured out
        //@see: servers.putAll(observers);
        auto iter = observers.entrySet()->iterator();
        while (iter->hasNext()) {
        	auto me = iter->next();
        	servers.put(me->getKey(), me->getValue());
        }

        EFile myIdFile(dataDir.c_str(), "myid");
        if (!myIdFile.exists()) {
            throw EIllegalArgumentException(__FILE__, __LINE__, (myIdFile.toString()
                    + " file is missing").c_str());
        }
        /* @see:
        BufferedReader br = new BufferedReader(new FileReader(myIdFile));
        String myIdString;
        try {
            myIdString = br.readLine();
        } finally {
            br.close();
        }
        */
        EDataInputStream dis(new EBufferedInputStream(new EFileInputStream(&myIdFile), 8192, true), true);
        sp<EString> myIdString;
        ON_FINALLY_NOTHROW(
        	dis.close();
        ) {
        	myIdString = dis.readLine();
        }}

        try {
            serverId = ELLong::parseLLong(myIdString->c_str());
            EMDC::put("myid", myIdString->c_str());
        } catch (ENumberFormatException& e) {
            throw EIllegalArgumentException(__FILE__, __LINE__, ("serverid " + *myIdString
                    + " is not a number").c_str());
        }

        // Warn about inconsistent peer type
        QuorumServer::LearnerType roleByServersList = observers.containsKey(serverId) ? QuorumServer::LearnerType::OBSERVER
                : QuorumServer::LearnerType::PARTICIPANT;
        if (roleByServersList != peerType) {
            LOG->warn(EString("Peer type from servers list (") + roleByServersList
                    + ") doesn't match peerType (" + peerType
                    + "). Defaulting to servers list.");

            peerType = roleByServersList;
        }
    }
}

} /* namespace ezk */
} /* namespace efc */
