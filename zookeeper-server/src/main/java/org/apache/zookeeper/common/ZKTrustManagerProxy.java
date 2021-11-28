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

package org.apache.zookeeper.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * This is the ZKTrustManager proxy class.
 * This is used to expose ZKTrustManager class through proxy instance to avoid class cast error
 * as X509TrustManagerImpl will conflict with ZKTrustManager class
 */

public class ZKTrustManagerProxy implements InvocationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ZKTrustManagerProxy.class);
    private final ZKTrustManager zkTrustManager;

    public ZKTrustManagerProxy(ZKTrustManager zkTrustManager) {
        this.zkTrustManager = zkTrustManager;
    }

    /**
     * Invoke method implementation of proxy instance
     *
     * @param proxy  proxy object
     * @param method method object
     * @param args   method arguments
     * @return return the output which comes from invoke mrthod
     * @throws Throwable it throws any kind of exception which pop up in executing the given method
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        LOG.debug("Invoking method {} with arguments {}", method.getName(), args);
        return method.invoke(zkTrustManager, args);
    }
}
