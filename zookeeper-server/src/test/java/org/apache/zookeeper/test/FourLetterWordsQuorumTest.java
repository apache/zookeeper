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

import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FourLetterWordsQuorumTest extends QuorumBase {

    protected static final Logger LOG = LoggerFactory.getLogger(FourLetterWordsQuorumTest.class);

    /** Test the various four letter words */
    @Test
    public void testFourLetterWords() throws Exception {
        String[] servers = hostPort.split(",");
        for (String hp : servers) {
            verify(hp, "ruok", "imok");
            verify(hp, "envi", "java.version");
            verify(hp, "conf", "clientPort");
            verify(hp, "stat", "Outstanding");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", "queued");
            verify(hp, "dump", "Session");
            verify(hp, "wchs", "watches");
            verify(hp, "wchp", "");
            verify(hp, "wchc", "");

            verify(hp, "srst", "reset");
            verify(hp, "crst", "reset");

            verify(hp, "stat", "Outstanding");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", "queued");

            TestableZooKeeper zk = createClient(hp);
            String sid = getHexSessionId(zk.getSessionId());

            verify(hp, "stat", "queued");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", sid);
            verify(hp, "dump", sid);
            verify(hp, "dirs", "size");

            zk.getData("/", true, null);

            verify(hp, "stat", "queued");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", sid);
            verify(hp, "dump", sid);
            verify(hp, "wchs", "watching 1");
            verify(hp, "wchp", sid);
            verify(hp, "wchc", sid);
            verify(hp, "dirs", "size");

            zk.close();

            verify(hp, "ruok", "imok");
            verify(hp, "envi", "java.version");
            verify(hp, "conf", "clientPort");
            verify(hp, "stat", "Outstanding");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", "queued");
            verify(hp, "dump", "Session");
            verify(hp, "wchs", "watch");
            verify(hp, "wchp", "");
            verify(hp, "wchc", "");
            verify(hp, "dirs", "size");

            verify(hp, "srst", "reset");
            verify(hp, "crst", "reset");

            verify(hp, "stat", "Outstanding");
            verify(hp, "srvr", "Outstanding");
            verify(hp, "cons", "queued");

            verify(hp, "mntr", "zk_version\t");
        }
    }

    private void verify(String hp, String cmd, String expected) throws IOException, SSLContextException {
        for (HostPort hpobj : parseHostPortList(hp)) {
            String resp = send4LetterWord(hpobj.host, hpobj.port, cmd);
            LOG.info("cmd {} expected {} got {}", cmd, expected, resp);
            if (cmd.equals("dump")) {
                assertTrue(resp.contains(expected) || resp.contains("Sessions with Ephemerals"));
            } else {
                assertTrue(resp.contains(expected));
            }
        }
    }

}
