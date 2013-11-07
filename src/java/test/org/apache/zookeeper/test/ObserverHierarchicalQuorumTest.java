/* Licensed to the Apache Software Foundation (ASF) under one
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

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.Test;

/**
 * Mimics QuorumHierarchical test, but on an ensemble that includes 2 
 * observers.
 */

public class ObserverHierarchicalQuorumTest extends HierarchicalQuorumTest {
    private static final Logger LOG = Logger.getLogger(QuorumBase.class);
       
    /**
     * startServers(true) puts two observers into a 5 peer ensemble
     */
    void startServers() throws Exception {
        startServers(true);
    }
           
    protected void shutdown(QuorumPeer qp) {
        QuorumBase.shutdown(qp);
    }

    @Test
    public void testHierarchicalQuorum() throws Throwable {
        cht.runHammer(5, 10);
    }
}