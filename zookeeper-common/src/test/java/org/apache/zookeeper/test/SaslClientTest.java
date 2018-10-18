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


import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
public class SaslClientTest extends ZKTestCase {

    private String existingPropertyValue = null;

    @Before
    public void setUp() {
        existingPropertyValue = System
                .getProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY);
    }

    @After
    public void tearDown() {
        // Restore the System property if it was set previously
        if (existingPropertyValue != null) {
            System.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY,
                    existingPropertyValue);
        }
    }

    @Test
    public void testSaslClientDisabled() {
        System.clearProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY);
        Assert.assertTrue("SASL client disabled",
                new ZKClientConfig().isSaslClientEnabled());

        for (String value : Arrays.asList("true", "TRUE")) {
            System.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY,
                    value);
            Assert.assertTrue("SASL client disabled",
                    new ZKClientConfig().isSaslClientEnabled());
        }

        for (String value : Arrays.asList("false", "FALSE")) {
            System.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY,
                    value);
            Assert.assertFalse("SASL client disabled",
                    new ZKClientConfig().isSaslClientEnabled());
        }
    }
}
