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

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import junit.framework.TestCase;

import org.apache.bookkeeper.client.BookKeeper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.HelperMethods;
import org.apache.hedwig.StubCallback;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.server.topics.TrivialOwnAllTopicManager;
import org.apache.hedwig.util.ConcurrencyUtils;

public class TestBookkeeperPersistenceManagerWhiteBox extends TestCase {

    BookKeeperTestBase bktb;
    private final int numBookies = 3;
    BookkeeperPersistenceManager bkpm;
    ServerConfiguration conf;
    ScheduledExecutorService scheduler;
    TopicManager tm;
    ByteString topic = ByteString.copyFromUtf8("topic0");

    @Override
    @Before
    protected void setUp() throws Exception {
        super.setUp();
        bktb = new BookKeeperTestBase(numBookies);
        bktb.setUp();

        conf = new ServerConfiguration();
        scheduler = Executors.newScheduledThreadPool(1);
        tm = new TrivialOwnAllTopicManager(conf, scheduler);

        bkpm = new BookkeeperPersistenceManager(bktb.bk, bktb.getZooKeeperClient(), tm, conf, scheduler);
    }

    @Override
    @After
    protected void tearDown() throws Exception {
        bktb.tearDown();
        super.tearDown();
    }

    @Test
    public void testEmptyDirtyLedger() throws Exception {

        StubCallback<Void> stubCallback = new StubCallback<Void>();
        bkpm.acquiredTopic(topic, stubCallback, null);
        assertNull(ConcurrencyUtils.take(stubCallback.queue).right());
        // now abandon, and try another time, the prev ledger should be dirty

        bkpm = new BookkeeperPersistenceManager(new BookKeeper(bktb.getZkHostPort()), bktb.getZooKeeperClient(), tm,
                conf, scheduler);
        bkpm.acquiredTopic(topic, stubCallback, null);
        assertNull(ConcurrencyUtils.take(stubCallback.queue).right());
        assertEquals(0, bkpm.topicInfos.get(topic).ledgerRanges.size());
    }

    public void testNonEmptyDirtyLedger() throws Exception {

        Random r = new Random();
        int NUM_MESSAGES_TO_TEST = 100;
        int SIZE_OF_MESSAGES_TO_TEST = 100;
        int index = 0;
        int numPrevLedgers = 0;
        List<Message> messages = HelperMethods.getRandomPublishedMessages(NUM_MESSAGES_TO_TEST,
                SIZE_OF_MESSAGES_TO_TEST);

        while (index < messages.size()) {

            StubCallback<Void> stubCallback = new StubCallback<Void>();
            bkpm.acquiredTopic(topic, stubCallback, null);
            assertNull(ConcurrencyUtils.take(stubCallback.queue).right());
            assertEquals(numPrevLedgers, bkpm.topicInfos.get(topic).ledgerRanges.size());

            StubCallback<Long> persistCallback = new StubCallback<Long>();
            bkpm.persistMessage(new PersistRequest(topic, messages.get(index), persistCallback, null));
            assertEquals(new Long(index + 1), ConcurrencyUtils.take(persistCallback.queue).left());

            // once in every 10 times, give up ledger
            if (r.nextInt(10) == 9) {
                // Make the bkpm lose its memory
                bkpm.topicInfos.clear();
                numPrevLedgers++;
            }
            index++;
        }

        // Lets scan now
        StubScanCallback scanCallback = new StubScanCallback();
        bkpm.scanMessages(new RangeScanRequest(topic, 1, NUM_MESSAGES_TO_TEST, Long.MAX_VALUE, scanCallback, null));
        for (int i = 0; i < messages.size(); i++) {
            Message scannedMessage = ConcurrencyUtils.take(scanCallback.queue).left();
            assertTrue(messages.get(i).getBody().equals(scannedMessage.getBody()));
            assertEquals(i + 1, scannedMessage.getMsgId().getLocalComponent());
        }
        assertTrue(StubScanCallback.END_MESSAGE == ConcurrencyUtils.take(scanCallback.queue).left());

    }

}
