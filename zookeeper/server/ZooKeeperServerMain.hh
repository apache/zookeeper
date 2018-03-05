/*
 * ZooKeeperServerMain.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperServerMain_HH_
#define ZooKeeperServerMain_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ServerConfig.hh"
#include "./persistence/FileTxnSnapLog.hh"
#include "./quorum/QuorumPeerConfig.hh"
#include "../common/SystemUtils.hh"

namespace efc {
namespace ezk {

/**
 * This class starts and runs a standalone ZooKeeperServer.
 */

class ZooKeeperServerMain {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ZooKeeperServerMain.class);

    constexpr static const char* USAGE =
        "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    sp<ServerCnxnFactory> cnxnFactory;

public:

	/*
	 * Start up the ZooKeeper server.
	 *
	 * @param args the configfile or the port datadir [ticktime]
	 */
    static void main(int argc, const char** argv) {
		sp<EA<EString*> > args = SystemUtils::cargs2jargs(argc, argv);
		if (args == null) {
			LOG->error("Invalid arguments, exiting abnormally");
			LOG->info(USAGE);
			ESystem::err->println(USAGE);
			ESystem::exit(1);
		}
		ZooKeeperServerMain::main(*args);
    }

	static void main(EA<EString*>& args) {
		ZooKeeperServerMain main;
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

protected:
	void initializeAndRun(EA<EString*>& args)
			THROWS2(ConfigException, EIOException) {
		ServerConfig config; // = new ServerConfig();
		if (args.length() == 1) {
			config.parse(args[0]);
		} else {
			config.parse(args);
		}

		runFromConfig(config);
	}

    /**
     * Run from a ServerConfig.
     * @param config ServerConfig to use.
     * @throws IOException
     */
    void runFromConfig(ServerConfig& config) THROWS(EIOException) {
        LOG->info("Starting server");
        sp<FileTxnSnapLog> txnLog = null;

        ON_FINALLY_NOTHROW(
			if (txnLog != null) {
				txnLog->close();
			}
        ) {
			try {
				// Note that this thread isn't going to be doing anything else,
				// so rather than spawning another thread, we will just call
				// run() in this thread.
				// create a file logger url from the command line args
				ZooKeeperServer zkServer;// = new ZooKeeperServer();
				// Registers shutdown handler which will be used to know the
				// server error or shutdown state changes.
				ECountDownLatch shutdownLatch(1);// = new CountDownLatch(1);
				ZooKeeperServerShutdownHandler zssh(&shutdownLatch);
				zkServer.registerServerShutdownHandler(&zssh);

				EFile dataDir(config.dataLogDir.c_str());
				EFile snapDir(config.dataDir.c_str());
				txnLog = new FileTxnSnapLog(&dataDir, &snapDir);
				zkServer.setTxnLogFactory(txnLog);
				zkServer.setTickTime(config.tickTime);
				zkServer.setMinSessionTimeout(config.minSessionTimeout);
				zkServer.setMaxSessionTimeout(config.maxSessionTimeout);
				cnxnFactory = ServerCnxnFactory::createFactory();
				cnxnFactory->configure(cnxnFactory, config.getClientPortAddress(),
						config.getMaxClientCnxns());
				cnxnFactory->startup(&zkServer);
				// Watch status of ZooKeeper server. It will do a graceful shutdown
				// if the server is not running or hits an internal error.
				shutdownLatch.await();
				shutdown();

				cnxnFactory->join();
				if (zkServer.canShutdown()) {
					zkServer.shutdown(true);
				}
			} catch (EInterruptedException& e) {
				// warn, but generally this is ok
				LOG->warn("Server interrupted", e);
			}
        }}
    }

    /**
     * Shutdown the serving instance
     */
    void shutdown() {
        if (cnxnFactory != null) {
            cnxnFactory->shutdown();
        }
    }

    // VisibleForTesting
    sp<ServerCnxnFactory> getCnxnFactory() {
        return cnxnFactory;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperServerMain_HH_ */
