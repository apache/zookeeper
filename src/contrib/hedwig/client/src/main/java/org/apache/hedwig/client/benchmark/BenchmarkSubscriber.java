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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.benchmark.BenchmarkUtils.BenchmarkCallback;
import org.apache.hedwig.client.benchmark.BenchmarkUtils.ThroughputAggregator;
import org.apache.hedwig.client.benchmark.BenchmarkUtils.ThroughputLatencyAggregator;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.RegionSpecificSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.util.Callback;

public class BenchmarkSubscriber extends BenchmarkWorker implements Callable<Void>{
    static final Logger logger = Logger.getLogger(BenchmarkSubscriber.class);
    Subscriber subscriber;
    ByteString subId;
    

    public BenchmarkSubscriber(int numTopics, int numMessages, int numRegions,
            int startTopicLabel, int partitionIndex, int numPartitions, Subscriber subscriber, ByteString subId) {
        super(numTopics, numMessages, numRegions, startTopicLabel, partitionIndex, numPartitions);
        this.subscriber = subscriber;
        this.subId = subId;        
    }

    public void warmup(int numWarmup) throws InterruptedException {
        /*
         * multiplying the number of ops by numParitions because we end up
         * skipping many because of the partitioning logic
         */
        multiSub("warmup", "warmup", 0, numWarmup, numWarmup * numPartitions);
    }

    public Void call() throws Exception {

        final ThroughputAggregator agg = new ThroughputAggregator("recvs", numMessages);
        final Map<String, Long> lastSeqIdSeenMap = new HashMap<String, Long>();

        for (int i = startTopicLabel; i < startTopicLabel + numTopics; i++) {

            if (!HedwigBenchmark.amIResponsibleForTopic(i, partitionIndex, numPartitions)) {
                continue;
            }

            final String topic = HedwigBenchmark.TOPIC_PREFIX + i;

            subscriber.subscribe(ByteString.copyFromUtf8(topic), subId, CreateOrAttach.CREATE_OR_ATTACH);
            subscriber.startDelivery(ByteString.copyFromUtf8(topic), subId, new MessageHandler() {

                @Override
                public void consume(ByteString thisTopic, ByteString subscriberId, Message msg,
                        Callback<Void> callback, Object context) {
                    if (logger.isDebugEnabled())
                        logger.debug("Got message from src-region: " + msg.getSrcRegion() + " with seq-id: "
                                + msg.getMsgId());

                    String mapKey = topic + msg.getSrcRegion().toStringUtf8();
                    Long lastSeqIdSeen = lastSeqIdSeenMap.get(mapKey);
                    if (lastSeqIdSeen == null) {
                        lastSeqIdSeen = (long) 0;
                    }

                    if (getSrcSeqId(msg) <= lastSeqIdSeen) {
                        logger.info("Redelivery of message, src-region: " + msg.getSrcRegion() + "seq-id: "
                                + msg.getMsgId());
                    } else {
                        agg.ding(false);
                    }

                    callback.operationFinished(context, null);
                }
            });
        }
        System.out.println("Finished subscribing to topics and now waiting for messages to come in...");
        // Wait till the benchmark test has completed
        agg.queue.take();            
        System.out.println(agg.summarize(agg.earliest.get()));
        return null;
    }

    long getSrcSeqId(Message msg) {
        if (msg.getMsgId().getRemoteComponentsCount() == 0) {
            return msg.getMsgId().getLocalComponent();
        }

        for (RegionSpecificSeqId rseqId : msg.getMsgId().getRemoteComponentsList()) {
            if (rseqId.getRegion().equals(msg.getSrcRegion()))
                return rseqId.getSeqId();
        }

        return msg.getMsgId().getLocalComponent();
    }

    void multiSub(String label, String topicPrefix, int start, final int npar, final int count)
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        ThroughputLatencyAggregator agg = new ThroughputLatencyAggregator(label, count / numPartitions, npar);
        int end = start + count;
        for (int i = start; i < end; ++i) {
            if (!HedwigBenchmark.amIResponsibleForTopic(i, partitionIndex, numPartitions)){
                continue;
            }
            subscriber.asyncSubscribe(ByteString.copyFromUtf8(topicPrefix + i), subId, CreateOrAttach.CREATE_OR_ATTACH,
                    new BenchmarkCallback(agg), null);
        }
        // Wait till the benchmark test has completed
        agg.tpAgg.queue.take();
        if (count > 1)
            System.out.println(agg.summarize(startTime));
    }

}
