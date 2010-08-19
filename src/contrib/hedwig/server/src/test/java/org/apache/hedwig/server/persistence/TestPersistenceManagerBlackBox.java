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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.HelperMethods;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.topics.TopicOwnershipChangeListener;
import org.apache.hedwig.util.Callback;

public abstract class TestPersistenceManagerBlackBox extends TestCase {
    protected PersistenceManager persistenceManager;
    protected int NUM_MESSAGES_TO_TEST = 5;
    protected int NUM_TOPICS_TO_TEST = 5;
    static Logger logger = Logger.getLogger(TestPersistenceManagerBlackBox.class);
    TestCallback testCallback = new TestCallback();

    RuntimeException failureException;

    class TestCallback implements Callback<Long> {

        public void operationFailed(Object ctx, PubSubException exception) {
            throw (failureException = new RuntimeException(exception));
        }

        @SuppressWarnings("unchecked")
        public void operationFinished(Object ctx, Long resultOfOperation) {
            LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
            try {
                statusQueue.put(true);
            } catch (InterruptedException e) {
                throw (failureException = new RuntimeException(e));
            }
        }
    }

    class RangeScanVerifierListener implements ScanCallback {
        List<Message> pubMsgs;

        public RangeScanVerifierListener(List<Message> pubMsgs) {
            this.pubMsgs = pubMsgs;
        }

        public void messageScanned(Object ctx, Message recvMessage) {
            if (pubMsgs.isEmpty()) {
                throw (failureException = new RuntimeException("Message received when none expected"));
            }

            Message pubMsg = pubMsgs.get(0);
            if (!HelperMethods.areEqual(recvMessage, pubMsg)) {
                throw (failureException = new RuntimeException("Scanned message not equal to expected"));
            }
            pubMsgs.remove(0);
        }

        public void scanFailed(Object ctx, Exception exception) {
            throw (failureException = new RuntimeException(exception));
        }

        @SuppressWarnings("unchecked")
        public void scanFinished(Object ctx, ReasonForFinish reason) {
            if (reason != ReasonForFinish.NO_MORE_MESSAGES) {
                throw (failureException = new RuntimeException("Scan finished prematurely " + reason));
            }
            LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
            try {
                statusQueue.put(true);
            } catch (InterruptedException e) {
                throw (failureException = new RuntimeException(e));
            }
        }

    }

    class PointScanVerifierListener implements ScanCallback {
        List<Message> pubMsgs;
        ByteString topic;

        public PointScanVerifierListener(List<Message> pubMsgs, ByteString topic) {
            this.topic = topic;
            this.pubMsgs = pubMsgs;
        }

        @SuppressWarnings("unchecked")
        public void messageScanned(Object ctx, Message recvMessage) {

            Message pubMsg = pubMsgs.get(0);
            if (!HelperMethods.areEqual(recvMessage, pubMsg)) {
                throw (failureException = new RuntimeException("Scanned message not equal to expected"));
            }
            pubMsgs.remove(0);

            if (pubMsgs.isEmpty()) {
                LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
                try {
                    statusQueue.put(true);
                } catch (InterruptedException e) {
                    throw (failureException = new RuntimeException(e));
                }
            } else {
                long seqId = recvMessage.getMsgId().getLocalComponent();
                seqId = persistenceManager.getSeqIdAfterSkipping(topic, seqId, 1);
                ScanRequest request = new ScanRequest(topic, seqId, new PointScanVerifierListener(pubMsgs, topic), ctx);
                persistenceManager.scanSingleMessage(request);
            }

        }

        public void scanFailed(Object ctx, Exception exception) {
            throw (failureException = new RuntimeException(exception));
        }

        public void scanFinished(Object ctx, ReasonForFinish reason) {

        }

    }

    class ScanVerifier implements Runnable {
        List<Message> pubMsgs;
        ByteString topic;
        LinkedBlockingQueue<Boolean> statusQueue = new LinkedBlockingQueue<Boolean>();

        public ScanVerifier(ByteString topic, List<Message> pubMsgs) {
            this.topic = topic;
            this.pubMsgs = pubMsgs;
        }

