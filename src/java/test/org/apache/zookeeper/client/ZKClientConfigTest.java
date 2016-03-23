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

package org.apache.zookeeper.client;

import static org.apache.zookeeper.client.ZKClientConfig.DISABLE_AUTO_WATCH_RESET;
import static org.apache.zookeeper.client.ZKClientConfig.ENABLE_CLIENT_SASL_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.LOGIN_CONTEXT_NAME_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.SECURE_CLIENT;
import static org.apache.zookeeper.client.ZKClientConfig.ZK_SASL_CLIENT_USERNAME;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_SERVER_REALM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.Test;

public class ZKClientConfigTest {

    @Test
    public void testDefaultConfiguration() {

        String[] values = new String[] { "zookeeper1", "Client1", "true", "zookeeper/hadoop.hadoop.com", "true",
                "ClientCnxnSocketNetty", "true" };
        String[] properties = new String[] { ZK_SASL_CLIENT_USERNAME, LOGIN_CONTEXT_NAME_KEY, ENABLE_CLIENT_SASL_KEY,
                ZOOKEEPER_SERVER_REALM, DISABLE_AUTO_WATCH_RESET, ZOOKEEPER_CLIENT_CNXN_SOCKET, SECURE_CLIENT };
        assertEquals(properties.length,values.length);
        for (int i = 0; i < properties.length; i++) {
            String prop = properties[i];
            System.setProperty(prop, values[i]);
        }
        /**
         * ZKClientConfig should get initialized with system properties
         */
        ZKClientConfig conf = new ZKClientConfig();
        for (int i = 0; i < properties.length; i++) {
            String prop = properties[i];
            String result = conf.getProperty(prop);
            assertEquals(values[i], result);
        }

        /**
         * clear properties
         */
        for (int i = 0; i < properties.length; i++) {
            String prop = properties[i];
            System.clearProperty(prop);
        }

        conf = new ZKClientConfig();
        /**
         * test that all the properties are null
         */
        for (int i = 0; i < properties.length; i++) {
            String prop = properties[i];
            String result = conf.getProperty(prop);
            assertNull(result);
        }

    }

    @Test
    public void testSystemProprtyValue() {
        String clientName = "zookeeper1";
        System.setProperty(ZK_SASL_CLIENT_USERNAME, clientName);

        ZKClientConfig conf = new ZKClientConfig();
        assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), clientName);

        String newClientName = "zookeeper2";
        conf.setProperty(ZK_SASL_CLIENT_USERNAME, newClientName);

        assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), newClientName);
    }

    @Test
    public void testReadConfigurationFile() throws IOException, ConfigException {
        File file = new File("test.conf");
        OutputStream io = new FileOutputStream(file);
        try {
            io.write((ENABLE_CLIENT_SASL_KEY + "=true\n").getBytes());
            io.write((ZK_SASL_CLIENT_USERNAME + "=ZK\n").getBytes());
            io.write((LOGIN_CONTEXT_NAME_KEY + "=MyClient\n").getBytes());
            io.write((ZOOKEEPER_SERVER_REALM + "=HADOOP.COM\n").getBytes());
            io.write(("dummyProperty=dummyValue").getBytes());
            io.flush();
        } finally {
            io.close();
        }

        try {
            ZKClientConfig conf = new ZKClientConfig();
            conf.addConfiguration(file.getAbsolutePath());
            assertEquals(conf.getProperty(ENABLE_CLIENT_SASL_KEY), "true");
            assertEquals(conf.getProperty(ZK_SASL_CLIENT_USERNAME), "ZK");
            assertEquals(conf.getProperty(LOGIN_CONTEXT_NAME_KEY), "MyClient");
            assertEquals(conf.getProperty(ZOOKEEPER_SERVER_REALM), "HADOOP.COM");
            assertNotNull(conf.getProperty("dummyProperty"));
        } finally {
            if (file != null) {
                file.delete();
            }
        }

    }

    @Test
    public void testSetConfiguration() {
        ZKClientConfig conf = new ZKClientConfig();
        String defaultValue = conf.getProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY,
                ZKClientConfig.ENABLE_CLIENT_SASL_DEFAULT);
        if (defaultValue.equals("true")) {
            conf.setProperty(ENABLE_CLIENT_SASL_KEY, "false");
        } else {
            conf.setProperty(ENABLE_CLIENT_SASL_KEY, "true");
        }
        assertTrue(conf.getProperty(ENABLE_CLIENT_SASL_KEY) != defaultValue);
    }

}
