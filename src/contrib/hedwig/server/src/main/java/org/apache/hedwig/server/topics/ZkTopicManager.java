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
package org.apache.hedwig.server.topics;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.Either;
import org.apache.hedwig.util.HedwigSocketAddress;
import org.apache.hedwig.zookeeper.SafeAsyncZKCallback;
import org.apache.hedwig.zookeeper.ZkUtils;
import org.apache.hedwig.zookeeper.SafeAsyncZKCallback.DataCallback;
import org.apache.hedwig.zookeeper.SafeAsyncZKCallback.StatCallback;

/**
 * Topics are operated on in parallel as they are independent.
 * 
 */
public class ZkTopicManager extends AbstractTopicManager implements TopicManager {

    static Logger logger = Logger.getLogger(ZkTopicManager.class);
    Random rand = new Random();
    
    /**
     * Persistent storage for topic metadata.
     */
    private ZooKeeper zk;
    String ephemeralNodePath;

    StatCallback loadReportingStatCallback = new StatCallback() {
        @Override
        public void safeProcessResult(int rc, String path, Object ctx, Stat stat) {
            if (rc != KeeperException.Code.OK.intValue()) {
                logger.warn("Failed to update load information in zk");
            }
        }
    };

    // Boolean flag indicating if we should suspend activity. If this is true,
    // all of the Ops put into the queuer will fail automatically.
    protected volatile boolean isSuspended = false;

