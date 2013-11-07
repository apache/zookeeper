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
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

public class QuorumHammerTest extends QuorumBase {
    protected static final Logger LOG = Logger.getLogger(QuorumHammerTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    protected final QuorumBase qb = new QuorumBase();
    protected final ClientHammerTest cht = new ClientHammerTest();

    @Before
    @Override
    protected void setUp() throws Exception {
        qb.setUp();
        cht.hostPort = qb.hostPort;
        cht.setUpAll();
    }

    protected void tearDown() throws Exception {
        cht.tearDownAll();
        qb.tearDown();
    }
    
    @Test
    public void testHammerBasic() throws Throwable {
        cht.testHammerBasic();
    }
}
