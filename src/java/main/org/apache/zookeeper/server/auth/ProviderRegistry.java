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

package org.apache.zookeeper.server.auth;

import java.util.Enumeration;
import java.util.HashMap;

import org.apache.log4j.Logger;

import org.apache.zookeeper.server.ZooKeeperServer;

public class ProviderRegistry {
    private static final Logger LOG = Logger.getLogger(ProviderRegistry.class);

    private static boolean initialized = false;
    private static HashMap<String, AuthenticationProvider> authenticationProviders =
        new HashMap<String, AuthenticationProvider>();

    public static void initialize() {
        synchronized (ProviderRegistry.class) {
            if (initialized)
                return;
            IPAuthenticationProvider ipp = new IPAuthenticationProvider();
            DigestAuthenticationProvider digp = new DigestAuthenticationProvider();
            authenticationProviders.put(ipp.getScheme(), ipp);
            authenticationProviders.put(digp.getScheme(), digp);
            Enumeration<Object> en = System.getProperties().keys();
            while (en.hasMoreElements()) {
                String k = (String) en.nextElement();
                if (k.startsWith("zookeeper.authProvider.")) {
                    String className = System.getProperty(k);
                    try {
                        Class<?> c = ZooKeeperServer.class.getClassLoader()
                                .loadClass(className);
                        AuthenticationProvider ap = (AuthenticationProvider) c
                                .newInstance();
                        authenticationProviders.put(ap.getScheme(), ap);
                    } catch (Exception e) {
                        LOG.warn("Problems loading " + className,e);
                    }
                }
            }
            initialized = true;
        }
    }

    public static AuthenticationProvider getProvider(String scheme) {
        if(!initialized)
            initialize();
        return authenticationProviders.get(scheme);
    }

    public static String listProviders() {
        StringBuilder sb = new StringBuilder();
        for(String s: authenticationProviders.keySet()) {
        sb.append(s + " ");
}
        return sb.toString();
    }
}
