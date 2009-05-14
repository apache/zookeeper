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

package org.apache.zookeeper.test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.CommonNames;

public class JMXEnv {
    protected static final Logger LOG = Logger.getLogger(JMXEnv.class);

    private static JMXConnectorServer cs;
    private static JMXConnector cc;

    public static void setUp() throws IOException {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi://");
        cs = JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs);
        cs.start();

       JMXServiceURL addr = cs.getAddress();
        
       cc = JMXConnectorFactory.connect(addr);
    }
    
    public static void tearDown() {
        try {
            cc.close();
        } catch (IOException e) {
            LOG.warn("Unexpected, ignoring", e);
            
        }
        cc = null;
        try {
            cs.stop();
        } catch (IOException e) {
            LOG.warn("Unexpected, ignoring", e);
            
        }
        cs = null;
    }
    
    public static MBeanServerConnection conn() throws IOException {
        return cc.getMBeanServerConnection();
    }

    /**
     * Ensure that all of the specified names are registered.
     * Note that these are components of the name, and in particular
     * order matters - you want the more specific name (leafs) specified
     * before their parent(s) (since names are hierarchical)
     * @param expectedNames
     * @return
     * @throws IOException
     * @throws MalformedObjectNameException
     */
    public static Set<ObjectName> ensureAll(String... expectedNames)
        throws IOException
    {
        Set<ObjectName> beans;
        try {
            beans = conn().queryNames(
                    new ObjectName(CommonNames.DOMAIN + ":*"), null);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
        
        Set<ObjectName> found = new HashSet<ObjectName>();
        for (String name : expectedNames) {
            System.err.println("expect:" + name);
            for (ObjectName bean : beans) {
                if (bean.toString().contains(name)) {
                    System.err.println("found:" + name + " " + bean);
                    found.add(bean);
                    break;
                }
            }
            beans.removeAll(found);
        }
        TestCase.assertEquals("expected " + Arrays.toString(expectedNames),
                expectedNames.length, found.size());
        return beans;
    }

    /**
     * Ensure that only the specified names are registered.
     * Note that these are components of the name, and in particular
     * order matters - you want the more specific name (leafs) specified
     * before their parent(s) (since names are hierarchical)
     * @param expectedNames
     * @return
     * @throws IOException
     * @throws MalformedObjectNameException
     */
    public static Set<ObjectName> ensureOnly(String... expectedNames)
        throws IOException
    {
        System.err.println("ensureOnly:" + Arrays.toString(expectedNames));
        Set<ObjectName> beans = ensureAll(expectedNames);
        for (ObjectName bean : beans) {
            System.err.println("unexpected:" + bean.toString());
        }
        TestCase.assertEquals(0, beans.size());
        return beans;
    }
    
    public static void ensureNone(String... expectedNames)
        throws IOException
    {
        Set<ObjectName> beans;
        try {
            beans = conn().queryNames(
                    new ObjectName(CommonNames.DOMAIN + ":*"), null);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
        
        for (String name : expectedNames) {
            for (ObjectName bean : beans) {
                if (bean.toString().contains(name)) {
                    System.err.println("didntexpect:" + name);
                    TestCase.fail(name + " " + bean.toString());
                }
            }
        }
    }

    public static void dump() throws IOException {
        System.err.println("JMXEnv.dump() follows");
        Set<ObjectName> beans;
        try {
            beans = conn().queryNames(
                    new ObjectName(CommonNames.DOMAIN + ":*"), null);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
        for (ObjectName bean : beans) {
            System.err.println("bean:" + bean.toString());
        }
    }

}
