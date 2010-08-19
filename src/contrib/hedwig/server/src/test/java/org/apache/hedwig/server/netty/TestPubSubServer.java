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
package org.apache.hedwig.server.netty;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigPublisher;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.PubSubServerStandAloneTestBase;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.topics.AbstractTopicManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.HedwigSocketAddress;
import org.apache.hedwig.zookeeper.SafeAsyncZKCallback;

public class TestPubSubServer extends PubSubServerStandAloneTestBase {

    @Test
    public void testSecondServer() throws Exception {
        PubSubServer server1 = new PubSubServer(new StandAloneServerConfiguration() {
            @Override
            public int getServerPort() {
                return super.getServerPort() + 1;
            }
        });
        server1.shutdown();
    }

    class RecordingUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        SynchronousQueue<Throwable> queue;

        public RecordingUncaughtExceptionHandler(SynchronousQueue<Throwable> queue) {
            this.queue = queue;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            queue.add(e);
        }

    }

    private interface TopicManagerInstantiator {
        public TopicManager instantiateTopicManager() throws IOException;
    }

    PubSubServer startServer(final UncaughtExceptionHandler uncaughtExceptionHandler, final int port,
            final TopicManagerInstantiator instantiator) throws Exception {
        PubSubServer server = new PubSubServer(new StandAloneServerConfiguration() {
            @Override
            public int getServerPort() {
                return port;
            }

        }, uncaughtExceptionHandler) {

            @Override
            protected TopicManager instantiateTopicManager() throws IOException {
                return instantiator.instantiateTopicManager();
            }
        };

        return server;

    }

    public void runPublishRequest(final int port) throws Exception {
        HedwigPublisher publisher = new HedwigClient(new ClientConfiguration() {
            @Override
            public InetSocketAddress getDefaultServerHost() {
                return new InetSocketAddress("localhost", port);
            }
        }).getPublisher();

        publisher.asyncPublish(ByteString.copyFromUtf8("blah"), Message.newBuilder().setBody(
                ByteString.copyFromUtf8("blah")).build(), new Callback<Void>() {
            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                assertTrue(false);
            }

            @Override
            public void operationFinished(Object ctx, Void resultOfOperation) {
                assertTrue(false);
            }

        }, null);
    }

    @Test
    public void testUncaughtExceptionInNettyThread() throws Exception {

        SynchronousQueue<Throwable> queue = new SynchronousQueue<Throwable>();
        RecordingUncaughtExceptionHandler uncaughtExceptionHandler = new RecordingUncaughtExceptionHandler(queue);
        final int port = 9876;

        PubSubServer server = startServer(uncaughtExceptionHandler, port, new TopicManagerInstantiator() {

            @Override
            public TopicManager instantiateTopicManager() throws IOException {
                return new AbstractTopicManager(new ServerConfiguration(), Executors.newSingleThreadScheduledExecutor()) {
                    @Override
                    protected void realGetOwner(ByteString topic, boolean shouldClaim, 
                            Callback<HedwigSocketAddress> cb, Object ctx) {
                        throw new RuntimeException("this exception should be uncaught");
                    }

                    @Override
                    protected void postReleaseCleanup(ByteString topic, Callback<Void> cb, Object ctx) {
                    }
                };
            }
        });

        runPublishRequest(port);
        assertEquals(RuntimeException.class, queue.take().getClass());
        server.shutdown();
    }

    @Test
    public void testUncaughtExceptionInZKThread() throws Exception {

        SynchronousQueue<Throwable> queue = new SynchronousQueue<Throwable>();
        RecordingUncaughtExceptionHandler uncaughtExceptionHandler = new RecordingUncaughtExceptionHandler(queue);
        final int port = 9876;
        final String hostPort = "127.0.0.1:33221";

        PubSubServer server = startServer(uncaughtExceptionHandler, port, new TopicManagerInstantiator() {

            @Override
            public TopicManager instantiateTopicManager() throws IOException {
                return new AbstractTopicManager(new ServerConfiguration(), Executors.newSingleThreadScheduledExecutor()) {

                    @Override
                    protected void realGetOwner(ByteString topic, boolean shouldClaim, 
                            Callback<HedwigSocketAddress> cb, Object ctx) {
                        ZooKeeper zookeeper;
                        try {
                            zookeeper = new ZooKeeper(hostPort, 60000, new Watcher() {
                                @Override
                                public void process(WatchedEvent event) {
                                    // TODO Auto-generated method stub

                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }

                        zookeeper.getData("/fake", false, new SafeAsyncZKCallback.DataCallback() {
                            @Override
                            public void safeProcessResult(int rc, String path, Object ctx, byte[] data,
                                    org.apache.zookeeper.data.Stat stat) {
                                throw new RuntimeException("This should go to the uncaught exception handler");
                            }

                        }, null);
                    }

                    @Override
                    protected void postReleaseCleanup(ByteString topic, Callback<Void> cb, Object ctx) {
                    }
                };
            }
        });

        runPublishRequest(port);
        assertEquals(RuntimeException.class, queue.take().getClass());
        server.shutdown();
    }

    @Test
    public void testInvalidServerConfiguration() throws Exception {
        boolean success = false;
        ServerConfiguration conf = new ServerConfiguration() {
            @Override
            public boolean isInterRegionSSLEnabled() {
                return conf.getBoolean(INTER_REGION_SSL_ENABLED, true);
            }

            @Override
            public List<String> getRegions() {
                List<String> regionsList = new LinkedList<String>();
                regionsList.add("regionHost1:4080:9876"); 
                regionsList.add("regionHost2:4080"); 
                regionsList.add("regionHost3:4080:9876");
                return regionsList;
            }
        };
        try {
            conf.validate();
        }
        catch (ConfigurationException e) {
            logger.error("Invalid configuration: ", e);
            success = true;
        }
        assertTrue(success);
    }    

    @Test
    public void testValidServerConfiguration() throws Exception {
        boolean success = true;
        ServerConfiguration conf = new ServerConfiguration() {
            @Override
            public boolean isInterRegionSSLEnabled() {
                return conf.getBoolean(INTER_REGION_SSL_ENABLED, true);
            }

            @Override
            public List<String> getRegions() {
                List<String> regionsList = new LinkedList<String>();
                regionsList.add("regionHost1:4080:9876"); 
                regionsList.add("regionHost2:4080:2938"); 
                regionsList.add("regionHost3:4080:9876");
                return regionsList;
            }
        };
        try {
            conf.validate();
        }
        catch (ConfigurationException e) {
            logger.error("Invalid configuration: ", e);
            success = false;
        }
        assertTrue(success);
    }    

}
