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
package org.apache.hedwig.server.delivery;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.ProtocolVersion;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.common.UnexpectedError;
import org.apache.hedwig.server.persistence.Factory;
import org.apache.hedwig.server.persistence.MapMethods;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.persistence.ScanCallback;
import org.apache.hedwig.server.persistence.ScanRequest;
import org.apache.hedwig.server.subscriptions.MessageFilter;

public class FIFODeliveryManager implements Runnable, DeliveryManager {

    protected static final Logger logger = Logger.getLogger(FIFODeliveryManager.class);

    protected interface DeliveryManagerRequest {
        public void performRequest();
    }

    /**
     * the main queue that the single-threaded delivery manager works off of
     */
    BlockingQueue<DeliveryManagerRequest> requestQueue = new LinkedBlockingQueue<DeliveryManagerRequest>();

    /**
     * The queue of all subscriptions that are facing a transient error either
     * in scanning from the persistence manager, or in sending to the consumer
     */
    Queue<ActiveSubscriberState> retryQueue = new ConcurrentLinkedQueue<ActiveSubscriberState>();

    /**
     * Stores a mapping from topic to the delivery pointers on the topic. The
     * delivery pointers are stored in a sorted map from seq-id to the set of
     * subscribers at that seq-id
     */
    Map<ByteString, SortedMap<Long, Set<ActiveSubscriberState>>> perTopicDeliveryPtrs;

    /**
     * Mapping from delivery end point to the subscriber state that we are
     * serving at that end point. This prevents us e.g., from serving two
     * subscriptions to the same endpoint
     */
    Map<TopicSubscriber, ActiveSubscriberState> subscriberStates;

    private PersistenceManager persistenceMgr;

    private ServerConfiguration cfg;

    // Boolean indicating if this thread should continue running. This is used
    // when we want to stop the thread during a PubSubServer shutdown.
    protected boolean keepRunning = true;

    public FIFODeliveryManager(PersistenceManager persistenceMgr, ServerConfiguration cfg) {
        this.persistenceMgr = persistenceMgr;
        perTopicDeliveryPtrs = new HashMap<ByteString, SortedMap<Long, Set<ActiveSubscriberState>>>();
        subscriberStates = new HashMap<TopicSubscriber, ActiveSubscriberState>();
        new Thread(this, "DeliveryManagerThread").start();
        this.cfg = cfg;
    }

    /**
     * ===================================================================== Our
     * usual enqueue function, stop if error because of unbounded queue, should
     * never happen
     * 
     */
    protected void enqueueWithoutFailure(DeliveryManagerRequest request) {
        if (!requestQueue.offer(request)) {
            throw new UnexpectedError("Could not enqueue object: " + request + " to delivery manager request queue.");
        }
    }

    /**
     * ====================================================================
     * Public interface of the delivery manager
     */

    /**
     * Tells the delivery manager to start sending out messages for a particular
     * subscription
     * 
     * @param topic
     * @param subscriberId
     * @param seqIdToStartFrom
     *            Message sequence-id from where delivery should be started
     * @param endPoint
     *            The delivery end point to which send messages to
     * @param filter
     *            Only messages passing this filter should be sent to this
     *            subscriber
     * @param isHubSubscriber
     *            There are some seq-id intricacies. To a hub subscriber, we
     *            should send only a subset of the seq-id vector
     */
    public void startServingSubscription(ByteString topic, ByteString subscriberId, MessageSeqId seqIdToStartFrom,
            DeliveryEndPoint endPoint, MessageFilter filter, boolean isHubSubscriber) {

        ActiveSubscriberState subscriber = new ActiveSubscriberState(topic, subscriberId, seqIdToStartFrom
                .getLocalComponent() - 1, endPoint, filter, isHubSubscriber);

        enqueueWithoutFailure(subscriber);
    }

    public void stopServingSubscriber(ByteString topic, ByteString subscriberId) {
        ActiveSubscriberState subState = subscriberStates.get(new TopicSubscriber(topic, subscriberId));

        if (subState != null) {
            stopServingSubscriber(subState);
        }
    }

