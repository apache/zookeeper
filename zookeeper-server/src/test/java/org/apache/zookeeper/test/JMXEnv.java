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

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXEnv {

    protected static final Logger LOG = LoggerFactory.getLogger(JMXEnv.class);

    private static JMXConnectorServer cs;
    private static JMXConnector cc;

    public static void setUp() throws IOException {
        MBeanServer mbs = MBeanRegistry.getInstance().getPlatformMBeanServer();

        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi://127.0.0.1");
        cs = JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs);
        cs.start();

        JMXServiceURL addr = cs.getAddress();

        cc = JMXConnectorFactory.connect(addr);
    }

    public static void tearDown() {
        try {
            if (cc != null) {
                cc.close();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected, ignoring", e);

        }
        cc = null;
        try {
            if (cs != null) {
                cs.stop();
            }
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
     * It waits in a loop up to 60 seconds before failing if there is a
     * mismatch.
     * @param expectedNames
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static Set<ObjectName> ensureAll(String... expectedNames) throws IOException, InterruptedException {
        Set<ObjectName> beans;
        Set<ObjectName> found;
        int nTry = 0;
        do {
            if (nTry++ > 0) {
                Thread.sleep(100);
            }
            try {
                beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }

            found = new HashSet<ObjectName>();
            for (String name : expectedNames) {
                LOG.info("expect:{}", name);
                for (ObjectName bean : beans) {
                    if (bean.toString().contains(name)) {
                        LOG.info("found:{} {}", name, bean);
                        found.add(bean);
                        break;
                    }
                }
                beans.removeAll(found);
            }
        } while ((expectedNames.length != found.size()) && (nTry < 600));
        assertEquals("expected " + Arrays.toString(expectedNames), expectedNames.length, found.size());
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
     * @throws InterruptedException
     */
    public static Set<ObjectName> ensureOnly(String... expectedNames) throws IOException, InterruptedException {
        LOG.info("ensureOnly:{}", Arrays.toString(expectedNames));
        Set<ObjectName> beans = ensureAll(expectedNames);
        for (ObjectName bean : beans) {
            LOG.info("unexpected:{}", bean.toString());
        }
        assertEquals(0, beans.size());
        return beans;
    }

    public static void ensureNone(String... expectedNames) throws IOException, InterruptedException {
        Set<ObjectName> beans;
        int nTry = 0;
        boolean foundUnexpected = false;
        String unexpectedName = "";
        do {
            if (nTry++ > 0) {
                Thread.sleep(100);
            }
            try {
                beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }

            foundUnexpected = false;
            for (String name : expectedNames) {
                for (ObjectName bean : beans) {
                    if (bean.toString().contains(name)) {
                        LOG.info("didntexpect:{}", name);
                        foundUnexpected = true;
                        unexpectedName = name + " " + bean.toString();
                        break;
                    }
                }
                if (foundUnexpected) {
                    break;
                }
            }
        } while ((foundUnexpected) && (nTry < 600));
        if (foundUnexpected) {
            LOG.info("List of all beans follows:");
            for (ObjectName bean : beans) {
                LOG.info("bean:{}", bean.toString());
            }
            fail(unexpectedName);
        }
    }

    public static void dump() throws IOException {
        LOG.info("JMXEnv.dump() follows");
        Set<ObjectName> beans;
        try {
            beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
        for (ObjectName bean : beans) {
            LOG.info("bean:{}", bean.toString());
        }
    }

    /**
     * Ensure that the specified parent names are registered. Note that these
     * are components of the name. It waits in a loop up to 60 seconds before
     * failing if there is a mismatch. This will return the beans which are not
     * matched.
     *
     * https://issues.apache.org/jira/browse/ZOOKEEPER-1858
     *
     * @param expectedNames
     *            - expected beans
     * @return the beans which are not matched with the given expected names
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public static Set<ObjectName> ensureParent(String... expectedNames) throws IOException, InterruptedException {
        LOG.info("ensureParent:{}", Arrays.toString(expectedNames));

        Set<ObjectName> beans;
        int nTry = 0;
        Set<ObjectName> found = new HashSet<ObjectName>();
        do {
            if (nTry++ > 0) {
                Thread.sleep(500);
            }
            try {
                beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }
            found.clear();
            for (String name : expectedNames) {
                LOG.info("expect:{}", name);
                for (ObjectName bean : beans) {
                    // check the existence of name in bean
                    if (compare(bean.toString(), name)) {
                        LOG.info("found:{} {}", name, bean);
                        found.add(bean);
                        break;
                    }
                }
                beans.removeAll(found);
            }
        } while (expectedNames.length != found.size() && nTry < 120);
        assertEquals("expected " + Arrays.toString(expectedNames), expectedNames.length, found.size());
        return beans;
    }

    /**
     * Ensure that the specified bean name and its attribute is registered. Note
     * that these are components of the name. It waits in a loop up to 60
     * seconds before failing if there is a mismatch. This will return the beans
     * which are not matched.
     *
     * @param expectedName
     *            - expected bean
     * @param expectedAttribute
     *            - expected attribute
     * @return the value of the attribute
     *
     * @throws Exception
     */
    public static Object ensureBeanAttribute(String expectedName, String expectedAttribute) throws Exception {
        String value = "";
        LOG.info("ensure bean:{}, attribute:{}", expectedName, expectedAttribute);

        Set<ObjectName> beans;
        int nTry = 0;
        do {
            if (nTry++ > 0) {
                Thread.sleep(500);
            }
            try {
                beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }
            LOG.info("expect:{}", expectedName);
            for (ObjectName bean : beans) {
                // check the existence of name in bean
                if (bean.toString().equals(expectedName)) {
                    LOG.info("found:{} {}", expectedName, bean);
                    return conn().getAttribute(bean, expectedAttribute);
                }
            }
        } while (nTry < 120);
        fail("Failed to find bean:" + expectedName + ", attribute:" + expectedAttribute);
        return value;
    }

    /**
     * Comparing that the given name exists in the bean. For component beans,
     * the component name will be present at the end of the bean name
     *
     * For example 'StandaloneServer' will present in the bean name like
     * 'org.apache.ZooKeeperService:name0=StandaloneServer_port-1'
     */
    private static boolean compare(String bean, String name) {
        String[] names = bean.split("=");
        return names.length > 0 && names[names.length - 1].contains(name);
    }

    static Pattern standaloneRegEx = Pattern.compile("^org.apache.ZooKeeperService:name0=StandaloneServer_port-?\\d+$");
    static Pattern instanceRegEx = Pattern.compile("^org.apache.ZooKeeperService:name0=ReplicatedServer_id(\\d+)"
                                                           + ",name1=replica.(\\d+),name2=(Follower|Leader)$");
    static Pattern observerRegEx = Pattern.compile("^org.apache.ZooKeeperService:name0=ReplicatedServer_id(-?\\d+)"
                                                           + ",name1=replica.(-?\\d+),name2=(StandaloneServer_port-?\\d+)$");
    static List<Pattern> beanPatterns = Arrays.asList(standaloneRegEx, instanceRegEx, observerRegEx);

    public static List<ObjectName> getServerBeans() throws IOException {
        ArrayList<ObjectName> serverBeans = new ArrayList<>();
        Set<ObjectName> beans;
        try {
            beans = conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
        for (ObjectName bean : beans) {
            String name = bean.toString();
            LOG.info("bean:{}", name);
            for (Pattern pattern : beanPatterns) {
                if (pattern.matcher(name).find()) {
                    serverBeans.add(bean);
                }
            }
        }
        return serverBeans;
    }

    public static ObjectName getServerBean() throws Exception {
        List<ObjectName> serverBeans = getServerBeans();
        if (serverBeans.size() != 1) {
            throw new RuntimeException("Unable to find one and only one server bean");
        }
        return serverBeans.get(0);
    }

}
