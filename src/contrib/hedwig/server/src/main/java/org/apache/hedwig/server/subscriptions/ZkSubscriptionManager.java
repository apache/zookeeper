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
package org.apache.hedwig.server.subscriptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionState;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.zookeeper.SafeAsyncZKCallback;
import org.apache.hedwig.zookeeper.ZkUtils;

public class ZkSubscriptionManager extends AbstractSubscriptionManager {

    ZooKeeper zk;

    protected final static Logger logger = Logger.getLogger(ZkSubscriptionManager.class);

    public ZkSubscriptionManager(ZooKeeper zk, TopicManager topicMgr, PersistenceManager pm, ServerConfiguration cfg,
            ScheduledExecutorService scheduler) {
        super(cfg, topicMgr, pm, scheduler);
        this.zk = zk;
    }

    private StringBuilder topicSubscribersPath(StringBuilder sb, ByteString topic) {
        return cfg.getZkTopicPath(sb, topic).append("/subscribers");
    }

    private String topicSubscriberPath(ByteString topic, ByteString subscriber) {
        return topicSubscribersPath(new StringBuilder(), topic).append("/").append(subscriber.toStringUtf8())
                .toString();
    }

    @Override
    protected void readSubscriptions(final ByteString topic,
            final Callback<Map<ByteString, InMemorySubscriptionState>> cb, final Object ctx) {

        String topicSubscribersPath = topicSubscribersPath(new StringBuilder(), topic).toString();
        zk.getChildren(topicSubscribersPath, false, new SafeAsyncZKCallback.ChildrenCallback() {
            @Override
            public void safeProcessResult(int rc, String path, final Object ctx, final List<String> children) {

                if (rc != Code.OK.intValue() && rc != Code.NONODE.intValue()) {
                    KeeperException e = ZkUtils.logErrorAndCreateZKException("Could not read subscribers for topic "
                            + topic.toStringUtf8(), path, rc);
                    cb.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                    return;
                }

                final Map<ByteString, InMemorySubscriptionState> topicSubs = new ConcurrentHashMap<ByteString, InMemorySubscriptionState>();

                if (rc == Code.NONODE.intValue() || children.size() == 0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("No subscriptions found while acquiring topic: " + topic.toStringUtf8());
                    }
                    cb.operationFinished(ctx, topicSubs);
                    return;
                }

                final AtomicBoolean failed = new AtomicBoolean();
                final AtomicInteger count = new AtomicInteger();

                for (final String child : children) {

                    final ByteString subscriberId = ByteString.copyFromUtf8(child);
                    final String childPath = path + "/" + child;

                    zk.getData(childPath, false, new SafeAsyncZKCallback.DataCallback() {
                        @Override
                        public void safeProcessResult(int rc, String path, Object ctx, byte[] data, Stat stat) {

                            if (rc != Code.OK.intValue()) {
                                KeeperException e = ZkUtils.logErrorAndCreateZKException(
                                        "Could not read subscription data for topic: " + topic.toStringUtf8()
                                                + ", subscriberId: " + subscriberId.toStringUtf8(), path, rc);
                                reportFailure(new PubSubException.ServiceDownException(e));
                                return;
                            }

                            if (failed.get()) {
                                return;
                            }

                            SubscriptionState state;

                            try {
                                state = SubscriptionState.parseFrom(data);
                            } catch (InvalidProtocolBufferException ex) {
                                String msg = "Failed to deserialize state for topic: " + topic.toStringUtf8()
                                        + " subscriberId: " + subscriberId.toStringUtf8();
                                logger.error(msg, ex);
                                reportFailure(new PubSubException.UnexpectedConditionException(msg));
                                return;
                            }

                            if (logger.isDebugEnabled()) {
                                logger.debug("Found subscription while acquiring topic: " + topic.toStringUtf8()
                                        + " subscriberId: " + child + "state: "
                                        + SubscriptionStateUtils.toString(state));
                            }

                            topicSubs.put(subscriberId, new InMemorySubscriptionState(state));
                            if (count.incrementAndGet() == children.size()) {
                                assert topicSubs.size() == count.get();
                                cb.operationFinished(ctx, topicSubs);
                            }
                        }

                        private void reportFailure(PubSubException e) {
                            if (failed.compareAndSet(false, true))
                                cb.operationFailed(ctx, e);
                        }
                    }, ctx);
                }
            }
        }, ctx);
    }

    @Override
    protected void createSubscriptionState(final ByteString topic, final ByteString subscriberId,
            final SubscriptionState state, final Callback<Void> callback, final Object ctx) {
        ZkUtils.createFullPathOptimistic(zk, topicSubscriberPath(topic, subscriberId), state.toByteArray(),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, new SafeAsyncZKCallback.StringCallback() {

                    @Override
                    public void safeProcessResult(int rc, String path, Object ctx, String name) {
                        if (rc == Code.OK.intValue()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Successfully recorded subscription for topic: " + topic.toStringUtf8()
                                        + " subscriberId: " + subscriberId.toStringUtf8() + " state: "
                                        + SubscriptionStateUtils.toString(state));
                            }
                            callback.operationFinished(ctx, null);
                        } else {
                            KeeperException ke = ZkUtils.logErrorAndCreateZKException(
                                    "Could not record new subscription for topic: " + topic.toStringUtf8()
                                            + " subscriberId: " + subscriberId.toStringUtf8(), path, rc);
                            callback.operationFailed(ctx, new PubSubException.ServiceDownException(ke));
                        }
                    }
                }, ctx);
    }

    @Override
    protected void updateSubscriptionState(final ByteString topic, final ByteString subscriberId,
            final SubscriptionState state, final Callback<Void> callback, final Object ctx) {
        zk.setData(topicSubscriberPath(topic, subscriberId), state.toByteArray(), -1,
                new SafeAsyncZKCallback.StatCallback() {
                    @Override
                    public void safeProcessResult(int rc, String path, Object ctx, Stat stat) {
                        if (rc != Code.OK.intValue()) {
                            KeeperException e = ZkUtils.logErrorAndCreateZKException("Topic: " + topic.toStringUtf8()
                                    + " subscriberId: " + subscriberId.toStringUtf8()
                                    + " could not set subscription state: " + SubscriptionStateUtils.toString(state),
                                    path, rc);
                            callback.operationFailed(ctx, new PubSubException.ServiceDownException(e));
                        } else {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Successfully updated subscription for topic: " + topic.toStringUtf8()
                                        + " subscriberId: " + subscriberId.toStringUtf8() + " state: "
                                        + SubscriptionStateUtils.toString(state));
                            }

                            callback.operationFinished(ctx, null);
                        }
                    }
                }, ctx);
    }

    @Override
    protected void deleteSubscriptionState(final ByteString topic, final ByteString subscriberId,
            final Callback<Void> callback, final Object ctx) {
        zk.delete(topicSubscriberPath(topic, subscriberId), -1, new SafeAsyncZKCallback.VoidCallback() {
            @Override
            public void safeProcessResult(int rc, String path, Object ctx) {
                if (rc == Code.OK.intValue()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Successfully deleted subscription for topic: " + topic.toStringUtf8()
                                + " subscriberId: " + subscriberId.toStringUtf8());
                    }

                    callback.operationFinished(ctx, null);
                    return;
                }

                KeeperException e = ZkUtils.logErrorAndCreateZKException("Topic: " + topic.toStringUtf8()
                        + " subscriberId: " + subscriberId.toStringUtf8() + " failed to delete subscription", path, rc);
                callback.operationFailed(ctx, new PubSubException.ServiceDownException(e));
            }
        }, ctx);
    }

}
