/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or morecontributor license agreements.  See the NOTICE file
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
import java.util.Set;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is intented to ensure the correct functionality of
 * {@link QuorumUtil} helper.
 */
public class QuorumUtilTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory
            .getLogger(QuorumUtilTest.class);

    /**
     * <p>
     * This test ensures that all JXM beans associated to a {@link QuorumPeer}
     * are unregistered when shuted down ({@link QuorumUtil#shutdown(int)}). It
     * allows a successfull restarting of several zookeeper servers (
     * {@link QuorumPeer}) running on the same JVM.
     * <p>
     * See ZOOKEEPER-1214 for details.
     */
    @Test
    public void validateAllMXBeanAreUnregistered() throws IOException {
        QuorumUtil qU = new QuorumUtil(1);
        LOG.info(">-->> Starting up all servers...");
        qU.startAll();
        LOG.info(">-->> Servers up and running...");

        int leaderIndex = qU.getLeaderServer();
        int firstFollowerIndex = 0;
        int secondFollowerIndex = 0;

        switch (leaderIndex) {
        case 1:
            firstFollowerIndex = 2;
            secondFollowerIndex = 3;
            break;
        case 2:
            firstFollowerIndex = 1;
            secondFollowerIndex = 3;
            break;
        case 3:
            firstFollowerIndex = 1;
            secondFollowerIndex = 2;
            break;

        default:
            Assert.fail("Unexpected leaderIndex value: " + leaderIndex);
            break;
        }

        LOG.info(">-->> Shuting down server [{}]", firstFollowerIndex);
        qU.shutdown(firstFollowerIndex);
        LOG.info(">-->> Shuting down server [{}]", secondFollowerIndex);
        qU.shutdown(secondFollowerIndex);
        LOG.info(">-->> Restarting server [{}]", firstFollowerIndex);
        qU.restart(firstFollowerIndex);
        LOG.info(">-->> Restarting server [{}]", secondFollowerIndex);
        qU.restart(secondFollowerIndex);

        qU.shutdownAll();
        Set<ZKMBeanInfo> pending = MBeanRegistry.getInstance()
                .getRegisteredBeans();
        Assert.assertTrue("The following beans should have been unregistered: "
                + pending, pending.isEmpty());
    }
}
