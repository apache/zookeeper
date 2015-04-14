package org.apache.zookeeper.server;

import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages cleanup of container ZNodes. This class is meant to only
 * be run from the leader. There's no harm in running from followers/observers
 * but that will be extra work that's not needed. Once started, it periodically
 * checks container nodes that have a cversion > 0 and have no children. A delete
 * is attempted on the node. The result of the delete is unimportant. If the proposal
 * fails or the container node is not empty there's no harm.
 */
public class ContainerManager {
    private static final Logger LOG = LoggerFactory.getLogger(ContainerManager.class);
    private final ZKDatabase zkDb;
    private final RequestProcessor requestProcessor;
    private final int checkIntervalMs;
    private final int maxPerInterval;
    private final Timer timer;
    private final AtomicReference<TimerTask> task = new AtomicReference<TimerTask>(null);

    /**
     * @param zkDb the ZK database
     * @param requestProcessor request processer - used to inject delete container requests
     * @param checkIntervalMs how often to check containers in milliseconds
     * @param maxPerInterval the max containers to delete in one interval - avoids herding of container deletions
     */
    public ContainerManager(ZKDatabase zkDb, RequestProcessor requestProcessor,
                            int checkIntervalMs, int maxPerInterval) {
        this.zkDb = zkDb;
        this.requestProcessor = requestProcessor;
        this.checkIntervalMs = checkIntervalMs;
        this.maxPerInterval = maxPerInterval;
        timer = new Timer("ContainerManagerTask", true);
    }

    /**
     * start/restart the timer the runs the check. Can safely be called multiple times.
     */
    public void start() {
        if ( task.get() == null ) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    checkContainers();
                }
            };
            if ( task.compareAndSet(null, timerTask) ) {
                timer.scheduleAtFixedRate(timerTask, checkIntervalMs, checkIntervalMs);
            }
        }
    }

    /**
     * stop the timer if necessary. Can safely be called multiple times.
     */
    public void stop() {
        TimerTask timerTask = task.getAndSet(null);
        if ( timerTask != null ) {
            timerTask.cancel();
        }
    }

    /**
     * Manually check the containers. Not normally used directly
     */
    public void checkContainers() {
        for ( String containerPath : getCandidates() ) {
            ByteBuffer path = ByteBuffer.wrap(containerPath.getBytes());
            Request request = new Request(null, 0, 0, ZooDefs.OpCode.deleteContainer, path, null);
            try {
                LOG.info("Attempting to delete candidate container: " + containerPath);
                requestProcessor.processRequest(request);
            } catch (Exception e) {
                LOG.error("Could not delete container: " + containerPath, e);
            }
        }
    }

    // VisibleForTesting
    protected Collection<String> getCandidates() {
        Set<String> candidates = new HashSet<String>();
        for ( String containerPath : zkDb.getDataTree().getContainers() ) {
            DataNode node = zkDb.getDataTree().getNode(containerPath);
            if ((node != null) && (node.stat.getEphemeralOwner() == DataTree.CONTAINER_EPHEMERAL_OWNER)) { // otherwise, the node changed type on us - ignore it
                if ((node.stat.getCversion() > 0) && (node.getChildren().size() == 0)) {
                    candidates.add(containerPath);
                    if ( candidates.size() >= maxPerInterval ) {
                        LOG.info("Stopping at " + maxPerInterval);
                        break;
                    }
                }
            }
        }
        return candidates;
    }
}
