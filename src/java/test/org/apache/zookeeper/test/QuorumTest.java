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

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

public class QuorumTest extends QuorumBase {
    private static final Logger LOG = Logger.getLogger(QuorumTest.class);
    private QuorumBase qb = new QuorumBase();
    private final ClientTest ct = new ClientTest();
    
    @Before
    @Override
    protected void setUp() throws Exception {
        qb.setUp();        
        ct.hostPort = qb.hostPort;
    }
    
    protected void tearDown() throws Exception {
        qb.tearDown();
    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        ct.testDeleteWithChildren();
    }

    @Test
    public void testHammerBasic() throws Throwable {
        ct.testHammerBasic();
    }

    @Test
    public void testPing() throws Exception {
        ct.testPing();
    }

    @Test
    public void testSequentialNodeNames()
        throws IOException, InterruptedException, KeeperException
    {
        ct.testSequentialNodeNames();
    }

    @Test
    public void testACLs() throws Exception {
        ct.testACLs();
    }

    @Test
    public void testClientwithoutWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientwithoutWatcherObj();
    }

    @Test
    public void testClientWithWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientWithWatcherObj();
    }
    @Test
    public void testMultipleWatcherObjs() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testMutipleWatcherObjs();
    }

    // skip superhammer and clientcleanup as they are too expensive for quorum
}
