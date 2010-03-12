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
import org.apache.zookeeper.TestableZooKeeper;
import org.junit.Test;

public class FourLetterWordsTest extends ClientBase {
    protected static final Logger LOG =
        Logger.getLogger(FourLetterWordsTest.class);

    /** Test the various four letter words */
    @Test
    public void testFourLetterWords() throws Exception {
        verify("ruok", "imok");
        verify("envi", "java.version");
        verify("conf", "clientPort");
        verify("stat", "Outstanding");
        verify("srvr", "Outstanding");
        verify("cons", "queued");
        verify("dump", "Session");
        verify("wchs", "watches");
        verify("wchp", "");
        verify("wchc", "");

        verify("srst", "reset");
        verify("crst", "reset");

        verify("stat", "Outstanding");
        verify("srvr", "Outstanding");
        verify("cons", "queued");

        TestableZooKeeper zk = createClient();
        String sid = "0x" + Long.toHexString(zk.getSessionId());

        verify("stat", "queued");
        verify("srvr", "Outstanding");
        verify("cons", sid);
        verify("dump", sid);

        zk.getData("/", true, null);

        verify("stat", "queued");
        verify("srvr", "Outstanding");
        verify("cons", sid);
        verify("dump", sid);

        verify("wchs", "watching 1");
        verify("wchp", sid);
        verify("wchc", sid);
        zk.close();

        verify("ruok", "imok");
        verify("envi", "java.version");
        verify("conf", "clientPort");
        verify("stat", "Outstanding");
        verify("srvr", "Outstanding");
        verify("cons", "queued");
        verify("dump", "Session");
        verify("wchs", "watch");
        verify("wchp", "");
        verify("wchc", "");

        verify("srst", "reset");
        verify("crst", "reset");

        verify("stat", "Outstanding");
        verify("srvr", "Outstanding");
        verify("cons", "queued");
    }

    private void verify(String cmd, String expected) throws IOException {
        HostPort hpobj = parseHostPortList(hostPort).get(0);
        String resp = send4LetterWord(hpobj.host, hpobj.port, cmd);
        LOG.info("cmd " + cmd + " expected " + expected + " got " + resp);
        assertTrue(resp.contains(expected));
    }
}
