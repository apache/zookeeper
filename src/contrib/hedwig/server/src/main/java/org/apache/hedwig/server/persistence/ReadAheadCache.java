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
package org.apache.hedwig.server.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ServerNotResponsibleForTopicException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protoextensions.MessageIdUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.common.UnexpectedError;
import org.apache.hedwig.util.Callback;

public class ReadAheadCache implements PersistenceManager, Runnable {

    static Logger logger = Logger.getLogger(ReadAheadCache.class);

    protected interface CacheRequest {
        public void performRequest();
    }

    /**
     * The underlying persistence manager that will be used for persistence and
     * scanning below the cache
     */
    protected PersistenceManagerWithRangeScan realPersistenceManager;

    /**
     * The structure for the cache
     */
    protected Map<CacheKey, CacheValue> cache = new HashMap<CacheKey, CacheValue>();

    /**
     * To simplify synchronization, the cache will be maintained by a single
     * cache maintainer thread. This is the queue that will hold requests that
     * need to be served by this thread
     */
    protected BlockingQueue<CacheRequest> requestQueue = new LinkedBlockingQueue<CacheRequest>();

    /**
     * We want to keep track of when entries were added in the cache, so that we
     * can remove them in a FIFO fashion
     */
    protected SortedMap<Long, Set<CacheKey>> timeIndexOfAddition = new TreeMap<Long, Set<CacheKey>>();

    /**
     * We also want to track the entries in seq-id order so that we can clean up
     * entries after the last subscriber
     */
    protected Map<ByteString, SortedSet<Long>> orderedIndexOnSeqId = new HashMap<ByteString, SortedSet<Long>>();

    /**
     * We maintain an estimate of the current size of the cache, so that we know
     * when to evict entries.
     */
    protected long presentCacheSize = 0;

    /**
     * One instance of a callback that we will pass to the underlying
     * persistence manager when asking it to persist messages
     */
    protected PersistCallback persistCallbackInstance = new PersistCallback();

    /**
     * 2 kinds of exceptions that we will use to signal error from readahead
     */
    protected NoSuchSeqIdException noSuchSeqIdExceptionInstance = new NoSuchSeqIdException();
    protected ReadAheadException readAheadExceptionInstance = new ReadAheadException();

    protected ServerConfiguration cfg;
    protected Thread cacheThread;
    // Boolean indicating if this thread should continue running. This is used
    // when we want to stop the thread during a PubSubServer shutdown.
    protected boolean keepRunning = true;

    /**
     * Constructor. Starts the cache maintainer thread
     * 
     * @param realPersistenceManager
     */
    public ReadAheadCache(PersistenceManagerWithRangeScan realPersistenceManager, ServerConfiguration cfg) {
        this.realPersistenceManager = realPersistenceManager;
        this.cfg = cfg;
        cacheThread = new Thread(this, "CacheThread");
    }

    public ReadAheadCache start() {
        cacheThread.start();
        return this;
    }

    /**
     * ========================================================================
     * Methods of {@link PersistenceManager} that we will pass straight down to
     * the real persistence manager.
     */

    public long getSeqIdAfterSkipping(ByteString topic, long seqId, int skipAmount) {
        return realPersistenceManager.getSeqIdAfterSkipping(topic, seqId, skipAmount);
    }

    public MessageSeqId getCurrentSeqIdForTopic(ByteString topic) throws ServerNotResponsibleForTopicException {
        return realPersistenceManager.getCurrentSeqIdForTopic(topic);
    }

    /**
     * ========================================================================
     * Other methods of {@link PersistenceManager} that the cache needs to take
     * some action on.
     * 
     * 1. Persist: We pass it through to the real persistence manager but insert
     * our callback on the return path
     * 
     */
    public void persistMessage(PersistRequest request) {
        // make a new PersistRequest object so that we can insert our own
        // callback in the middle. Assign the original request as the context
        // for the callback.

        PersistRequest newRequest = new PersistRequest(request.getTopic(), request.getMessage(),
                persistCallbackInstance, request);
        realPersistenceManager.persistMessage(newRequest);
    }

    /**
     * The callback that we insert on the persist request return path. The
     * callback simply forms a {@link PersistResponse} object and inserts it in
     * the request queue to be handled serially by the cache maintainer thread.
     * 
     */
    public class PersistCallback implements Callback<Long> {

