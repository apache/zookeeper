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
import org.apache.zookeeper.server.command.FourLetterCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FourLetterWordsWhiteListTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(FourLetterWordsWhiteListTest.class);

    /*
     * ZOOKEEPER-2693: test white list of four letter words.
     * For 3.5.x default white list is empty. Verify that is
     * the case (except 'stat' command which is enabled in ClientBase
     * which other tests depend on.).
     */
    @Test
    @Timeout(value = 30)
    public void testFourLetterWordsAllDisabledByDefault() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "stat");
        startServer();

        // Default white list for 3.5.x is empty, so all command should fail.
        verifyAllCommandsFail();

        TestableZooKeeper zk = createClient();

        verifyAllCommandsFail();

        zk.getData("/", true, null);

        verifyAllCommandsFail();

        zk.close();

        verifyFuzzyMatch("stat", "Outstanding");
        verifyAllCommandsFail();
    }

    @Test
    @Timeout(value = 30)
    public void testFourLetterWordsEnableSomeCommands() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "stat, ruok, isro");
        startServer();
        // stat, ruok and isro are white listed.
        verifyFuzzyMatch("stat", "Outstanding");
        verifyExactMatch("ruok", "imok");
        verifyExactMatch("isro", "rw");

        // Rest of commands fail.
        verifyExactMatch("conf", generateExpectedMessage("conf"));
        verifyExactMatch("cons", generateExpectedMessage("cons"));
        verifyExactMatch("crst", generateExpectedMessage("crst"));
        verifyExactMatch("dirs", generateExpectedMessage("dirs"));
        verifyExactMatch("dump", generateExpectedMessage("dump"));
        verifyExactMatch("envi", generateExpectedMessage("envi"));
        verifyExactMatch("gtmk", generateExpectedMessage("gtmk"));
        verifyExactMatch("stmk", generateExpectedMessage("stmk"));
        verifyExactMatch("srst", generateExpectedMessage("srst"));
        verifyExactMatch("wchc", generateExpectedMessage("wchc"));
        verifyExactMatch("wchp", generateExpectedMessage("wchp"));
        verifyExactMatch("wchs", generateExpectedMessage("wchs"));
        verifyExactMatch("mntr", generateExpectedMessage("mntr"));
    }

    @Test
    @Timeout(value = 30)
    public void testISROEnabledWhenReadOnlyModeEnabled() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "stat");
        System.setProperty("readonlymode.enabled", "true");
        startServer();
        verifyExactMatch("isro", "rw");
        System.clearProperty("readonlymode.enabled");
    }

    @Test
    @Timeout(value = 30)
    public void testFourLetterWordsInvalidConfiguration() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "foo bar"
                + " foo,,, "
                + "bar :.,@#$%^&*() , , , , bar, bar, stat,        ");
        startServer();

        // Just make sure we are good when admin made some mistakes in config file.
        verifyAllCommandsFail();
        // But still, what's valid in white list will get through.
        verifyFuzzyMatch("stat", "Outstanding");
    }

    @Test
    @Timeout(value = 30)
    public void testFourLetterWordsEnableAllCommandsThroughAsterisk() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
        startServer();
        verifyAllCommandsSuccess();
    }

    @Test
    @Timeout(value = 30)
    public void testFourLetterWordsEnableAllCommandsThroughExplicitList() throws Exception {
        stopServer();
        FourLetterCommands.resetWhiteList();
        System.setProperty("zookeeper.4lw.commands.whitelist", "ruok, envi, conf, stat, srvr, cons, dump,"
                + "wchs, wchp, wchc, srst, crst, "
                + "dirs, mntr, gtmk, isro, stmk");
        startServer();
        verifyAllCommandsSuccess();
    }

    private void verifyAllCommandsSuccess() throws Exception {
        verifyExactMatch("ruok", "imok");
        verifyFuzzyMatch("envi", "java.version");
        verifyFuzzyMatch("conf", "clientPort");
        verifyFuzzyMatch("stat", "Outstanding");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", "queued");
        verifyFuzzyMatch("dump", "Session");
        verifyFuzzyMatch("wchs", "watches");
        verifyFuzzyMatch("wchp", "");
        verifyFuzzyMatch("wchc", "");

        verifyFuzzyMatch("srst", "reset");
        verifyFuzzyMatch("crst", "reset");

        verifyFuzzyMatch("stat", "Outstanding");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", "queued");
        verifyFuzzyMatch("gtmk", "306");
        verifyFuzzyMatch("isro", "rw");

        TestableZooKeeper zk = createClient();
        String sid = getHexSessionId(zk.getSessionId());

        verifyFuzzyMatch("stat", "queued");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", sid);
        verifyFuzzyMatch("dump", sid);
        verifyFuzzyMatch("dirs", "size");

        zk.getData("/", true, null);

        verifyFuzzyMatch("stat", "queued");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", sid);
        verifyFuzzyMatch("dump", sid);

        verifyFuzzyMatch("wchs", "watching 1");
        verifyFuzzyMatch("wchp", sid);
        verifyFuzzyMatch("wchc", sid);
        verifyFuzzyMatch("dirs", "size");
        zk.close();

        verifyExactMatch("ruok", "imok");
        verifyFuzzyMatch("envi", "java.version");
        verifyFuzzyMatch("conf", "clientPort");
        verifyFuzzyMatch("stat", "Outstanding");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", "queued");
        verifyFuzzyMatch("dump", "Session");
        verifyFuzzyMatch("wchs", "watch");
        verifyFuzzyMatch("wchp", "");
        verifyFuzzyMatch("wchc", "");

        verifyFuzzyMatch("srst", "reset");
        verifyFuzzyMatch("crst", "reset");

        verifyFuzzyMatch("stat", "Outstanding");
        verifyFuzzyMatch("srvr", "Outstanding");
        verifyFuzzyMatch("cons", "queued");
        verifyFuzzyMatch("mntr", "zk_server_state\tstandalone");
        verifyFuzzyMatch("mntr", "num_alive_connections");
        verifyFuzzyMatch("stat", "Connections");
        verifyFuzzyMatch("srvr", "Connections");
        verifyFuzzyMatch("dirs", "size");
    }

    private void verifyAllCommandsFail() throws Exception {
        verifyExactMatch("ruok", generateExpectedMessage("ruok"));
        verifyExactMatch("conf", generateExpectedMessage("conf"));
        verifyExactMatch("cons", generateExpectedMessage("cons"));
        verifyExactMatch("crst", generateExpectedMessage("crst"));
        verifyExactMatch("dirs", generateExpectedMessage("dirs"));
        verifyExactMatch("dump", generateExpectedMessage("dump"));
        verifyExactMatch("envi", generateExpectedMessage("envi"));
        verifyExactMatch("gtmk", generateExpectedMessage("gtmk"));
        verifyExactMatch("stmk", generateExpectedMessage("stmk"));
        verifyExactMatch("srst", generateExpectedMessage("srst"));
        verifyExactMatch("wchc", generateExpectedMessage("wchc"));
        verifyExactMatch("wchp", generateExpectedMessage("wchp"));
        verifyExactMatch("wchs", generateExpectedMessage("wchs"));
        verifyExactMatch("mntr", generateExpectedMessage("mntr"));
        verifyExactMatch("isro", generateExpectedMessage("isro"));

        // srvr is enabled by default due to the sad fact zkServer.sh uses it.
        verifyFuzzyMatch("srvr", "Outstanding");
    }

    private String sendRequest(String cmd) throws IOException, SSLContextException {
        HostPort hpobj = ClientBase.parseHostPortList(hostPort).get(0);
        return send4LetterWord(hpobj.host, hpobj.port, cmd);
    }

    private void verifyFuzzyMatch(String cmd, String expected) throws IOException, SSLContextException {
        String resp = sendRequest(cmd);
        LOG.info("cmd {} expected {} got {}", cmd, expected, resp);
        assertTrue(resp.contains(expected));
    }

    private String generateExpectedMessage(String command) {
        return command + " is not executed because it is not in the whitelist.";
    }

    private void verifyExactMatch(String cmd, String expected) throws IOException, SSLContextException {
        String resp = sendRequest(cmd);
        LOG.info("cmd {} expected an exact match of {}; got {}", cmd, expected, resp);
        assertTrue(resp.trim().equals(expected));
    }

}
