/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParameters;
import org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParametersFactory;
import org.junit.runners.parameterized.TestWithParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ZKParameterized {
    private static final Logger LOG = LoggerFactory.getLogger(ZKParameterized.class);
    public static class RunnerFactory extends BlockJUnit4ClassRunnerWithParametersFactory {
        @Override
        public org.junit.runner.Runner createRunnerForTestWithParameters(TestWithParameters test) throws InitializationError {
            return new ZKParameterized.Runner(test);
        }
    }

    public static class Runner extends BlockJUnit4ClassRunnerWithParameters {
        public Runner(TestWithParameters test) throws InitializationError {
            super(test);
        }


        @Override
        protected List<FrameworkMethod> computeTestMethods() {
            return JUnit4ZKTestRunner.computeTestMethodsForClass(getTestClass().getJavaClass(), super.computeTestMethods());
        }


        @Override
        protected Statement methodInvoker(FrameworkMethod method, Object test) {
            return new JUnit4ZKTestRunner.LoggedInvokeMethod(method, test);
        }
    }
}
