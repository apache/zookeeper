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
package org.apache.zookeeper.common;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import javax.net.ssl.SSLContext;

public class X509UtilTest {

    @Test
    public void testCreateSSLContext_invalidCustomSSLContextClass() {
        ZKConfig zkConfig = new ZKConfig();
        ClientX509Util clientX509Util = new ClientX509Util();
        zkConfig.setProperty(clientX509Util.getSslClientContextProperty(), String.class.getCanonicalName());
        try {
            clientX509Util.createSSLContext(zkConfig);
            fail("SSLContextException expected.");
        } catch (X509Exception.SSLContextException e) {
            assertTrue(e.getMessage().contains(clientX509Util.getSslClientContextProperty()));
        }
    }

    @Test
    public void testCreateSSLContext_validCustomSSLContextClass() throws X509Exception.SSLContextException {
        ZKConfig zkConfig = new ZKConfig();
        ClientX509Util clientX509Util = new ClientX509Util();
        zkConfig.setProperty(clientX509Util.getSslClientContextProperty(), ZKTestClientSSLContext.class.getCanonicalName());
        final SSLContext sslContext = clientX509Util.createSSLContext(zkConfig);
        assertNull(sslContext);
    }

}