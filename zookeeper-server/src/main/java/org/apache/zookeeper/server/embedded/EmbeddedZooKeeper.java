package org.apache.zookeeper.server.embedded;

import org.apache.zookeeper.util.ServiceUtils;

/**
 * Factory methods for starting ZooKeeper servers.
 */
public final class EmbeddedZooKeeper {
    private EmbeddedZooKeeper() {
        // Prevent this class from being instantiated.
    }

    /**
     * Starts a ZooKeeper server in production mode, meaning the JVM will exit when the ZooKeeper
     * server exits.
     * @param config The {@link EmbeddedConfig}
     * @return A handle to the ZooKeeper server
     */
    static ZooKeeperServerHandle startProd(final EmbeddedConfig config) {
        ServiceUtils.setSystemExitProcedure(ServiceUtils.SYSTEM_EXIT);
        return new QuorumPeerMainHandle(config);
    }

    /**
     * Starts a ZooKeeper server in testing mode, meaning the JVM will not exit when the ZooKeeper
     * server exits.
     * @param config The {@link EmbeddedConfig}
     * @return A handle to the ZooKeeper server
     */
    static ZooKeeperServerHandle startTest(final EmbeddedConfig config) {
        ServiceUtils.setSystemExitProcedure(ServiceUtils.LOG_ONLY);
        return new QuorumPeerMainHandle(config);
    }
}
