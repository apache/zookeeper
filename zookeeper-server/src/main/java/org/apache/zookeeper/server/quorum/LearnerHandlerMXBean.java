package org.apache.zookeeper.server.quorum;

/**
 * This MBean represents a server connection for a learner.
 */
public interface LearnerHandlerMXBean {
    /**
     * Terminate the connection. The learner will attempt to reconnect to
     * the leader or to the next ObserverMaster if that feature is enabled
     */
    public void terminateConnection();
}