        /**
         * In case there is a failure in persisting, just pass it to the
         * original callback
         */
        public void operationFailed(Object ctx, PubSubException exception) {
            PersistRequest originalRequest = (PersistRequest) ctx;
            Callback<Long> originalCallback = originalRequest.getCallback();
            Object originalContext = originalRequest.getCtx();
            originalCallback.operationFailed(originalContext, exception);
        }

        /**
         * When the persist finishes, we first notify the original callback of
         * success, and then opportunistically treat the message as if it just
         * came in through a scan
         */
        public void operationFinished(Object ctx, Long resultOfOperation) {
            PersistRequest originalRequest = (PersistRequest) ctx;

            // Lets call the original callback first so that the publisher can
            // hear success
            originalRequest.getCallback().operationFinished(originalRequest.getCtx(), resultOfOperation);

            // Original message that was persisted didn't have the local seq-id.
            // Lets add that in
            Message messageWithLocalSeqId = MessageIdUtils.mergeLocalSeqId(originalRequest.getMessage(),
                    resultOfOperation);

            // Now enqueue a request to add this newly persisted message to our
            // cache
            CacheKey cacheKey = new CacheKey(originalRequest.getTopic(), resultOfOperation);

            enqueueWithoutFailure(new ScanResponse(cacheKey, messageWithLocalSeqId));
        }

    }

    /**
     * Too complicated to deal with enqueue failures from the context of our
     * callbacks. Its just simpler to quit and restart afresh. Moreover, this
     * should not happen as the request queue for the cache maintainer is
     * unbounded.
     * 
     * @param obj
     */
    protected void enqueueWithoutFailure(CacheRequest obj) {
        if (!requestQueue.offer(obj)) {
            throw new UnexpectedError("Could not enqueue object: " + obj.toString()
                    + " to cache request queue. Exiting.");

        }
    }

    /**
     * Another method from {@link PersistenceManager}.
     * 
     * 2. Scan - Since the scan needs to touch the cache, we will just enqueue
     * the scan request and let the cache maintainer thread handle it.
     */
    public void scanSingleMessage(ScanRequest request) {
        // Let the scan requests be serialized through the queue
        enqueueWithoutFailure(new ScanRequestWrapper(request));
    }

    /**
     * Another method from {@link PersistenceManager}.
     * 
     * 3. Enqueue the request so that the cache maintainer thread can delete all
     * message-ids older than the one specified
     */
    public void deliveredUntil(ByteString topic, Long seqId) {
        enqueueWithoutFailure(new DeliveredUntil(topic, seqId));
    }

    /**
     * Another method from {@link PersistenceManager}.
     * 
     * Since this is a cache layer on top of an underlying persistence manager,
     * we can just call the consumedUntil method there. The messages older than
     * the latest one passed here won't be accessed anymore so they should just
     * get aged out of the cache eventually. For now, there is no need to
     * proactively remove those entries from the cache.
     */
    public void consumedUntil(ByteString topic, Long seqId) {
        realPersistenceManager.consumedUntil(topic, seqId);
    }

