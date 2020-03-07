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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Simulates the current system time in millis and allows the time to be moved
     * forward during testing.
     */
    private static AtomicLong currentTime = null;

    /**
     * The default interval for the {@code RateLogger} under test to log messages.
     */
    private static final long DEFAULT_LOGGING_INTERVAL = 500L;

    @BeforeClass
    public static void setUpBeforeClass() {
        testAppender = new TestAppender();
        currentTime = new AtomicLong(0L);
        org.apache.log4j.Logger logger =
                org.apache.log4j.Logger.getLogger(RateLoggerTest.class);
        logger.setAdditivity(false);
        logger.addAppender(testAppender);
    }

    @Before
    public void setUpBefore() {
        testAppender.messages.clear();
        currentTime.set(0L);
    }

    /** Verity that the first time a message is received it is logged immediately. */
    @Test
    public void singleMessage() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message");
        assertThat(testAppender.messages,
                contains("Message: test message"));
    }

    /** Verify that a single message with value is logged. */
    @Test
    public void singleMessageValueExplicitFlush() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message.", "testValue");
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message. Value: testValue"));
    }

    /** Verify that a repeated message and value is not logged until flushed. */
    @Test
    public void recurringMessageWithValueExplicitFlush() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message.", "value-one");
        rateLogger.rateLimitLog("test message.", "value-two");
        // first message logged immediately, second is retained
        assertThat(testAppender.messages,
                contains("Message: test message. Value: value-one"));
        // flush logs second message
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message. Value: value-one",
                        "Message: test message. Last value: value-two"));
    }

    /** Verify that a message change writes the previous message. */
    @Test
    public void messageChangeImplicitFlush() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message one");
        rateLogger.rateLimitLog("test message one");
        // message change invokes log of previous message
        rateLogger.rateLimitLog("test message two");
        /*
         * first logged immediately, the second during a flush due to message
         * change, and the third immediately as new message
         */
        assertThat(testAppender.messages,
                contains("Message: test message one",
                         "Message: test message one",
                         "Message: test message two"));
    }

    /** Verify that a count is included for more than one message and value. */
    @Test
    public void recurringMessageWithCountExplicitFlush() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message one.", "value-one");
        rateLogger.rateLimitLog("test message one.", "value-two");
        rateLogger.rateLimitLog("test message one.", "value-three");
        // repeated value logged with last receive value
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message one. Value: value-one",
                        "[2 times] Message: test message one. Last value: value-three"));
    }

    /** Verify the a message written after the rate interval logs the previous message. */
    @Test
    public void recurringMessageTimedImplicitFlush() {
        RateLogger rateLogger = getRateLogger();
        rateLogger.rateLimitLog("test message one.", "value-one");
        rateLogger.rateLimitLog("test message one.", "value-two");
        rateLogger.rateLimitLog("test message one.", "value-three");

        // simulate passage of time
        currentTime.set(DEFAULT_LOGGING_INTERVAL + 1L);
        // same message received after log interval flushes message, count, and
        // last value received: 'value-three'
        rateLogger.rateLimitLog("test message one.", "value-four");
        assertThat(testAppender.messages,
                contains("Message: test message one. Value: value-one",
                        "[2 times] Message: test message one. Last value: value-three"));

        rateLogger.rateLimitLog("test message one.", "value-five");
        // explicit flush writes current message, count, and last value received: 'value-five'
        rateLogger.flush();
        assertThat(testAppender.messages,
                contains("Message: test message one. Value: value-one",
                        "[2 times] Message: test message one. Last value: value-three",
                        "[2 times] Message: test message one. Last value: value-five"));
    }

    /**
     * Creates a {@code RateLogger} instance that allows the current time to
     * be set for testing.
     * @return the created instance
     */
    private RateLogger getRateLogger() {
        return new RateLogger(LOGGER, DEFAULT_LOGGING_INTERVAL) {
            long getCurrentElapsedTime() {
                return currentTime.get();
            }
        };
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