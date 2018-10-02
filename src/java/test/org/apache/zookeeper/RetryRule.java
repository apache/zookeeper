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

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Defined a junit rule to retry failed test immediately.
 */
public class RetryRule implements TestRule {
    private int retryCount;
    private String retryException;

    public RetryRule(int retryCount, String retryException) {
        this.retryCount = retryCount;
        this.retryException = retryException;
    }

    public Statement apply(Statement base, Description description) {
        return statement(base, description);
    }

    private Statement statement(
            final Statement base, final Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Throwable caughtThrowable = null;

                // implement retry logic here
                int retryTimes = 0;
                while (retryTimes < retryCount) {
                    try {
                        base.evaluate();
                        return;
                    } catch (Throwable t) {
                        caughtThrowable = t;
                        if (!Class.forName(retryException).isInstance(t)) {
                            System.err.println(
                                    String.format("Not going to retry %s", t));
                            break;
                        }
                    }
                    retryTimes++;
                    System.err.println(
                            String.format("%s: run %d failed, %s, trying again.", 
                            description.getDisplayName(), retryTimes, 
                            caughtThrowable.getMessage()));
                }

                System.err.println(
                        String.format("%s: giving up after retry %d times", 
                        description.getDisplayName(), retryTimes));
                throw caughtThrowable;
            }
        };
    }
}