    /**
     * ========================================================================
     * BEGINNING OF CODE FOR THE CACHE MAINTAINER THREAD
     * 
     * 1. The run method. It simply dequeues from the request queue, checks the
     * type of object and acts accordingly
     */
    public void run() {
        while (keepRunning) {
            CacheRequest obj;
            try {
                obj = requestQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            obj.performRequest();
        }

    }

    /**
     * Stop method which will enqueue a ShutdownCacheRequest.
     */
    public void stop() {
        enqueueWithoutFailure(new ShutdownCacheRequest());
    }

    /**
     * The readahead policy is simple: We check if an entry already exists for
     * the message being requested. If an entry exists, it means that either
     * that message is already in the cache, or a read for that message is
     * outstanding. In that case, we look a little ahead (by readAheadCount/2)
     * and issue a range read of readAheadCount/2 messages. The idea is to
     * ensure that the next readAheadCount messages are always available.
     * 
     * @return the range scan that should be issued for read ahead
     */
    protected RangeScanRequest doReadAhead(ScanRequest request) {
        ByteString topic = request.getTopic();
        Long seqId = request.getStartSeqId();

        int readAheadCount = cfg.getReadAheadCount();
        // To prevent us from getting screwed by bad configuration
        readAheadCount = Math.max(1, readAheadCount);

        RangeScanRequest readAheadRequest = doReadAheadStartingFrom(topic, seqId, readAheadCount);

        if (readAheadRequest != null) {
            return readAheadRequest;
        }

        // start key was already there in the cache so no readahead happened,
        // lets look a little beyond
        seqId = realPersistenceManager.getSeqIdAfterSkipping(topic, seqId, readAheadCount / 2);

        readAheadRequest = doReadAheadStartingFrom(topic, seqId, readAheadCount / 2);

        return readAheadRequest;
    }

    /**
     * This method just checks if the provided seq-id already exists in the
     * cache. If not, a range read of the specified amount is issued.
     * 
     * @param topic
     * @param seqId
     * @param readAheadCount
     * @return The range read that should be issued
     */
    protected RangeScanRequest doReadAheadStartingFrom(ByteString topic, long seqId, int readAheadCount) {

        long startSeqId = seqId;
        Queue<CacheKey> installedStubs = new LinkedList<CacheKey>();

        int i = 0;

        for (; i < readAheadCount; i++) {
            CacheKey cacheKey = new CacheKey(topic, seqId);

            // Even if a stub exists, it means that a scan for that is
            // outstanding
            if (cache.containsKey(cacheKey)) {
                break;
            }
            CacheValue cacheValue = new CacheValue();
            cache.put(cacheKey, cacheValue);

            if (logger.isDebugEnabled()) {
                logger.debug("Adding stub for seq-id: " + seqId + " topic: " + topic.toStringUtf8());
            }
            installedStubs.add(cacheKey);

            seqId = realPersistenceManager.getSeqIdAfterSkipping(topic, seqId, 1);
        }

        // so how many did we decide to readahead
        if (i == 0) {
            // no readahead, hence return false
            return null;
        }

        long readAheadSizeLimit = cfg.getReadAheadSizeBytes();
        ReadAheadScanCallback callback = new ReadAheadScanCallback(installedStubs, topic);
        RangeScanRequest rangeScanRequest = new RangeScanRequest(topic, startSeqId, i, readAheadSizeLimit, callback,
                null);

        return rangeScanRequest;

    }

    /**
     * This is the callback that is used for the range scans.
     */
    protected class ReadAheadScanCallback implements ScanCallback {
        Queue<CacheKey> installedStubs;
        ByteString topic;

        /**
         * Constructor
         * 
         * @param installedStubs
         *            The list of stubs that were installed for this range scan
         * @param topic
         */
        public ReadAheadScanCallback(Queue<CacheKey> installedStubs, ByteString topic) {
            this.installedStubs = installedStubs;
            this.topic = topic;
        }

        public void messageScanned(Object ctx, Message message) {

            // Any message we read is potentially useful for us, so lets first
            // enqueue it
            CacheKey cacheKey = new CacheKey(topic, message.getMsgId().getLocalComponent());
            enqueueWithoutFailure(new ScanResponse(cacheKey, message));

            // Now lets see if this message is the one we were expecting
            CacheKey expectedKey = installedStubs.peek();

            if (expectedKey == null) {
                // Was not expecting any more messages to come in, but they came
                // in so we will keep them
                return;
            }

            if (expectedKey.equals(cacheKey)) {
                // what we got is what we expected, dequeue it so we get the
                // next expected one
                installedStubs.poll();
                return;
            }

            // If reached here, what we scanned was not what we were expecting.
            // This means that we have wrong stubs installed in the cache. We
            // should remove them, so that whoever is waiting on them can retry.
            // This shouldn't be happening usually
            logger.warn("Unexpected message seq-id: " + message.getMsgId().getLocalComponent() + " on topic: "
                    + topic.toStringUtf8() + " from readahead scan, was expecting seq-id: " + expectedKey.seqId
                    + " topic: " + expectedKey.topic.toStringUtf8() + " installedStubs: " + installedStubs);
            enqueueDeleteOfRemainingStubs(noSuchSeqIdExceptionInstance);

        }

        public void scanFailed(Object ctx, Exception exception) {
            enqueueDeleteOfRemainingStubs(exception);
        }

        public void scanFinished(Object ctx, ReasonForFinish reason) {
            // If the scan finished because no more messages are present, its ok
            // to leave the stubs in place because they will get filled in as
            // new publishes happen. However, if the scan finished due to some
            // other reason, e.g., read ahead size limit was reached, we want to
            // delete the stubs, so that when the time comes, we can schedule
            // another readahead request.
            if (reason != ReasonForFinish.NO_MORE_MESSAGES) {
                enqueueDeleteOfRemainingStubs(readAheadExceptionInstance);
            }
        }

        private void enqueueDeleteOfRemainingStubs(Exception reason) {
            CacheKey installedStub;
            while ((installedStub = installedStubs.poll()) != null) {
                enqueueWithoutFailure(new ExceptionOnCacheKey(installedStub, reason));
            }
        }
    }

    protected static class HashSetCacheKeyFactory implements Factory<Set<CacheKey>> {
        protected static HashSetCacheKeyFactory instance = new HashSetCacheKeyFactory();

        public Set<CacheKey> newInstance() {
            return new HashSet<CacheKey>();
        }
    }

    protected static class TreeSetLongFactory implements Factory<SortedSet<Long>> {
        protected static TreeSetLongFactory instance = new TreeSetLongFactory();

        public SortedSet<Long> newInstance() {
            return new TreeSet<Long>();
        }
    }

    /**
     * For adding the message to the cache, we do some bookeeping such as the
     * total size of cache, order in which entries were added etc. If the size
     * of the cache has exceeded our budget, old entries are collected.
     * 
     * @param cacheKey
     * @param message
     */
    protected void addMessageToCache(CacheKey cacheKey, Message message, long currTime) {
        if (logger.isDebugEnabled()) {
            logger.debug("Adding msg (topic: " + cacheKey.getTopic().toStringUtf8() + ", seq-id: "
                    + message.getMsgId().getLocalComponent() + ") to readahead cache");
        }

        CacheValue cacheValue;

        if ((cacheValue = cache.get(cacheKey)) == null) {
            cacheValue = new CacheValue();
            cache.put(cacheKey, cacheValue);
        }

        // update the cache size
        presentCacheSize += message.getBody().size();

        // maintain the time index of addition
        MapMethods.addToMultiMap(timeIndexOfAddition, currTime, cacheKey, HashSetCacheKeyFactory.instance);

        // maintain the index of seq-id
        MapMethods.addToMultiMap(orderedIndexOnSeqId, cacheKey.getTopic(), cacheKey.getSeqId(),
                TreeSetLongFactory.instance);

        // finally add the message to the cache
        cacheValue.setMessageAndInvokeCallbacks(message, currTime);

        // if overgrown, collect old entries
        collectOldCacheEntries();
    }

    protected void removeMessageFromCache(CacheKey cacheKey, Exception exception, boolean maintainTimeIndex,
            boolean maintainSeqIdIndex) {
        CacheValue cacheValue = cache.remove(cacheKey);

        if (cacheValue == null) {
            return;
        }

        if (cacheValue.isStub()) {
            cacheValue.setErrorAndInvokeCallbacks(exception);
            // Stubs are not present in the indexes, so dont need to maintain
            // indexes here
            return;
        }

        presentCacheSize -= cacheValue.getMessage().getBody().size();

        // maintain the 2 indexes
        // TODO: can we maintain these lazily?
        if (maintainSeqIdIndex) {
            MapMethods.removeFromMultiMap(orderedIndexOnSeqId, cacheKey.getTopic(), cacheKey.getSeqId());
        }
        if (maintainTimeIndex) {
            MapMethods.removeFromMultiMap(timeIndexOfAddition, cacheValue.getTimeOfAddition(), cacheKey);
        }
    }

    /**
     * Collection of old entries is simple. Just collect in insert-time order,
     * oldest to newest.
     */
    protected void collectOldCacheEntries() {
        long maxCacheSize = cfg.getMaximumCacheSize();

        while (presentCacheSize > maxCacheSize && !timeIndexOfAddition.isEmpty()) {
            Long earliestTime = timeIndexOfAddition.firstKey();
            Set<CacheKey> oldCacheEntries = timeIndexOfAddition.get(earliestTime);

            // Note: only concrete cache entries, and not stubs are in the time
            // index. Hence there can be no callbacks pending on these cache
            // entries. Hence safe to remove them directly.
            for (Iterator<CacheKey> iter = oldCacheEntries.iterator(); iter.hasNext();) {
                CacheKey cacheKey = iter.next();

                if (logger.isDebugEnabled()) {
                    logger.debug("Removing topic: " + cacheKey.getTopic() + "seq-id: " + cacheKey.getSeqId()
                            + " from cache because its the oldest");
                }
                removeMessageFromCache(cacheKey, readAheadExceptionInstance, //
                        // maintainTimeIndex=
                        false,
                        // maintainSeqIdIndex=
                        true);
            }

            timeIndexOfAddition.remove(earliestTime);

        }
    }

    /**
     * ========================================================================
     * The rest is just simple wrapper classes.
     * 
     */

    protected class ExceptionOnCacheKey implements CacheRequest {
        CacheKey cacheKey;
        Exception exception;

        public ExceptionOnCacheKey(CacheKey cacheKey, Exception exception) {
            this.cacheKey = cacheKey;
            this.exception = exception;
        }

        /**
         * If for some reason, an outstanding read on a cache stub fails,
         * exception for that key is enqueued by the
         * {@link ReadAheadScanCallback}. To handle this, we simply send error
         * on the callbacks registered for that stub, and delete the entry from
         * the cache
         */
        public void performRequest() {
            removeMessageFromCache(cacheKey, exception,
            // maintainTimeIndex=
                    true,
                    // maintainSeqIdIndex=
                    true);
        }

    }

    @SuppressWarnings("serial")
    protected static class NoSuchSeqIdException extends Exception {

        public NoSuchSeqIdException() {
            super("No such seq-id");
        }
    }

    @SuppressWarnings("serial")
    protected static class ReadAheadException extends Exception {
        public ReadAheadException() {
            super("Readahead failed");
        }
    }

    protected class ScanResponse implements CacheRequest {
        CacheKey cacheKey;
        Message message;

        public ScanResponse(CacheKey cacheKey, Message message) {
            this.cacheKey = cacheKey;
            this.message = message;
        }

        public void performRequest() {
            addMessageToCache(cacheKey, message, System.currentTimeMillis());
        }

    }

    protected class DeliveredUntil implements CacheRequest {
        ByteString topic;
        Long seqId;

        public DeliveredUntil(ByteString topic, Long seqId) {
            this.topic = topic;
            this.seqId = seqId;
        }

        public void performRequest() {
            SortedSet<Long> orderedSeqIds = orderedIndexOnSeqId.get(topic);
            if (orderedSeqIds == null) {
                return;
            }

            // focus on the set of messages with seq-ids <= the one that
            // has been delivered until
            SortedSet<Long> headSet = orderedSeqIds.headSet(seqId + 1);

            for (Iterator<Long> iter = headSet.iterator(); iter.hasNext();) {
                Long seqId = iter.next();
                CacheKey cacheKey = new CacheKey(topic, seqId);

                if (logger.isDebugEnabled()) {
                    logger.debug("Removing seq-id: " + cacheKey.getSeqId() + " topic: "
                            + cacheKey.getTopic().toStringUtf8()
                            + " from cache because every subscriber has moved past");
                }

                removeMessageFromCache(cacheKey, readAheadExceptionInstance, //
                        // maintainTimeIndex=
                        true,
                        // maintainSeqIdIndex=
                        false);
                iter.remove();
            }

            if (orderedSeqIds.isEmpty()) {
                orderedIndexOnSeqId.remove(topic);
            }
        }
    }

    protected class ScanRequestWrapper implements CacheRequest {
        ScanRequest request;

        public ScanRequestWrapper(ScanRequest request) {
            this.request = request;
        }

        /**
         * To handle a scan request, we first try to do readahead (which might
         * cause a range read to be issued to the underlying persistence
         * manager). The readahead will put a stub in the cache, if the message
         * is not already present in the cache. The scan callback that is part
         * of the scan request is added to this stub, and will be called later
         * when the message arrives as a result of the range scan issued to the
         * underlying persistence manager.
         */

        public void performRequest() {

            RangeScanRequest readAheadRequest = doReadAhead(request);

            // Read ahead must have installed at least a stub for us, so this
            // can't be null
            CacheValue cacheValue = cache.get(new CacheKey(request.getTopic(), request.getStartSeqId()));

            // Add our callback to the stub. If the cache value was already a
            // concrete message, the callback will be called right away
            cacheValue.addCallback(request.getCallback(), request.getCtx());

            if (readAheadRequest != null) {
                realPersistenceManager.scanMessages(readAheadRequest);
            }
        }
    }

    protected class ShutdownCacheRequest implements CacheRequest {
        // This is a simple type of CacheRequest we will enqueue when
        // the PubSubServer is shut down and we want to stop the ReadAheadCache
        // thread.
        public void performRequest() {
            keepRunning = false;
        }
    }

}
