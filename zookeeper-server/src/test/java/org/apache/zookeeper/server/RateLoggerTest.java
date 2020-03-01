/*
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

package org.apache.zookeeper.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link RateLogger}.
 */
public class RateLoggerTest {

    /**
     * The underlying logger that is passed into {@code RateLogger} instance under test.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLoggerTest.class);

    /**
     * The log4j appender for capturing messages logged using the {@code RateLogger}.
     */
    private static TestAppender testAppender = null;

    @BeforeClass
    public static void setUpBeforeClass() {
        testAppender = new TestAppender();
        org.apache.log4j.Logger logger =
                org.apache.log4j.Logger.getLogger(RateLoggerTest.class);
        logger.setAdditivity(false);
        logger.addAppender(testAppender);
    }

    @Before
    public void setUpBefore() {
        testAppender.messages.clear();
    }

    /** Verify that a single message is logged. */
    @Test
    public void singleMessageExplicitFlush() {
        RateLogger rateLogger = new RateLogger(LOGGER, 5 * 1000L);
        rateLogger.rateLimitLog("test message");
        assertThat(testAppender.messages,
                contains("Message: test message"));
    }

    /** Verify that a single message with value is logged. */
    @Test
    public void singleMessageWithValueExplicitFlush() {
        RateLogger rateLogger = new RateLogger(LOGGER, 5 * 1000L);
        rateLogger.rateLimitLog("test message", "testValue");
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message, Value: testValue"));
    }

    /** Verify that a repeated message and value is not logged until flushed. */
    @Test
    public void recurringMessageWithValueExplicitFlush() {
        RateLogger rateLogger = new RateLogger(LOGGER, 5 * 1000L);
        rateLogger.rateLimitLog("test message", "value-one");
        rateLogger.rateLimitLog("test message", "value-two");
        assertThat(testAppender.messages,
                contains("Message: test message, Value: value-one"));
        // flush logs second message
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message, Value: value-one",
                        "Message: test message, Last value: value-two"));
    }

    /** Verify that a message change writes the previous message. */
    @Test
    public void messageChangeImplicitFlush() {
        RateLogger rateLogger = new RateLogger(LOGGER, 5 * 1000L);
        rateLogger.rateLimitLog("test message one");
        rateLogger.rateLimitLog("test message one");
        rateLogger.rateLimitLog("test message two");
        assertThat(testAppender.messages,
                contains("Message: test message one",
                         "Message: test message one",
                         "Message: test message two"));
    }

    /** Verify that a count is included for more than one message and value. */
    @Test
    public void recurringMessageWithValueImplicitFlush() {
        RateLogger rateLogger = new RateLogger(LOGGER, 5 * 1000L);
        rateLogger.rateLimitLog("test message one", "value-one");
        rateLogger.rateLimitLog("test message one", "value-two");
        rateLogger.rateLimitLog("test message one", "value-three");
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message one, Value: value-one",
                        "[2 times] Message: test message one, Last value: value-three"));
    }

    /** Verify the a message written after the rate interval logs the previous message. */
    @Test
    public void recurringMessageTimedImplicitFlush() throws InterruptedException {
        long rateLogInterval = 500L;
        RateLogger rateLogger = new RateLogger(LOGGER, rateLogInterval);
        rateLogger.rateLimitLog("test message one", "value-one");
        rateLogger.rateLimitLog("test message one", "value-two");
        rateLogger.rateLimitLog("test message one", "value-three");
        Thread.sleep(2 * rateLogInterval);
        // implicit flush
        rateLogger.rateLimitLog("test message one", "value-four");

        assertThat(testAppender.messages,
                contains("Message: test message one, Value: value-one",
                        "[2 times] Message: test message one, Last value: value-three"));

        // explicit flush writes last message
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message one, Value: value-one",
                        "[2 times] Message: test message one, Last value: value-three",
                        "Message: test message one, Last value: value-four"));
    }
}

/**
 * A Log4j appender that saves logged messages to a {@code List}.
 */
class TestAppender extends AppenderSkeleton {
    public List<String> messages = new ArrayList<>();
    @Override
    public void append(LoggingEvent event) {
        messages.add(event.getMessage().toString());
    }
    @Override
    public void close() {
        // intentionally does nothing
    }
    @Override
    public boolean requiresLayout() {
        return false;
    }
}