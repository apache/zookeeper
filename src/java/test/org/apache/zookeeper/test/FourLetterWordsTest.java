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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;

import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooKeeper;
import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FourLetterWordsTest extends ClientBase {
    protected static final Logger LOG =
        LoggerFactory.getLogger(FourLetterWordsTest.class);

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
        String sid = getHexSessionId(zk.getSessionId());

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
        verify("mntr", "zk_server_state\tstandalone");
        verify("mntr", "num_alive_connections");
        verify("stat", "Connections");
        verify("srvr", "Connections");
    }

    private String sendRequest(String cmd) throws IOException {
      HostPort hpobj = ClientBase.parseHostPortList(hostPort).get(0);
      return send4LetterWord(hpobj.host, hpobj.port, cmd);
    }

    private void verify(String cmd, String expected) throws IOException {
        String resp = sendRequest(cmd);
        LOG.info("cmd " + cmd + " expected " + expected + " got " + resp);
        Assert.assertTrue(resp.contains(expected));
    }
    
    @Test
    public void validateStatOutput() throws Exception {
        ZooKeeper zk1 = createClient();
        ZooKeeper zk2 = createClient();
        
        String resp = sendRequest("stat");
        BufferedReader in = new BufferedReader(new StringReader(resp));

        String line;
        // first line should be version info
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^.*\\s\\d+\\.\\d+\\.\\d+-.*$", line));
        Assert.assertTrue(Pattern.matches("^Clients:$", in.readLine()));

        int count = 0;
        while ((line = in.readLine()).length() > 0) {
            count++;
            Assert.assertTrue(Pattern.matches("^ /.*:\\d+\\[\\d+\\]\\(queued=\\d+,recved=\\d+,sent=\\d+\\)$", line));
        }
        // ensure at least the two clients we created are accounted for
        Assert.assertTrue(count >= 2);

        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Latency min/avg/max: \\d+/\\d+/\\d+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Received: \\d+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Sent: \\d+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Connections: \\d+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Outstanding: \\d+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Zxid: 0x[\\da-fA-F]+$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Mode: .*$", line));
        line = in.readLine();
        Assert.assertTrue(Pattern.matches("^Node count: \\d+$", line));

        zk1.close();
        zk2.close();
    }

    @Test
    public void validateConsOutput() throws Exception {
        ZooKeeper zk1 = createClient();
        ZooKeeper zk2 = createClient();
        
        String resp = sendRequest("cons");
        BufferedReader in = new BufferedReader(new StringReader(resp));

        String line;
        int count = 0;
        while ((line = in.readLine()) != null && line.length() > 0) {
            count++;
            Assert.assertTrue(line, Pattern.matches("^ /.*:\\d+\\[\\d+\\]\\(queued=\\d+,recved=\\d+,sent=\\d+.*\\)$", line));
        }
        // ensure at least the two clients we created are accounted for
        Assert.assertTrue(count >= 2);

        zk1.close();
        zk2.close();
    }
}
