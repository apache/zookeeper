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

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZOOKEEPER-1620 - Acceptor and Selector thread don't call selector.close()
 * causing fd leakage
 */
public class NIOConnectionFactoryFdLeakTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(NIOConnectionFactoryFdLeakTest.class);

    @Test
    public void testFileDescriptorLeak() throws Exception {

        OSMXBean osMbean = new OSMXBean();
        if (!osMbean.getUnix()) {
            LOG.info("Unable to run test on non-unix system");
            return;
        }

        long startFdCount = osMbean.getOpenFileDescriptorCount();
        LOG.info("Start fdcount is: {}", startFdCount);

        for (int i = 0; i < 50; ++i) {
            NIOServerCnxnFactory factory = new NIOServerCnxnFactory();
            factory.configure(new InetSocketAddress("127.0.0.1", PortAssignment.unique()), 10);
            factory.start();
            Thread.sleep(100);
            factory.shutdown();
        }

        long endFdCount = osMbean.getOpenFileDescriptorCount();
        LOG.info("End fdcount is: {}", endFdCount);

        // On my box, if selector.close() is not called fd diff is > 700.
        assertTrue(((endFdCount - startFdCount) < 50), "Possible fd leakage");
    }

}