    /**
     * Due to some error or disconnection or unsusbcribe, someone asks us to
     * stop serving a particular endpoint
     * 
     * @param endPoint
     */
    protected void stopServingSubscriber(ActiveSubscriberState subscriber) {
        enqueueWithoutFailure(new StopServingSubscriber(subscriber));
    }

    /**
     * Instructs the delivery manager to backoff on the given subscriber and
     * retry sending after some time
     * 
     * @param subscriber
     */

    public void retryErroredSubscriberAfterDelay(ActiveSubscriberState subscriber) {

        subscriber.setLastScanErrorTime(System.currentTimeMillis());

        if (!retryQueue.offer(subscriber)) {
            throw new UnexpectedError("Could not enqueue to delivery manager retry queue");
        }
    }

    /**
     * Instructs the delivery manager to move the delivery pointer for a given
     * subscriber
     * 
     * @param subscriber
     * @param prevSeqId
     * @param newSeqId
     */
    public void moveDeliveryPtrForward(ActiveSubscriberState subscriber, long prevSeqId, long newSeqId) {
        enqueueWithoutFailure(new DeliveryPtrMove(subscriber, prevSeqId, newSeqId));
    }

    /*
     * ==========================================================================
     * == End of public interface, internal machinery begins.
     */
    public void run() {
        while (keepRunning) {
            DeliveryManagerRequest request = null;

            try {
                // We use a timeout of 1 second, so that we can wake up once in
                // a while to check if there is something in the retry queue.
                request = requestQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // First retry any subscriptions that had failed and need a retry
            retryErroredSubscribers();

            if (request == null) {
                continue;
            }

            request.performRequest();

        }
    }

    /**
     * Stop method which will enqueue a ShutdownDeliveryManagerRequest.
     */
    public void stop() {
        enqueueWithoutFailure(new ShutdownDeliveryManagerRequest());
    }

    protected void retryErroredSubscribers() {
        long lastInterestingFailureTime = System.currentTimeMillis() - cfg.getScanBackoffPeriodMs();
        ActiveSubscriberState subscriber;

        while ((subscriber = retryQueue.peek()) != null) {
            if (subscriber.getLastScanErrorTime() > lastInterestingFailureTime) {
                // Not enough time has elapsed yet, will retry later
                // Since the queue is fifo, no need to check later items
                return;
            }

            // retry now
            subscriber.deliverNextMessage();
            retryQueue.poll();
        }
    }

    protected void removeDeliveryPtr(ActiveSubscriberState subscriber, Long seqId, boolean isAbsenceOk,
            boolean pruneTopic) {

        assert seqId != null;

        // remove this subscriber from the delivery pointers data structure
        ByteString topic = subscriber.getTopic();
        SortedMap<Long, Set<ActiveSubscriberState>> deliveryPtrs = perTopicDeliveryPtrs.get(topic);

        if (deliveryPtrs == null && !isAbsenceOk) {
            throw new UnexpectedError("No delivery pointers found while disconnecting " + "channel for topic:" + topic);
        }

        if (!MapMethods.removeFromMultiMap(deliveryPtrs, seqId, subscriber) && !isAbsenceOk) {

            throw new UnexpectedError("Could not find subscriber:" + subscriber + " at the expected delivery pointer");
        }

        if (pruneTopic && deliveryPtrs.isEmpty()) {
            perTopicDeliveryPtrs.remove(topic);
        }

    }

    protected long getMinimumSeqId(ByteString topic) {
        SortedMap<Long, Set<ActiveSubscriberState>> deliveryPtrs = perTopicDeliveryPtrs.get(topic);

        if (deliveryPtrs == null || deliveryPtrs.isEmpty()) {
            return Long.MAX_VALUE - 1;
        }
        return deliveryPtrs.firstKey();
    }

    protected void addDeliveryPtr(ActiveSubscriberState subscriber, Long seqId) {

        // If this topic doesn't exist in the per-topic delivery pointers table,
        // create an entry for it
        SortedMap<Long, Set<ActiveSubscriberState>> deliveryPtrs = MapMethods.getAfterInsertingIfAbsent(
                perTopicDeliveryPtrs, subscriber.getTopic(), TreeMapLongToSetSubscriberFactory.instance);

        MapMethods.addToMultiMap(deliveryPtrs, seqId, subscriber, HashMapSubscriberFactory.instance);
    }

    public class ActiveSubscriberState implements ScanCallback, DeliveryCallback, DeliveryManagerRequest {
        ByteString topic;
        ByteString subscriberId;
        long lastLocalSeqIdDelivered;
        boolean connected = true;
        DeliveryEndPoint deliveryEndPoint;
        long lastScanErrorTime = -1;
        long localSeqIdDeliveringNow;
        long lastSeqIdCommunicatedExternally;
        // TODO make use of these variables
        MessageFilter filter;
        boolean isHubSubscriber;
        final static int SEQ_ID_SLACK = 10;

        public ActiveSubscriberState(ByteString topic, ByteString subscriberId, long lastLocalSeqIdDelivered,
                DeliveryEndPoint deliveryEndPoint, MessageFilter filter, boolean isHubSubscriber) {
            this.topic = topic;
            this.subscriberId = subscriberId;
            this.lastLocalSeqIdDelivered = lastLocalSeqIdDelivered;
            this.deliveryEndPoint = deliveryEndPoint;
            this.filter = filter;
            this.isHubSubscriber = isHubSubscriber;
        }

        public void setNotConnected() {
            this.connected = false;
            deliveryEndPoint.close();
        }

        public ByteString getTopic() {
            return topic;
        }

        public long getLastLocalSeqIdDelivered() {
            return lastLocalSeqIdDelivered;
        }

        public long getLastScanErrorTime() {
            return lastScanErrorTime;
        }

        public void setLastScanErrorTime(long lastScanErrorTime) {
            this.lastScanErrorTime = lastScanErrorTime;
        }

        protected boolean isConnected() {
            return connected;
        }

        public void deliverNextMessage() {

            if (!isConnected()) {
                return;
            }

            localSeqIdDeliveringNow = persistenceMgr.getSeqIdAfterSkipping(topic, lastLocalSeqIdDelivered, 1);

            ScanRequest scanRequest = new ScanRequest(topic, localSeqIdDeliveringNow,
            /* callback= */this, /* ctx= */null);

            persistenceMgr.scanSingleMessage(scanRequest);
        }

        /**
         * ===============================================================
         * {@link ScanCallback} methods
         */

        public void messageScanned(Object ctx, Message message) {
            if (!connected) {
                return;
            }

            // We're using a simple all-to-all network topology, so no region
            // should ever need to forward messages to any other region.
            // Otherwise, with the current logic, messages will end up
            // ping-pong-ing back and forth between regions with subscriptions
            // to each other without termination (or in any other cyclic
            // configuration).
            if (isHubSubscriber && !message.getSrcRegion().equals(cfg.getMyRegionByteString())) {
                sendingFinished();
                return;
            }

            /**
             * The method below will invoke our sendingFinished() method when
             * done
             */
            PubSubResponse response = PubSubResponse.newBuilder().setProtocolVersion(ProtocolVersion.VERSION_ONE)
                    .setStatusCode(StatusCode.SUCCESS).setTxnId(0).setMessage(message).build();

            deliveryEndPoint.send(response, //
                    // callback =
                    this);

        }

        public void scanFailed(Object ctx, Exception exception) {
            if (!connected) {
                return;
            }

            // wait for some time and then retry
            retryErroredSubscriberAfterDelay(this);
        }

        public void scanFinished(Object ctx, ReasonForFinish reason) {
            // no-op
        }

        /**
         * ===============================================================
         * {@link DeliveryCallback} methods
         */
        public void sendingFinished() {
            if (!connected) {
                return;
            }

            lastLocalSeqIdDelivered = localSeqIdDeliveringNow;
            
            if (lastLocalSeqIdDelivered > lastSeqIdCommunicatedExternally + SEQ_ID_SLACK){
                // Note: The order of the next 2 statements is important. We should
                // submit a request to change our delivery pointer only *after* we
                // have actually changed it. Otherwise, there is a race condition
                // with removal of this channel, w.r.t, maintaining the deliveryPtrs
                // tree map.
                long prevId = lastSeqIdCommunicatedExternally;
                lastSeqIdCommunicatedExternally = lastLocalSeqIdDelivered;
                moveDeliveryPtrForward(this, prevId, lastLocalSeqIdDelivered);
            }
            deliverNextMessage();
        }
        
        public long getLastSeqIdCommunicatedExternally() {
            return lastSeqIdCommunicatedExternally;
        }
            

        public void permanentErrorOnSend() {
            stopServingSubscriber(this);
        }

        public void transientErrorOnSend() {
            retryErroredSubscriberAfterDelay(this);
        }

        /**
         * ===============================================================
         * {@link DeliveryManagerRequest} methods
         */
        public void performRequest() {

            // Put this subscriber in the channel to subscriber mapping
            ActiveSubscriberState prevSubscriber = subscriberStates.put(new TopicSubscriber(topic, subscriberId), this);

            if (prevSubscriber != null) {
                stopServingSubscriber(prevSubscriber);
            }

            lastSeqIdCommunicatedExternally = lastLocalSeqIdDelivered;
            addDeliveryPtr(this, lastLocalSeqIdDelivered);
            
            deliverNextMessage();
        };

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Topic: ");
            sb.append(topic.toStringUtf8());
            sb.append("DeliveryPtr: ");
            sb.append(lastLocalSeqIdDelivered);
            return sb.toString();

        }
    }

