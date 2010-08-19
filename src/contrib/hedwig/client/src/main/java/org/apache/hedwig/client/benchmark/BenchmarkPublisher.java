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

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.benchmark.BenchmarkUtils.BenchmarkCallback;
import org.apache.hedwig.client.benchmark.BenchmarkUtils.ThroughputLatencyAggregator;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.util.Callback;

public class BenchmarkPublisher extends BenchmarkWorker {
    Publisher publisher;
    Subscriber subscriber;
    int msgSize;
    int nParallel;
    double rate;

    public BenchmarkPublisher(int numTopics, int numMessages, int numRegions, int startTopicLabel, int partitionIndex,
            int numPartitions, Publisher publisher, Subscriber subscriber, int msgSize, int nParallel, int rate) {
        super(numTopics, numMessages, numRegions, startTopicLabel, partitionIndex, numPartitions);
        this.publisher = publisher;
        this.msgSize = msgSize;
        this.subscriber = subscriber;
        this.nParallel = nParallel;

        this.rate = rate / (numRegions * numPartitions + 0.0);
    }

    public void warmup(int nWarmup) throws Exception {
        ByteString topic = ByteString.copyFromUtf8("warmup" + partitionIndex);
        ByteString subId = ByteString.copyFromUtf8("sub");
        subscriber.subscribe(topic, subId, CreateOrAttach.CREATE_OR_ATTACH);

        subscriber.startDelivery(topic, subId, new MessageHandler() {
            @Override
            public void consume(ByteString topic, ByteString subscriberId, Message msg, Callback<Void> callback,
                    Object context) {
                // noop
                callback.operationFinished(context, null);
            }
        });

        // picking constants arbitarily for warmup phase
        ThroughputLatencyAggregator agg = new ThroughputLatencyAggregator("acked pubs", nWarmup, 100);
        Message msg = getMsg(1024);
        for (int i = 0; i < nWarmup; i++) {
            publisher.asyncPublish(topic, msg, new BenchmarkCallback(agg), null);
        }

        if (agg.tpAgg.queue.take() > 0) {
            throw new RuntimeException("Warmup publishes failed!");
        }

    }

    public Message getMsg(int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            sb.append('a');
        }
        final ByteString body = ByteString.copyFromUtf8(sb.toString());
        Message msg = Message.newBuilder().setBody(body).build();
        return msg;
    }

    public Void call() throws Exception {
        Message msg = getMsg(msgSize);

        // Single warmup for every topic
        int myPublishCount = 0;
        for (int i = 0; i < numTopics; i++) {
            if (!HedwigBenchmark.amIResponsibleForTopic(startTopicLabel + i, partitionIndex, numPartitions)){
                continue;
            }
            ByteString topic = ByteString.copyFromUtf8(HedwigBenchmark.TOPIC_PREFIX + (startTopicLabel + i));
            publisher.publish(topic, msg);
            myPublishCount++;
        }

        long startTime = System.currentTimeMillis();
        int myPublishLimit = numMessages / numRegions / numPartitions - myPublishCount;
        myPublishCount = 0;
        ThroughputLatencyAggregator agg = new ThroughputLatencyAggregator("acked pubs", myPublishLimit, nParallel);

        int topicLabel = 0;

        while (myPublishCount < myPublishLimit) {
            int topicNum = startTopicLabel + topicLabel;
            topicLabel = (topicLabel + 1) % numTopics;

            if (!HedwigBenchmark.amIResponsibleForTopic(topicNum, partitionIndex, numPartitions)) {
                continue;
            }

            ByteString topic = ByteString.copyFromUtf8(HedwigBenchmark.TOPIC_PREFIX + topicNum);

            if (rate > 0) {
                long delay = startTime + (long) (1000 * myPublishCount / rate) - System.currentTimeMillis();
                if (delay > 0)
                    Thread.sleep(delay);
            }
            publisher.asyncPublish(topic, msg, new BenchmarkCallback(agg), null);
            myPublishCount++;
        }

        System.out.println("Finished unacked pubs: tput = " + BenchmarkUtils.calcTp(myPublishLimit, startTime)
                + " ops/s");
        // Wait till the benchmark test has completed 
        agg.tpAgg.queue.take();
        System.out.println(agg.summarize(startTime));
        return null;
    }

}