        public void run() {
            // start the scan
            try {
                if (persistenceManager instanceof PersistenceManagerWithRangeScan) {

                    ScanCallback listener = new RangeScanVerifierListener(pubMsgs);

                    PersistenceManagerWithRangeScan rangePersistenceManager = (PersistenceManagerWithRangeScan) persistenceManager;

                    rangePersistenceManager.scanMessages(new RangeScanRequest(topic, getLowestSeqId(),
                            NUM_MESSAGES_TO_TEST + 1, Long.MAX_VALUE, listener, statusQueue));

                } else {

                    ScanCallback listener = new PointScanVerifierListener(pubMsgs, topic);
                    persistenceManager
                            .scanSingleMessage(new ScanRequest(topic, getLowestSeqId(), listener, statusQueue));

                }
                // now listen for it to finish
                // wait a maximum of a minute
                Boolean b = statusQueue.poll(60, TimeUnit.SECONDS);
                if (b == null) {
                    throw (failureException = new RuntimeException("Scanning timed out"));
                }
            } catch (InterruptedException e) {
                throw (failureException = new RuntimeException(e));
            }
        }
    }

    class Publisher implements Runnable {
        List<Message> pubMsgs;
        ByteString topic;

        public Publisher(ByteString topic, List<Message> pubMsgs) {
            this.pubMsgs = pubMsgs;
            this.topic = topic;
        }

        public void run() {
            LinkedBlockingQueue<Boolean> statusQueue = new LinkedBlockingQueue<Boolean>();

            for (Message msg : pubMsgs) {

                try {
                    persistenceManager.persistMessage(new PersistRequest(topic, msg, testCallback, statusQueue));
                    // wait a maximum of a minute
                    Boolean b = statusQueue.poll(60, TimeUnit.SECONDS);
                    if (b == null) {
                        throw (failureException = new RuntimeException("Scanning timed out"));
                    }
                } catch (InterruptedException e) {
                    throw (failureException = new RuntimeException(e));
                }
            }
        }

    }

    @Override
    protected void setUp() throws Exception {
        logger.info("STARTING " + getName());
        persistenceManager = instantiatePersistenceManager();
        failureException = null;
        logger.info("Persistence Manager test setup finished");
    }

    abstract long getLowestSeqId();

    abstract PersistenceManager instantiatePersistenceManager() throws Exception;

    @Override
    protected void tearDown() throws Exception {
        logger.info("tearDown starting");
        super.tearDown();
        logger.info("FINISHED " + getName());
    }

    protected ByteString getTopicName(int number) {
        return ByteString.copyFromUtf8("topic" + number);
    }

    @Test
    public void testPersistenceManager() throws Exception {
        List<Thread> publisherThreads = new LinkedList<Thread>();
        List<Thread> scannerThreads = new LinkedList<Thread>();
        Thread thread;
        Semaphore latch = new Semaphore(1);

        for (int i = 0; i < NUM_TOPICS_TO_TEST; i++) {
            ByteString topic = getTopicName(i);

            if (persistenceManager instanceof TopicOwnershipChangeListener) {

                TopicOwnershipChangeListener tocl = (TopicOwnershipChangeListener) persistenceManager;

                latch.acquire();

                tocl.acquiredTopic(topic, new Callback<Void>() {
                    @Override
                    public void operationFailed(Object ctx, PubSubException exception) {
                        failureException = new RuntimeException(exception);
                        ((Semaphore) ctx).release();
                    }

                    @Override
                    public void operationFinished(Object ctx, Void res) {
                        ((Semaphore) ctx).release();
                    }
                }, latch);

                latch.acquire();
                latch.release();
                if (failureException != null) {
                    throw (Exception) failureException.getCause();
                }
            }
            List<Message> msgs = HelperMethods.getRandomPublishedMessages(NUM_MESSAGES_TO_TEST, 1024);

            thread = new Thread(new Publisher(topic, msgs));
            publisherThreads.add(thread);
            thread.start();

            thread = new Thread(new ScanVerifier(topic, msgs));
            scannerThreads.add(thread);
        }
        for (Thread t : publisherThreads) {
            t.join();
        }

        for (Thread t : scannerThreads) {
            t.start();
        }

        for (Thread t : scannerThreads) {
            t.join();
        }

        assertEquals(null, failureException);
        for (int i = 0; i < NUM_TOPICS_TO_TEST; i++) {
            assertEquals(persistenceManager.getCurrentSeqIdForTopic(getTopicName(i)).getLocalComponent(),
                    getExpectedSeqId(NUM_MESSAGES_TO_TEST));
        }

    }

    abstract long getExpectedSeqId(int numPublished);

}