    protected class StopServingSubscriber implements DeliveryManagerRequest {
        ActiveSubscriberState subscriber;

        public StopServingSubscriber(ActiveSubscriberState subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void performRequest() {

            // This will automatically stop delivery, and disconnect the channel
            subscriber.setNotConnected();

            // if the subscriber has moved on, a move request for its delivery
            // pointer must be pending in the request queue. Note that the
            // subscriber first changes its delivery pointer and then submits a
            // request to move so this works.
            removeDeliveryPtr(subscriber, subscriber.getLastSeqIdCommunicatedExternally(), //
                    // isAbsenceOk=
                    true,
                    // pruneTopic=
                    true);
        }

    }

    protected class DeliveryPtrMove implements DeliveryManagerRequest {

        ActiveSubscriberState subscriber;
        Long oldSeqId;
        Long newSeqId;

        public DeliveryPtrMove(ActiveSubscriberState subscriber, Long oldSeqId, Long newSeqId) {
            this.subscriber = subscriber;
            this.oldSeqId = oldSeqId;
            this.newSeqId = newSeqId;
        }

        @Override
        public void performRequest() {
            ByteString topic = subscriber.getTopic();
            long prevMinSeqId = getMinimumSeqId(topic);

            if (subscriber.isConnected()) {
                removeDeliveryPtr(subscriber, oldSeqId, //
                        // isAbsenceOk=
                        false,
                        // pruneTopic=
                        false);

                addDeliveryPtr(subscriber, newSeqId);
            } else {
                removeDeliveryPtr(subscriber, oldSeqId, //
                        // isAbsenceOk=
                        true,
                        // pruneTopic=
                        true);
            }

            long nowMinSeqId = getMinimumSeqId(topic);

            if (nowMinSeqId > prevMinSeqId) {
                persistenceMgr.deliveredUntil(topic, nowMinSeqId);
            }
        }
    }

    protected class ShutdownDeliveryManagerRequest implements DeliveryManagerRequest {
        // This is a simple type of Request we will enqueue when the
        // PubSubServer is shut down and we want to stop the DeliveryManager
        // thread.
        public void performRequest() {
            keepRunning = false;
        }
    }

    /**
     * ====================================================================
     * 
     * Dumb factories for our map methods
     */
    protected static class TreeMapLongToSetSubscriberFactory implements
            Factory<SortedMap<Long, Set<ActiveSubscriberState>>> {
        static TreeMapLongToSetSubscriberFactory instance = new TreeMapLongToSetSubscriberFactory();

        @Override
        public SortedMap<Long, Set<ActiveSubscriberState>> newInstance() {
            return new TreeMap<Long, Set<ActiveSubscriberState>>();
        }
    }

    protected static class HashMapSubscriberFactory implements Factory<Set<ActiveSubscriberState>> {
        static HashMapSubscriberFactory instance = new HashMapSubscriberFactory();

        @Override
        public Set<ActiveSubscriberState> newInstance() {
            return new HashSet<ActiveSubscriberState>();
        }
    }

}
