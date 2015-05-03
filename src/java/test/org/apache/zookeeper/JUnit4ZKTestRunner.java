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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Test;
import org.junit.internal.runners.statements.InvokeMethod;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * The sole responsibility of this class is to print to the log when a test
 * starts and when it finishes.
 */
public class JUnit4ZKTestRunner extends BlockJUnit4ClassRunner {
    private static final Logger LOG = LoggerFactory.getLogger(JUnit4ZKTestRunner.class);

    public JUnit4ZKTestRunner(Class<?> klass) throws InitializationError {
        super(klass);
    }

    public class LoggedInvokeMethod extends InvokeMethod {
        private final FrameworkMethod method;
        private final String name;

        public LoggedInvokeMethod(FrameworkMethod method, Object target) {
            super(method, target);
            this.method = method;
            name = method.getName();
        }

        @Override
        public void evaluate() throws Throwable {
            LOG.info("RUNNING TEST METHOD {}", name);
            try {
                super.evaluate();
                Runtime rt = Runtime.getRuntime();
                long usedKB = (rt.totalMemory() - rt.freeMemory()) / 1024;
                LOG.info("Memory used {}", usedKB);
                ThreadGroup tg = Thread.currentThread().getThreadGroup();
                while (tg.getParent() != null) {
                    tg = tg.getParent();
                }
                LOG.info("Number of threads {}", tg.activeCount());
            } catch (Throwable t) {
                // The test method threw an exception, but it might be an
                // expected exception as defined in the @Test annotation.
                // Check the annotation and log an appropriate message.
                Test annotation = this.method.getAnnotation(Test.class);
                if (annotation != null && annotation.expected() != null &&
                        annotation.expected().isAssignableFrom(t.getClass())) {
                    LOG.info("TEST METHOD {} THREW EXPECTED EXCEPTION {}", name,
                        annotation.expected());
                } else {
                    LOG.info("TEST METHOD FAILED {}", name, t);
                }
                throw t;
            }
            LOG.info("FINISHED TEST METHOD {}", name);
        }
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
        return new LoggedInvokeMethod(method, test);
    }
}
