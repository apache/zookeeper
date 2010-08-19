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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.CompositeException;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.Either;
import org.apache.hedwig.util.HedwigSocketAddress;
import org.apache.hedwig.util.Pair;
import org.apache.hedwig.zookeeper.ZooKeeperTestBase;

public class TestZkTopicManager extends ZooKeeperTestBase {

    protected ZkTopicManager tm;

    protected class CallbackQueue<T> implements Callback<T> {
        SynchronousQueue<Either<T, Exception>> q = new SynchronousQueue<Either<T, Exception>>();

        public SynchronousQueue<Either<T, Exception>> getQueue() {
            return q;
        }

        public Either<T, Exception> take() throws InterruptedException {
            return q.take();
        }

        @Override
        public void operationFailed(Object ctx, final PubSubException exception) {
            LOG.error("got exception: " + exception);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConcurrencyUtils.put(q, Either.of((T) null, (Exception) exception));
                }
            }).start();
        }

        @Override
        public void operationFinished(Object ctx, final T resultOfOperation) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConcurrencyUtils.put(q, Either.of(resultOfOperation, (Exception) null));
                }
            }).start();
        }
    }

    protected CallbackQueue<HedwigSocketAddress> addrCbq = new CallbackQueue<HedwigSocketAddress>();
    protected CallbackQueue<ByteString> bsCbq = new CallbackQueue<ByteString>();
    protected CallbackQueue<Void> voidCbq = new CallbackQueue<Void>();

    protected ByteString topic = ByteString.copyFromUtf8("topic");
    protected ServerConfiguration cfg;
    protected HedwigSocketAddress me;
    protected ScheduledExecutorService scheduler;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        cfg = new ServerConfiguration();
        me = cfg.getServerAddr();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        tm = new ZkTopicManager(zk, cfg, scheduler);
    }

    @Test
    public void testGetOwnerSingle() throws Exception {
        tm.getOwner(topic, false, addrCbq, null);
        Assert.assertEquals(me, check(addrCbq.take()));
    }

    protected ByteString mkTopic(int i) {
        return ByteString.copyFromUtf8(topic.toStringUtf8() + i);
    }

    protected <T> T check(Either<T, Exception> ex) throws Exception {
        if (ex.left() == null)
            throw ex.right();
        else
            return ex.left();
    }

    public static class CustomServerConfiguration extends ServerConfiguration {
        int port;

        public CustomServerConfiguration(int port) {
            this.port = port;
        }

        @Override
        public int getServerPort() {
            return port;
        }
    }

    @Test
    public void testGetOwnerMulti() throws Exception {
        ServerConfiguration cfg1 = new CustomServerConfiguration(cfg.getServerPort() + 1), cfg2 = new CustomServerConfiguration(
                cfg.getServerPort() + 2);
        // TODO change cfg1 cfg2 params
        ZkTopicManager tm1 = new ZkTopicManager(zk, cfg1, scheduler), tm2 = new ZkTopicManager(zk, cfg2, scheduler);

        tm.getOwner(topic, false, addrCbq, null);
        HedwigSocketAddress owner = check(addrCbq.take());

        // If we were told to have another person claim the topic, make them
        // claim the topic.
        if (owner.getPort() == cfg1.getServerPort())
            tm1.getOwner(topic, true, addrCbq, null);
        else if (owner.getPort() == cfg2.getServerPort())
            tm2.getOwner(topic, true, addrCbq, null);
        if (owner.getPort() != cfg.getServerPort())
            Assert.assertEquals(owner, check(addrCbq.take()));

        for (int i = 0; i < 100; ++i) {
            tm.getOwner(topic, false, addrCbq, null);
            Assert.assertEquals(owner, check(addrCbq.take()));

            tm1.getOwner(topic, false, addrCbq, null);
            Assert.assertEquals(owner, check(addrCbq.take()));

            tm2.getOwner(topic, false, addrCbq, null);
            Assert.assertEquals(owner, check(addrCbq.take()));
        }

        // Give us 100 chances to choose another owner if not shouldClaim.
        for (int i = 0; i < 100; ++i) {
            if (!owner.equals(me))
                break;
            tm.getOwner(mkTopic(i), false, addrCbq, null);
            owner = check(addrCbq.take());
            if (i == 99)
                Assert.fail("Never chose another owner");
        }

        // Make sure we always choose ourselves if shouldClaim.
        for (int i = 0; i < 100; ++i) {
            tm.getOwner(mkTopic(100), true, addrCbq, null);
            Assert.assertEquals(me, check(addrCbq.take()));
        }
    }

    @Test
    public void testLoadBalancing() throws Exception {
        tm.getOwner(topic, false, addrCbq, null);

        Assert.assertEquals(me, check(addrCbq.take()));

        ServerConfiguration cfg1 = new CustomServerConfiguration(cfg.getServerPort() + 1);
        new ZkTopicManager(zk, cfg1, scheduler);

        ByteString topic1 = mkTopic(1);
        tm.getOwner(topic1, false, addrCbq, null);
        Assert.assertEquals(cfg1.getServerAddr(), check(addrCbq.take()));

    }

    class StubOwnershipChangeListener implements TopicOwnershipChangeListener {
        boolean failure;
        SynchronousQueue<Pair<ByteString, Boolean>> bsQueue;

        public StubOwnershipChangeListener(SynchronousQueue<Pair<ByteString, Boolean>> bsQueue) {
            this.bsQueue = bsQueue;
        }

        public void setFailure(boolean failure) {
            this.failure = failure;
        }

        @Override
        public void lostTopic(final ByteString topic) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConcurrencyUtils.put(bsQueue, Pair.of(topic, false));
                }
            }).start();
        }

        public void acquiredTopic(final ByteString topic, final Callback<Void> callback, final Object ctx) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConcurrencyUtils.put(bsQueue, Pair.of(topic, true));
                    if (failure) {
                        callback.operationFailed(ctx, new PubSubException.ServiceDownException("Asked to fail"));
                    } else {
                        callback.operationFinished(ctx, null);
                    }
                }
            }).start();
        }
    }

    @Test
    public void testOwnershipChange() throws Exception {
        SynchronousQueue<Pair<ByteString, Boolean>> bsQueue = new SynchronousQueue<Pair<ByteString, Boolean>>();

        StubOwnershipChangeListener listener = new StubOwnershipChangeListener(bsQueue);

        tm.addTopicOwnershipChangeListener(listener);

        // regular acquire
        tm.getOwner(topic, true, addrCbq, null);
        Pair<ByteString, Boolean> pair = bsQueue.take();
        Assert.assertEquals(topic, pair.first());
        Assert.assertTrue(pair.second());
        Assert.assertEquals(me, check(addrCbq.take()));
        assertOwnershipNodeExists();

        // topic that I already own
        tm.getOwner(topic, true, addrCbq, null);
        Assert.assertEquals(me, check(addrCbq.take()));
        Assert.assertTrue(bsQueue.isEmpty());
        assertOwnershipNodeExists();

        // regular release
        tm.releaseTopic(topic, cb, null);
        pair = bsQueue.take();
        Assert.assertEquals(topic, pair.first());
        Assert.assertFalse(pair.second());
        Assert.assertTrue(queue.take());
        assertOwnershipNodeDoesntExist();

        // releasing topic that I don't own
        tm.releaseTopic(mkTopic(0), cb, null);
        Assert.assertTrue(queue.take());
        Assert.assertTrue(bsQueue.isEmpty());

        // set listener to return error
        listener.setFailure(true);

        tm.getOwner(topic, true, addrCbq, null);
        pair = bsQueue.take();
        Assert.assertEquals(topic, pair.first());
        Assert.assertTrue(pair.second());
        Assert.assertEquals(PubSubException.ServiceDownException.class, ((CompositeException) addrCbq.take().right())
                .getExceptions().iterator().next().getClass());
        Assert.assertFalse(tm.topics.contains(topic));
        Thread.sleep(100);
        assertOwnershipNodeDoesntExist();

    }

    public void assertOwnershipNodeExists() throws Exception {
        byte[] data = zk.getData(tm.hubPath(topic), false, null);
        Assert.assertEquals(new HedwigSocketAddress(new String(data)), tm.addr);
    }

    public void assertOwnershipNodeDoesntExist() throws Exception {
        try {
            zk.getData(tm.hubPath(topic), false, null);
            Assert.assertTrue(false);
        } catch (KeeperException e) {
            Assert.assertEquals(e.code(), KeeperException.Code.NONODE);
        }
    }

    @Test
    public void testZKClientDisconnected() throws Exception {
        // First assert ownership of the topic
        tm.getOwner(topic, true, addrCbq, null);
        Assert.assertEquals(me, check(addrCbq.take()));

        // Suspend the ZKTopicManager and make sure calls to getOwner error out
        tm.isSuspended = true;
        tm.getOwner(topic, true, addrCbq, null);
        Assert.assertEquals(PubSubException.ServiceDownException.class, addrCbq.take().right().getClass());
        // Release the topic. This should not error out even if suspended.
        tm.releaseTopic(topic, cb, null);
        Assert.assertTrue(queue.take());
        assertOwnershipNodeDoesntExist();

        // Restart the ZKTopicManager and make sure calls to getOwner are okay
        tm.isSuspended = false;
        tm.getOwner(topic, true, addrCbq, null);
        Assert.assertEquals(me, check(addrCbq.take()));
        assertOwnershipNodeExists();
    }

}