    /**
     * Create a new topic manager. Pass in an active ZooKeeper client object.
     * 
     * @param zk
     */
    public ZkTopicManager(final ZooKeeper zk, final ServerConfiguration cfg, ScheduledExecutorService scheduler)
            throws UnknownHostException, PubSubException {

        super(cfg, scheduler);
        this.zk = zk;
        this.ephemeralNodePath = cfg.getZkHostsPrefix(new StringBuilder()).append("/").append(addr).toString();

        zk.register(new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType().equals(Watcher.Event.EventType.None)) {
                    if (event.getState().equals(Watcher.Event.KeeperState.Disconnected)) {
                        logger.warn("ZK client has been disconnected to the ZK server!");
                        isSuspended = true;
                    } else if (event.getState().equals(Watcher.Event.KeeperState.SyncConnected)) {
			if (isSuspended) {
	                    logger.info("ZK client has been reconnected to the ZK server!");
			}
			isSuspended = false;
                    }
		}
		// Check for expired connection.
                if (event.getState().equals(Watcher.Event.KeeperState.Expired)) {
                    logger.error("ZK client connection to the ZK server has expired!");
                    System.exit(1);
                }             
	    }
        });
        final SynchronousQueue<Either<Void, PubSubException>> queue = new SynchronousQueue<Either<Void, PubSubException>>();

        registerWithZookeeper(new Callback<Void>() {
            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                logger.error("Failed to register hub with zookeeper", exception);
                ConcurrencyUtils.put(queue, Either.of((Void) null, exception));
            }

            @Override
            public void operationFinished(Object ctx, Void resultOfOperation) {
                logger.info("Successfully registered hub with zookeeper");
                ConcurrencyUtils.put(queue, Either.of(resultOfOperation, (PubSubException) null));
            }
        }, null);

        PubSubException pse = ConcurrencyUtils.take(queue).right();

        if (pse != null) {
            throw pse;
        }
    }

    void registerWithZookeeper(final Callback<Void> callback, Object ctx) {

        ZkUtils.createFullPathOptimistic(zk, ephemeralNodePath, getCurrentLoadData(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL, new SafeAsyncZKCallback.StringCallback() {

                    @Override
                    public void safeProcessResult(int rc, String path, Object ctx, String name) {
                        if (rc == Code.OK.intValue()) {
                            callback.operationFinished(ctx, null);
                            return;
                        }
                        if (rc != Code.NODEEXISTS.intValue()) {
                            KeeperException ke = ZkUtils.logErrorAndCreateZKException(
                                    "Could not create ephemeral node to register hub", ephemeralNodePath, rc);
                            callback.operationFailed(ctx, new PubSubException.ServiceDownException(ke));
                            return;
                        }

                        logger.info("Found stale ephemeral node while registering hub with ZK, deleting it");

                        // Node exists, lets try to delete it and retry
                        zk.delete(ephemeralNodePath, -1, new SafeAsyncZKCallback.VoidCallback() {
                            @Override
                            public void safeProcessResult(int rc, String path, Object ctx) {
                                if (rc == Code.OK.intValue() || rc == Code.NONODE.intValue()) {
                                    registerWithZookeeper(callback, ctx);
                                    return;
                                }
                                KeeperException ke = ZkUtils.logErrorAndCreateZKException(
                                        "Could not delete stale ephemeral node to register hub", ephemeralNodePath, rc);
                                callback.operationFailed(ctx, new PubSubException.ServiceDownException(ke));
                                return;

                            }
                        }, ctx);

                    }
                }, null);
    }

    String hubPath(ByteString topic) {
        return cfg.getZkTopicPath(new StringBuilder(), topic).append("/hub").toString();
    }

    @Override
    protected void realGetOwner(final ByteString topic, final boolean shouldClaim,
            final Callback<HedwigSocketAddress> cb, final Object ctx) {
        // If operations are suspended due to a ZK client disconnect, just error
        // out this call and return.
        if (isSuspended) {
            cb.operationFailed(ctx, new PubSubException.ServiceDownException(
                    "ZKTopicManager service is temporarily suspended!"));
            return;
        }

        if (topics.contains(topic)) {
            cb.operationFinished(ctx, addr);
            return;
        }

        new ZkGetOwnerOp(topic, shouldClaim, cb, ctx).read();
    }

    // Recursively call each other.
    class ZkGetOwnerOp {
        ByteString topic;
        boolean shouldClaim;
        Callback<HedwigSocketAddress> cb;
        Object ctx;
        String hubPath;

        public ZkGetOwnerOp(ByteString topic, boolean shouldClaim, Callback<HedwigSocketAddress> cb, Object ctx) {
            this.topic = topic;
            this.shouldClaim = shouldClaim;
            this.cb = cb;
            this.ctx = ctx;
            hubPath = hubPath(topic);

        }

        public void choose() {
            // Get the list of existing hosts
            String registeredHostsPath = cfg.getZkHostsPrefix(new StringBuilder()).toString();
            zk.getChildren(registeredHostsPath, false, new SafeAsyncZKCallback.ChildrenCallback() {
                @Override
                public void safeProcessResult(int rc, String path, Object ctx, List<String> children) {
                    if (rc != Code.OK.intValue()) {
                        KeeperException e = ZkUtils.logErrorAndCreateZKException(
                                "Could not get list of available hubs", path, rc);
                        cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                        return;
                    }
                    chooseLeastLoadedNode(children);
                }
            }, null);
        }

        public void chooseLeastLoadedNode(final List<String> children) {
            DataCallback dataCallback = new DataCallback() {
                int numResponses = 0;
                int minLoad = Integer.MAX_VALUE;
                String leastLoaded = null;

                @Override
                public void safeProcessResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
                    synchronized (this) {
                        if (rc == KeeperException.Code.OK.intValue()) {
                            try {
                                int load = Integer.parseInt(new String(data));
                                if (logger.isDebugEnabled()){
                                	logger.debug("Found server: " + ctx + " with load: " + load);
                                }
                                if (load < minLoad  || (load == minLoad && rand.nextBoolean())) {
                                    minLoad = load;
                                    leastLoaded = (String) ctx;
                                }
                            } catch (NumberFormatException e) {
                                logger.warn("Corrupted load information from hub:" + ctx);
                                // some corrupted data, we'll just ignore this
                                // hub
                            }
                        }
                        numResponses++;

                        if (numResponses == children.size()) {
                            if (leastLoaded == null) {
                                cb.operationFailed(ZkGetOwnerOp.this.ctx, new PubSubException.ServiceDownException(
                                        "No hub available"));
                                return;
                            }
                            HedwigSocketAddress owner = new HedwigSocketAddress(leastLoaded);
                            if (owner.equals(addr)) {
                                claim();
                            } else {
                                cb.operationFinished(ZkGetOwnerOp.this.ctx, owner);
                            }
                        }
                    }

                }
            };

            for (String child : children) {
                zk.getData(cfg.getZkHostsPrefix(new StringBuilder()).append("/").append(child).toString(), false,
                        dataCallback, child);
            }
        }

        public void claimOrChoose() {
            if (shouldClaim)
                claim();
            else
                choose();
        }

        public void read() {
            zk.getData(hubPath, false, new SafeAsyncZKCallback.DataCallback() {
                @Override
                public void safeProcessResult(int rc, String path, Object ctx, byte[] data, Stat stat) {

                    if (rc == Code.NONODE.intValue()) {
                        claimOrChoose();
                        return;
                    }

                    if (rc != Code.OK.intValue()) {
                        KeeperException e = ZkUtils.logErrorAndCreateZKException("Could not read ownership for topic: "
                                + topic.toStringUtf8(), path, rc);
                        cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                        return;
                    }

                    // successfully did a read
                    HedwigSocketAddress owner = new HedwigSocketAddress(new String(data));
                    if (!owner.equals(addr)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("topic: " + topic.toStringUtf8() + " belongs to someone else: " + owner);
                        }
                        cb.operationFinished(ctx, owner);
                        return;
                    }

                    logger.info("Discovered stale self-node for topic: " + topic.toStringUtf8() + ", will delete it");

                    // we must have previously failed and left a
                    // residual ephemeral node here, so we must
                    // delete it (clean it up) and then
                    // re-create/re-acquire the topic.
                    zk.delete(hubPath, stat.getVersion(), new VoidCallback() {
                        @Override
                        public void processResult(int rc, String path, Object ctx) {
                            if (Code.OK.intValue() == rc || Code.NONODE.intValue() == rc) {
                                claimOrChoose();
                            } else {
                                KeeperException e = ZkUtils.logErrorAndCreateZKException(
                                        "Could not delete self node for topic: " + topic.toStringUtf8(), path, rc);
                                cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                            }
                        }
                    }, ctx);
                }
            }, ctx);
        }

        public void claim() {
            if (logger.isDebugEnabled()) {
                logger.debug("claiming topic: " + topic.toStringUtf8());
            }

            ZkUtils.createFullPathOptimistic(zk, hubPath, addr.toString().getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL, new SafeAsyncZKCallback.StringCallback() {

                        @Override
                        public void safeProcessResult(int rc, String path, Object ctx, String name) {
                            if (rc == Code.OK.intValue()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("claimed topic: " + topic.toStringUtf8());
                                }
                                notifyListenersAndAddToOwnedTopics(topic, cb, ctx);
                                updateLoadInformation();
                            } else if (rc == Code.NODEEXISTS.intValue()) {
                                read();
                            } else {
                                KeeperException e = ZkUtils.logErrorAndCreateZKException(
                                        "Failed to create ephemeral node to claim ownership of topic: "
                                                + topic.toStringUtf8(), path, rc);
                                cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                            }
                        }
                    }, ctx);
        }

    }

    byte[] getCurrentLoadData() {
        // For now, using the number of topics as an indicator of load
        // information
        return (topics.size() + "").getBytes();
    }

    void updateLoadInformation() {
    	byte[] currentLoad = getCurrentLoadData();
    	if (logger.isDebugEnabled()){
    		logger.debug("Reporting load of " + new String(currentLoad));
    	}
        zk.setData(ephemeralNodePath, currentLoad, -1, loadReportingStatCallback, null);
    }

    @Override
    protected void postReleaseCleanup(final ByteString topic, final Callback<Void> cb, Object ctx) {

        zk.getData(hubPath(topic), false, new SafeAsyncZKCallback.DataCallback() {
            @Override
            public void safeProcessResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
                if (rc == Code.NONODE.intValue()) {
                    // Node has somehow disappeared from under us, live with it
                    // since its a transient node
                    logger.warn("While deleting self-node for topic: " + topic.toStringUtf8() + ", node not found");
                    cb.operationFinished(ctx, null);
                    return;
                }

                if (rc != Code.OK.intValue()) {
                    KeeperException e = ZkUtils.logErrorAndCreateZKException(
                            "Failed to delete self-ownership node for topic: " + topic.toStringUtf8(), path, rc);
                    cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                    return;
                }

                HedwigSocketAddress owner = new HedwigSocketAddress(new String(data));
                if (!owner.equals(addr)) {
                    logger.warn("Wanted to delete self-node for topic: " + topic.toStringUtf8() + " but node for "
                            + owner + " found, leaving untouched");
                    // Not our node, someone else's, leave it alone
                    cb.operationFinished(ctx, null);
                    return;
                }

                zk.delete(path, stat.getVersion(), new SafeAsyncZKCallback.VoidCallback() {
                    @Override
                    public void safeProcessResult(int rc, String path, Object ctx) {
                        if (rc != Code.OK.intValue() && rc != Code.NONODE.intValue()) {
                            KeeperException e = ZkUtils
                                    .logErrorAndCreateZKException("Failed to delete self-ownership node for topic: "
                                            + topic.toStringUtf8(), path, rc);
                            cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                            return;
                        }

                        cb.operationFinished(ctx, null);
                    }
                }, ctx);
            }
        }, ctx);
    }

}
