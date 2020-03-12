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

package org.apache.zookeeper;

import static org.junit.Assert.fail;
import java.time.LocalDateTime;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for a non-parameterized ZK test.
 *
 * Basic utilities shared by all tests. Also logging of various events during
 * the test execution (start/stop/success/failure/etc...)
 */
@SuppressWarnings("deprecation")
@RunWith(JUnit4ZKTestRunner.class)
public class ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(ZKTestCase.class);

    private String testName;

    protected String getTestName() {
        return testName;
    }

    @Rule
    public TestWatcher watchman= new TestWatcher() {
        
        @Override
        public void starting(Description method) {
            // By default, disable starting a JettyAdminServer in tests to avoid
            // accidentally attempting to start multiple admin servers on the
            // same port.
            System.setProperty("zookeeper.admin.enableServer", "false");
            // ZOOKEEPER-2693 disables all 4lw by default.
            // Here we enable the 4lw which ZooKeeper tests depends.
            System.setProperty("zookeeper.4lw.commands.whitelist", "*");
            testName = method.getMethodName();
            LOG.info("STARTING " + testName);
        }

        @Override
        public void finished(Description method) {
            LOG.info("FINISHED " + testName);
        }

        @Override
        public void succeeded(Description method) {
            LOG.info("SUCCEEDED " + testName);
        }

        @Override
        public void failed(Throwable e, Description method) {
            LOG.info("FAILED " + testName, e);
        }

    };
    public interface WaitForCondition {
        /**
         * @return true when success
         */
        boolean evaluate();
    }

    /**
     * Wait for condition to be true; otherwise fail the test if it exceed
     * timeout
     * @param msg       error message to print when fail
     * @param condition condition to evaluate
     * @param timeout   timeout in seconds
     * @throws InterruptedException
     */
    public void waitFor(String msg, WaitForCondition condition, int timeout) throws InterruptedException {
        final LocalDateTime deadline = LocalDateTime.now().plusSeconds(timeout);
        while (LocalDateTime.now().isBefore(deadline)) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(msg);
    }

}
