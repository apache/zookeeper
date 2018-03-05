/*
 * QuorumPeerMain.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef QuorumPeerMain_HH_
#define QuorumPeerMain_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./QuorumPeer.hh"
#include "./QuorumPeerConfig.hh"
#include "../ServerCnxnFactory.hh"
#include "../ZKDatabase.hh"
#include "../DatadirCleanupManager.hh"
#include "../ZooKeeperServerMain.hh"
#include "../persistence/FileTxnSnapLog.hh"

namespace efc {
namespace ezk {

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 */
class QuorumPeerMain {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumPeerMain.class);

    constexpr static const char* USAGE = "Usage: QuorumPeerMain configfile";

public:
    QuorumPeer quorumPeer;

    /**
     * To start the replicated server specify the configuration file name on
     * the command line.
     * @param args path to the configfile
     */
    static void main(int argc, const char** argv) {
		sp<EA<EString*> > args = SystemUtils::cargs2jargs(argc, argv);
		if (args == null) {
			LOG->error("Invalid arguments, exiting abnormally");
			LOG->info(USAGE);
			ESystem::err->println(USAGE);
			ESystem::exit(1);
		}

		QuorumPeerMain::main(*args);
    }

    static void main(EA<EString*>& args) {
        QuorumPeerMain main;// = new QuorumPeerMain();
        try {
            main.initializeAndRun(args);
        } catch (EIllegalArgumentException& e) {
            LOG->error("Invalid arguments, exiting abnormally", e);
            LOG->info(USAGE);
            ESystem::err->println(USAGE);
            ESystem::exit(2);
        } catch (ConfigException& e) {
        	LOG->error("Invalid config, exiting abnormally", e);
        	ESystem::err->println("Invalid config, exiting abnormally");
        	ESystem::exit(2);
        } catch (EException& e) {
        	LOG->error("Unexpected exception, exiting abnormally", e);
        	ESystem::exit(1);
        }
        LOG->info("Exiting normally");
        ESystem::exit(0);
    }

    void initializeAndRun(EA<EString*>& args)
    	THROWS2(ConfigException, EIOException)
    {
        QuorumPeerConfig config;// = new QuorumPeerConfig();
        if (args.length() == 1) {
            config.parse(args[0]);
        }

        // Start and schedule the the purge task
        DatadirCleanupManager purgeMgr(config
                .getDataDir(), config.getDataLogDir(), config
                .getSnapRetainCount(), config.getPurgeInterval());
        purgeMgr.start();

        if (args.length() == 1 && config.servers.size() > 0) {
            runFromConfig(config);
        } else {
        	LOG->warn("Either no config or no quorum defined in config, running "
                      " in standalone mode");
            // there is only server in the quorum -- run as standalone
            ZooKeeperServerMain::main(args);
        }
    }

	void runFromConfig(QuorumPeerConfig& config) THROWS(EIOException) {
		LOG->info("Starting quorum peer");

		try {
			sp<ServerCnxnFactory> cnxnFactory =
					ServerCnxnFactory::createFactory();
			cnxnFactory->configure(cnxnFactory, config.getClientPortAddress(),
					config.getMaxClientCnxns());

			//@see: quorumPeer = getQuorumPeer();

			EFile dataDir(config.getDataDir().c_str());
			EFile snapDir(config.getDataLogDir().c_str());

			quorumPeer.setQuorumPeers(config.getServers());
			quorumPeer.setTxnFactory(new FileTxnSnapLog(&dataDir, &snapDir));
			quorumPeer.setElectionType(config.getElectionAlg());
			quorumPeer.setMyid(config.getServerId());
			quorumPeer.setTickTime(config.getTickTime());
			quorumPeer.setInitLimit(config.getInitLimit());
			quorumPeer.setSyncLimit(config.getSyncLimit());
			quorumPeer.setQuorumListenOnAllIPs(
					config.getQuorumListenOnAllIPs());
			quorumPeer.setCnxnFactory(cnxnFactory);
			quorumPeer.setQuorumVerifier(config.getQuorumVerifier());
			quorumPeer.setClientPortAddress(config.getClientPortAddress());
			quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
			quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
			quorumPeer.setZKDatabase(
					new ZKDatabase(quorumPeer.getTxnFactory()));
			quorumPeer.setLearnerType(config.getPeerType());
			quorumPeer.setSyncEnabled(config.getSyncEnabled());

			quorumPeer.setQuorumCnxnThreadsSize(
					config.quorumCnxnThreadsSize);
			quorumPeer.initialize();

			quorumPeer.start();
			quorumPeer.join();
		} catch (EInterruptedException& e) {
			// warn, but generally this is ok
			LOG->warn("Quorum Peer interrupted", e);
		}
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumPeerMain_HH_ */
