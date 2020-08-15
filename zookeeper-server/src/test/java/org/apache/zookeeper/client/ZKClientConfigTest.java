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

package org.apache.zookeeper.client;

import static org.apache.zookeeper.client.ZKClientConfig.DISABLE_AUTO_WATCH_RESET;
import static org.apache.zookeeper.client.ZKClientConfig.ENABLE_CLIENT_SASL_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.LOGIN_CONTEXT_NAME_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.SECURE_CLIENT;
import static org.apache.zookeeper.client.ZKClientConfig.ZK_SASL_CLIENT_USERNAME;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_SERVER_REALM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ZKClientConfigTest {

    private static final File testData = new File(System.getProperty("test.data.dir", "src/test/resources/data"));

    @BeforeAll
    public static void init() {
        if (!testData.exists()) {
            testData.mkdirs();
        }
    }

    @Test
    @Timeout(value = 10)
    public void testDefaultConfiguration() {
        Map<String, String> properties = new HashMap<>();
        properties.put(ZK_SASL_CLIENT_USERNAME, "zookeeper1");
        properties.put(LOGIN_CONTEXT_NAME_KEY, "Client1");
        properties.put(ENABLE_CLIENT_SASL_KEY, "true");
        properties.put(ZOOKEEPER_SERVER_REALM, "zookeeper/hadoop.hadoop.com");
        properties.put(DISABLE_AUTO_WATCH_RESET, "true");
        properties.put(ZOOKEEPER_CLIENT_CNXN_SOCKET, "ClientCnxnSocketNetty");
        properties.put(SECURE_CLIENT, "true");

        for (Map.Entry<String, String> e : properties.entrySet()) {
            System.setProperty(e.getKey(), e.getValue());
        }
        /**
         * ZKClientConfig should get initialized with system properties
         */
        ZKClientConfig conf = new ZKClientConfig();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            assertEquals(e.getValue(), conf.getProperty(e.getKey()));
        }
        /**
         * clear properties
         */
        for (Map.Entry<String, String> e : properties.entrySet()) {
            System.clearProperty(e.getKey());
        }

        conf = new ZKClientConfig();
        /**
         * test that all the properties are null
         */
        for (Map.Entry<String, String> e : properties.entrySet()) {
            String result = conf.getProperty(e.getKey());
            assertNull(result);
        }
    }

    @Test
    @Timeout(value = 10)
    public void testSystemPropertyValue() {
        String clientName = "zookeeper1";
        System.setProperty(ZK_SASL_CLIENT_USERNAME, clientName);

        ZKClientConfig conf = new ZKClientConfig();
        assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), clientName);

        String newClientName = "zookeeper2";
        conf.setProperty(ZK_SASL_CLIENT_USERNAME, newClientName);

        assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), newClientName);
    }

    @Test
    @Timeout(value = 10)
    public void testReadConfigurationFile() throws IOException, ConfigException {
        File file = File.createTempFile("clientConfig", ".conf", testData);
        file.deleteOnExit();
        Properties clientConfProp = new Properties();
        clientConfProp.setProperty(ENABLE_CLIENT_SASL_KEY, "true");
        clientConfProp.setProperty(ZK_SASL_CLIENT_USERNAME, "ZK");
        clientConfProp.setProperty(LOGIN_CONTEXT_NAME_KEY, "MyClient");
        clientConfProp.setProperty(ZOOKEEPER_SERVER_REALM, "HADOOP.COM");
        clientConfProp.setProperty("dummyProperty", "dummyValue");
        OutputStream io = new FileOutputStream(file);
        try {
            clientConfProp.store(io, "Client Configurations");
        } finally {
            io.close();
        }

        ZKClientConfig conf = new ZKClientConfig();
        conf.addConfiguration(file.getAbsolutePath());
        assertEquals(conf.getProperty(ENABLE_CLIENT_SASL_KEY), "true");
        assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), "ZK");
        assertEquals(conf.getProperty(LOGIN_CONTEXT_NAME_KEY), "MyClient");
        assertEquals(conf.getProperty(ZOOKEEPER_SERVER_REALM), "HADOOP.COM");
        assertEquals(conf.getProperty("dummyProperty"), "dummyValue");

        // try to delete it now as we have done with the created file, why to
        // wait for deleteOnExit() deletion
        file.delete();
    }

    @Test
    @Timeout(value = 10)
    public void testSetConfiguration() {
        ZKClientConfig conf = new ZKClientConfig();
        String defaultValue = conf.getProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY, ZKClientConfig.ENABLE_CLIENT_SASL_DEFAULT);
        if (defaultValue.equals("true")) {
            conf.setProperty(ENABLE_CLIENT_SASL_KEY, "false");
        } else {
            conf.setProperty(ENABLE_CLIENT_SASL_KEY, "true");
        }
        assertTrue(conf.getProperty(ENABLE_CLIENT_SASL_KEY) != defaultValue);
    }

    @Test
    @Timeout(value = 10)
    public void testIntegerRetrievalFromProperty() {
        ZKClientConfig conf = new ZKClientConfig();
        String prop = "UnSetProperty" + System.currentTimeMillis();
        int defaultValue = 100;
        // property is not set we should get the default value
        int result = conf.getInt(prop, defaultValue);
        assertEquals(defaultValue, result);

        // property is set but can not be parsed to int, we should get the
        // NumberFormatException
        conf.setProperty(ZKConfig.JUTE_MAXBUFFER, "InvlaidIntValue123");
        try {
            result = conf.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
            fail("NumberFormatException is expected");
        } catch (NumberFormatException exception) {
            // do nothing
        }
        assertEquals(defaultValue, result);

        // property is set to an valid int, we should get the set value
        int value = ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT;
        conf.setProperty(ZKConfig.JUTE_MAXBUFFER, Integer.toString(value));
        result = conf.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
        assertEquals(value, result);

        // property is set but with white spaces
        value = 12345;
        conf.setProperty(ZKConfig.JUTE_MAXBUFFER, " " + value + " ");
        result = conf.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
        assertEquals(value, result);
    }

    @Test
    @Timeout(value = 10)
    public void testIntegerRetrievalFromHexadecimalProperty() {
        int hexaValue = 0x3000000;
        String wrongValue = "0xwel";
        int defaultValue = 100;
        // property is set in hexadecimal value
        ZKClientConfig zkClientConfig = new ZKClientConfig();
        zkClientConfig.setProperty(ZKConfig.JUTE_MAXBUFFER,
                Integer.toString(hexaValue));
        int result = zkClientConfig.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
        assertEquals(result, hexaValue);
        zkClientConfig.setProperty(ZKConfig.JUTE_MAXBUFFER,
                wrongValue);
        try {
            result = zkClientConfig.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
            fail("NumberFormatException is expected");
        } catch (NumberFormatException exception) {
            // do nothing
        }
        zkClientConfig.setProperty(ZKConfig.JUTE_MAXBUFFER,
                " " + hexaValue + " ");
        result = zkClientConfig.getInt(ZKConfig.JUTE_MAXBUFFER, defaultValue);
        assertEquals(result, hexaValue);
    }

}
