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
package org.apache.hedwig.client.benchmark;

import java.io.File;
import java.util.concurrent.Callable;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.log4j.Logger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Log4JLoggerFactory;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigPublisher;
import org.apache.hedwig.client.netty.HedwigSubscriber;

public class HedwigBenchmark implements Callable<Void> {
    protected static final Logger logger = Logger.getLogger(HedwigBenchmark.class);

    static final String TOPIC_PREFIX = "topic-";

    private final HedwigClient client;
    private final HedwigPublisher publisher;
    private final HedwigSubscriber subscriber;

    public HedwigBenchmark(ClientConfiguration cfg) {
        client = new HedwigClient(cfg);
        publisher = client.getPublisher();
        subscriber = client.getSubscriber();
    }

    static boolean amIResponsibleForTopic(int topicNum, int partitionIndex, int numPartitions) {
        return topicNum % numPartitions == partitionIndex;
    }

    @Override
    public Void call() throws Exception {

        //
        // Parameters.
        //

        // What program to run: pub, sub (subscription benchmark), recv.
        final String mode = System.getProperty("mode","");

        // Number of requests to make (publishes or subscribes).
        int numTopics = Integer.getInteger("nTopics", 50);
        int numMessages = Integer.getInteger("nMsgs", 1000);
        int numRegions = Integer.getInteger("nRegions", 1);
        int startTopicLabel = Integer.getInteger("startTopicLabel", 0);
        int partitionIndex = Integer.getInteger("partitionIndex", 0);
        int numPartitions = Integer.getInteger("nPartitions", 1);

        int replicaIndex = Integer.getInteger("replicaIndex", 0);

        int rate = Integer.getInteger("rate", 0);
        int nParallel = Integer.getInteger("npar", 100);
        int msgSize = Integer.getInteger("msgSize", 1024);

        // Number of warmup subscriptions to make.
        final int nWarmups = Integer.getInteger("nwarmups", 1000);

        if (mode.equals("sub")) {
            BenchmarkSubscriber benchmarkSub = new BenchmarkSubscriber(numTopics, 0, 1, startTopicLabel, 0, 1,
                    subscriber, ByteString.copyFromUtf8("mySub"));

            benchmarkSub.warmup(nWarmups);
            benchmarkSub.call();

        } else if (mode.equals("recv")) {

            BenchmarkSubscriber benchmarkSub = new BenchmarkSubscriber(numTopics, numMessages, numRegions,
                    startTopicLabel, partitionIndex, numPartitions, subscriber, ByteString.copyFromUtf8("sub-"
                            + replicaIndex));

            benchmarkSub.call();

        } else if (mode.equals("pub")) {
            // Offered load in msgs/second.
            BenchmarkPublisher benchmarkPub = new BenchmarkPublisher(numTopics, numMessages, numRegions,
                    startTopicLabel, partitionIndex, numPartitions, publisher, subscriber, msgSize, nParallel, rate);
            benchmarkPub.warmup(nWarmups);
            benchmarkPub.call();
            
        } else {
            throw new Exception("unknown mode: " + mode);
        }

        return null;
    }

    public static void main(String[] args) throws Exception {
        ClientConfiguration cfg = new ClientConfiguration();
        if (args.length > 0) {
            String confFile = args[0];
            try {
                cfg.loadConf(new File(confFile).toURI().toURL());
            } catch (ConfigurationException e) {
                throw new RuntimeException(e);
            }
        }

        InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());

        HedwigBenchmark app = new HedwigBenchmark(cfg);
        app.call();
        System.exit(0);
    }

}
