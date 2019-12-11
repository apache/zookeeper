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

package org.apache.zookeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.cli.CliCommand;
import org.apache.zookeeper.cli.CliException;
import org.apache.zookeeper.cli.CliWrapperException;
import org.apache.zookeeper.cli.LsCommand;
import org.apache.zookeeper.cli.MalformedCommandException;
import org.apache.zookeeper.cli.MalformedPathException;
import org.apache.zookeeper.cli.SyncCommand;
import org.apache.zookeeper.client.ConnectStringParser;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;

/**
 *
 * Testing ZooKeeper public methods
 *
 */
public class ZooKeeperTest extends ClientBase {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator", "\n");

    @Test
    public void testDeleteRecursive() throws IOException, InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();
        setupDataTree(zk);

        assertTrue(ZKUtil.deleteRecursive(zk, "/a/c", 1000));
        List<String> children = zk.getChildren("/a", false);
        assertEquals("1 children - c should be deleted ", 1, children.size());
        assertTrue(children.contains("b"));

        assertTrue(ZKUtil.deleteRecursive(zk, "/a", 1000));
        assertNull(zk.exists("/a", null));
    }

    @Test
    public void testDeleteRecursiveFail() throws IOException, InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();
        setupDataTree(zk);

        ACL deleteProtection = new ACL(ZooDefs.Perms.DELETE, new Id("digest", "user:tl+z3z0vO6PfPfEENfLF96E6pM0="/* password is test */));
        List<ACL> acls = Arrays.asList(new ACL(ZooDefs.Perms.READ, Ids.ANYONE_ID_UNSAFE), deleteProtection);

        // poison the well
        zk.create("/a/c/0/surprise", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(1, zk.getACL("/a/c/0", new Stat()).size());
        zk.setACL("/a/c/0", acls, -1);
        assertEquals(2, zk.getACL("/a/c/0", new Stat()).size());

        assertFalse(ZKUtil.deleteRecursive(zk, "/a/c", 1000));
        List<String> children = zk.getChildren("/a", false);
        assertEquals("2 children - c should fail to be deleted ", 2, children.size());
        assertTrue(children.contains("b"));

        assertTrue(ZKUtil.deleteRecursive(zk, "/a/b", 1000));
        children = zk.getChildren("/a", false);
        assertEquals("1 children - b should be deleted ", 1, children.size());

        // acquire immunity to poison
        zk.addAuthInfo(deleteProtection.getId().getScheme(), "user:test".getBytes());

        assertTrue(ZKUtil.deleteRecursive(zk, "/a", 1000));
        assertNull(zk.exists("/a", null));
    }

    private void setupDataTree(ZooKeeper zk) throws KeeperException, InterruptedException {
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        for (int i = 1000; i < 3000; ++i) {
            zk.create("/a/b/v/" + i, "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        zk.create("/a/c", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/c/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        for (int i = 0; i < 500; ++i) {
            zk.create("/a/c/" + i, "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        List<String> children = zk.getChildren("/a", false);

        assertEquals("2 children - b & c should be present ", 2, children.size());
        assertTrue(children.contains("b"));
        assertTrue(children.contains("c"));
    }

    @Test
    public void testDeleteRecursiveCli() throws IOException, InterruptedException, CliException, KeeperException {
        final ZooKeeper zk = createClient();
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v/1", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/c", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/c/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        List<String> children = zk.getChildren("/a", false);

        assertEquals("2 children - b & c should be present ", children.size(), 2);
        assertTrue(children.contains("b"));
        assertTrue(children.contains("c"));

        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring1 = "deleteall /a";
        zkMain.cl.parseCommand(cmdstring1);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
        assertNull(zk.exists("/a", null));
    }

    @Test
    public void testDeleteRecursiveAsync() throws IOException, InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v/1", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/c", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/c/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        for (int i = 0; i < 50; ++i) {
            zk.create("/a/c/" + i, "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        List<String> children = zk.getChildren("/a", false);

        assertEquals("2 children - b & c should be present ", children.size(), 2);
        assertTrue(children.contains("b"));
        assertTrue(children.contains("c"));

        VoidCallback cb = new VoidCallback() {

            @Override
            public void processResult(int rc, String path, Object ctx) {
                synchronized (ctx) {
                    ((AtomicInteger) ctx).set(4);
                    ctx.notify();
                }
            }

        };
        final AtomicInteger ctx = new AtomicInteger(3);
        ZKUtil.deleteRecursive(zk, "/a", cb, ctx);
        synchronized (ctx) {
            ctx.wait();
        }
        assertEquals(4, ctx.get());
    }

    @Test
    public void testStatWhenPathDoesNotExist() throws IOException, InterruptedException, MalformedCommandException {
        final ZooKeeper zk = createClient();
        ZooKeeperMain main = new ZooKeeperMain(zk);
        String cmdstring = "stat /invalidPath";
        main.cl.parseCommand(cmdstring);
        try {
            main.processZKCmd(main.cl);
            fail("As Node does not exist, command should fail by throwing No Node Exception.");
        } catch (CliException e) {
            assertEquals("Node does not exist: /invalidPath", e.getMessage());
        }
    }

    @Test
    public void testParseWithExtraSpaces() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "      ls       /  ";
        zkMain.cl.parseCommand(cmdstring);
        assertEquals("Spaces also considered as characters", zkMain.cl.getNumArguments(), 2);
        assertEquals("ls is not taken as first argument", zkMain.cl.getCmdArgument(0), "ls");
        assertEquals("/ is not taken as second argument", zkMain.cl.getCmdArgument(1), "/");
    }

    @Test
    public void testParseWithQuotes() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        for (String quoteChar : new String[]{"'", "\""}) {
            String cmdstring = String.format("create /node %1$squoted data%1$s", quoteChar);
            zkMain.cl.parseCommand(cmdstring);
            assertEquals("quotes combine arguments", zkMain.cl.getNumArguments(), 3);
            assertEquals("create is not taken as first argument", zkMain.cl.getCmdArgument(0), "create");
            assertEquals("/node is not taken as second argument", zkMain.cl.getCmdArgument(1), "/node");
            assertEquals("quoted data is not taken as third argument", zkMain.cl.getCmdArgument(2), "quoted data");
        }
    }

    @Test
    public void testParseWithMixedQuotes() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        for (String[] quoteChars : new String[][]{{"'", "\""}, {"\"", "'"}}) {
            String outerQuotes = quoteChars[0];
            String innerQuotes = quoteChars[1];
            String cmdstring = String.format("create /node %1$s%2$squoted data%2$s%1$s", outerQuotes, innerQuotes);
            zkMain.cl.parseCommand(cmdstring);
            assertEquals("quotes combine arguments", zkMain.cl.getNumArguments(), 3);
            assertEquals("create is not taken as first argument", zkMain.cl.getCmdArgument(0), "create");
            assertEquals("/node is not taken as second argument", zkMain.cl.getCmdArgument(1), "/node");
            assertEquals(
                "quoted data is not taken as third argument",
                zkMain.cl.getCmdArgument(2),
                innerQuotes + "quoted data" + innerQuotes);
        }
    }

    @Test
    public void testParseWithEmptyQuotes() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "create /node ''";
        zkMain.cl.parseCommand(cmdstring);
        assertEquals("empty quotes should produce arguments", zkMain.cl.getNumArguments(), 3);
        assertEquals("create is not taken as first argument", zkMain.cl.getCmdArgument(0), "create");
        assertEquals("/node is not taken as second argument", zkMain.cl.getCmdArgument(1), "/node");
        assertEquals("empty string is not taken as third argument", zkMain.cl.getCmdArgument(2), "");
    }

    @Test
    public void testParseWithMultipleQuotes() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "create /node '' ''";
        zkMain.cl.parseCommand(cmdstring);
        assertEquals("expected 5 arguments", zkMain.cl.getNumArguments(), 4);
        assertEquals("create is not taken as first argument", zkMain.cl.getCmdArgument(0), "create");
        assertEquals("/node is not taken as second argument", zkMain.cl.getCmdArgument(1), "/node");
        assertEquals("empty string is not taken as third argument", zkMain.cl.getCmdArgument(2), "");
        assertEquals("empty string is not taken as fourth argument", zkMain.cl.getCmdArgument(3), "");
    }

    @Test
    public void testNonexistantCommand() throws Exception {
        testInvalidCommand("cret -s /node1", 127);
    }

    @Test
    public void testCreateCommandWithoutPath() throws Exception {
        testInvalidCommand("create", 1);
    }

    @Test
    public void testCreateEphemeralCommandWithoutPath() throws Exception {
        testInvalidCommand("create -e ", 1);
    }

    @Test
    public void testCreateSequentialCommandWithoutPath() throws Exception {
        testInvalidCommand("create -s ", 1);
    }

    @Test
    public void testCreateEphemeralSequentialCommandWithoutPath() throws Exception {
        testInvalidCommand("create -s -e ", 1);
    }

    private void testInvalidCommand(String cmdString, int exitCode) throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        zkMain.cl.parseCommand(cmdString);

        // Verify that the exit code is set properly
        zkMain.processCmd(zkMain.cl);
        assertEquals(exitCode, zkMain.exitCode);

        // Verify that the correct exception is thrown
        try {
            zkMain.processZKCmd(zkMain.cl);
            fail();
        } catch (CliException e) {
            return;
        }
        fail("invalid command should throw CliException");
    }

    @Test
    public void testCreateNodeWithoutData() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        // create persistent sequential node
        String cmdstring = "create -s /node ";
        zkMain.cl.parseCommand(cmdstring);
        assertTrue("Doesn't create node without data", zkMain.processZKCmd(zkMain.cl));
        // create ephemeral node
        cmdstring = "create  -e /node ";
        zkMain.cl.parseCommand(cmdstring);
        assertTrue("Doesn't create node without data", zkMain.processZKCmd(zkMain.cl));
        // create ephemeral sequential node
        cmdstring = "create -s -e /node ";
        zkMain.cl.parseCommand(cmdstring);
        assertTrue("Doesn't create node without data", zkMain.processZKCmd(zkMain.cl));
        // creating ephemeral with wrong option.
        cmdstring = "create -s y /node";
        zkMain.cl.parseCommand(cmdstring);
        try {
            assertTrue("Created node with wrong option", zkMain.processZKCmd(zkMain.cl));
            fail("Created the node with wrong option should " + "throw Exception.");
        } catch (MalformedPathException e) {
            assertEquals("Path must start with / character", e.getMessage());
        }
    }

    @Test
    public void testACLWithExtraAgruments() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        // create persistent sequential node
        String cmdstring = "create -s /l data ip:10.18.52.144:cdrwa f g h";
        zkMain.cl.parseCommand(cmdstring);
        assertTrue("Not considering the extra arguments after the acls.", zkMain.processZKCmd(zkMain.cl));
    }

    @Test
    public void testCreatePersistentNode() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "create /node2";
        zkMain.cl.parseCommand(cmdstring);
        assertTrue("Not creating Persistent node.", zkMain.processZKCmd(zkMain.cl));
    }

    @Test
    public void testDelete() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring1 = "create -e /node2 data";
        String cmdstring2 = "delete /node2";
        String cmdstring3 = "ls /node2";
        zkMain.cl.parseCommand(cmdstring1);
        assertTrue(zkMain.processZKCmd(zkMain.cl));
        zkMain.cl.parseCommand(cmdstring2);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
        zkMain.cl.parseCommand(cmdstring3);
        assertFalse("", zkMain.processCmd(zkMain.cl));
    }

    @Test
    public void testDeleteNonexistantNode() throws Exception {
        testInvalidCommand("delete /blahblahblah", 1);
    }

    @Test
    public void testStatCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring1 = "create -e /node3 data";
        String cmdstring2 = "stat /node3";
        String cmdstring3 = "delete /node3";
        zkMain.cl.parseCommand(cmdstring1);
        assertTrue(zkMain.processZKCmd(zkMain.cl));
        zkMain.cl.parseCommand(cmdstring2);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
        zkMain.cl.parseCommand(cmdstring3);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
    }

    @Test
    public void testInvalidStatCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        // node doesn't exists
        String cmdstring1 = "stat /node123";
        zkMain.cl.parseCommand(cmdstring1);
        try {
            assertFalse(zkMain.processZKCmd(zkMain.cl));
            fail("Path doesn't exists so, command should fail.");
        } catch (CliWrapperException e) {
            assertEquals(KeeperException.Code.NONODE, ((KeeperException) e.getCause()).code());
        }
    }

    @Test
    public void testSetData() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring1 = "create -e /node4 data";
        String cmdstring2 = "set /node4 " + "data";
        String cmdstring3 = "delete /node4";
        Stat stat = new Stat();
        int version = 0;
        zkMain.cl.parseCommand(cmdstring1);
        assertTrue(zkMain.processZKCmd(zkMain.cl));
        stat = zk.exists("/node4", true);
        version = stat.getVersion();
        zkMain.cl.parseCommand(cmdstring2);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
        stat = zk.exists("/node4", true);
        assertEquals(version + 1, stat.getVersion());
        zkMain.cl.parseCommand(cmdstring3);
        assertFalse(zkMain.processZKCmd(zkMain.cl));
    }

    @Test
    public void testCheckInvalidAcls() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "create -s -e /node data ip:scheme:gggsd"; //invalid acl's

        // For Invalid ACls should not throw exception
        zkMain.executeLine(cmdstring);
    }

    @Test
    public void testDeleteWithInvalidVersionNo() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmdstring = "create -s -e /node1 data ";
        String cmdstring1 = "delete /node1 2"; //invalid dataversion no
        zkMain.executeLine(cmdstring);

        // For Invalid dataversion number should not throw exception
        zkMain.executeLine(cmdstring1);
    }

    @Test
    public void testCliCommandsNotEchoingUsage() throws Exception {
        // setup redirect out/err streams to get System.in/err, use this judiciously!
        final PrintStream systemErr = System.err; // get current err
        final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmd1 = "printwatches";
        zkMain.executeLine(cmd1);
        String cmd2 = "history";
        zkMain.executeLine(cmd2);
        String cmd3 = "redo";
        zkMain.executeLine(cmd3);
        // revert redirect of out/err streams - important step!
        System.setErr(systemErr);
        if (errContent.toString().contains("ZooKeeper -server host:port cmd args")) {
            fail("CLI commands (history, redo, connect, printwatches) display usage info!");
        }
    }

    // ZOOKEEPER-2467 : Testing negative number for redo command
    @Test
    public void testRedoWithNegativeCmdNumber() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String cmd1 = "redo -1";

        // setup redirect out/err streams to get System.in/err, use this
        // judiciously!
        final PrintStream systemErr = System.err; // get current err
        final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        try {
            zkMain.executeLine(cmd1);
            assertEquals("Command index out of range", errContent.toString().trim());
        } finally {
            // revert redirect of out/err streams - important step!
            System.setErr(systemErr);
        }
    }

    private static void runCommandExpect(CliCommand command, List<String> expectedResults) throws Exception {
        // call command and put result in byteStream
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(byteStream);
        command.setOut(out);
        command.exec();

        String result = byteStream.toString();
        assertTrue(result, result.contains(StringUtils.joinStrings(expectedResults, LINE_SEPARATOR)));
    }

    @Test
    public void testSortedLs() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);

        zkMain.executeLine("create /aa1");
        zkMain.executeLine("create /aa2");
        zkMain.executeLine("create /aa3");
        zkMain.executeLine("create /test1");
        zkMain.executeLine("create /zk1");

        LsCommand cmd = new LsCommand();
        cmd.setZk(zk);
        cmd.parse("ls /".split(" "));
        List<String> expected = new ArrayList<String>();
        expected.add("[aa1, aa2, aa3, test1, zk1, zookeeper]");
        runCommandExpect(cmd, expected);
    }

    @Test
    public void testLsrCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);

        zkMain.executeLine("create /a");
        zkMain.executeLine("create /a/b");
        zkMain.executeLine("create /a/c");
        zkMain.executeLine("create /a/b/d");
        zkMain.executeLine("create /a/c/e");
        zkMain.executeLine("create /a/f");

        LsCommand cmd = new LsCommand();
        cmd.setZk(zk);
        cmd.parse("ls -R /a".split(" "));

        List<String> expected = new ArrayList<String>();
        expected.add("/a");
        expected.add("/a/b");
        expected.add("/a/c");
        expected.add("/a/f");
        expected.add("/a/b/d");
        expected.add("/a/c/e");
        runCommandExpect(cmd, expected);
    }

    @Test
    public void testLsrRootCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);

        LsCommand cmd = new LsCommand();
        cmd.setZk(zk);
        cmd.parse("ls -R /".split(" "));

        List<String> expected = new ArrayList<String>();
        expected.add("/");
        expected.add("/zookeeper");
        runCommandExpect(cmd, expected);
    }

    @Test
    public void testLsrLeafCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);

        zkMain.executeLine("create /b");
        zkMain.executeLine("create /b/c");

        LsCommand cmd = new LsCommand();
        cmd.setZk(zk);
        cmd.parse("ls -R /b/c".split(" "));

        List<String> expected = new ArrayList<String>();
        expected.add("/b/c");
        runCommandExpect(cmd, expected);
    }

    @Test
    public void testLsrNonexistantZnodeCommand() throws Exception {
        final ZooKeeper zk = createClient();
        ZooKeeperMain zkMain = new ZooKeeperMain(zk);

        zkMain.executeLine("create /b");
        zkMain.executeLine("create /b/c");

        LsCommand cmd = new LsCommand();
        cmd.setZk(zk);
        cmd.parse("ls -R /b/c/d".split(" "));

        try {
            runCommandExpect(cmd, new ArrayList<String>());
            fail("Path doesn't exists so, command should fail.");
        } catch (CliWrapperException e) {
            assertEquals(KeeperException.Code.NONODE, ((KeeperException) e.getCause()).code());
        }
    }

    @Test
    public void testSetAclRecursive() throws Exception {
        final ZooKeeper zk = createClient();
        final byte[] EMPTY = new byte[0];

        zk.setData("/", EMPTY, -1);
        zk.create("/a", EMPTY, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b", EMPTY, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b/c", EMPTY, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/d", EMPTY, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/e", EMPTY, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        ZooKeeperMain zkMain = new ZooKeeperMain(zk);
        String setAclCommand = "setAcl -R /a world:anyone:r";
        zkMain.cl.parseCommand(setAclCommand);
        assertFalse(zkMain.processZKCmd(zkMain.cl));

        assertEquals(Ids.READ_ACL_UNSAFE, zk.getACL("/a", new Stat()));
        assertEquals(Ids.READ_ACL_UNSAFE, zk.getACL("/a/b", new Stat()));
        assertEquals(Ids.READ_ACL_UNSAFE, zk.getACL("/a/b/c", new Stat()));
        assertEquals(Ids.READ_ACL_UNSAFE, zk.getACL("/a/d", new Stat()));
        // /e is unset, its acl should remain the same.
        assertEquals(Ids.OPEN_ACL_UNSAFE, zk.getACL("/e", new Stat()));
    }

    @Test
    public void testClientReconnectWithZKClientConfig() throws Exception {
        ZooKeeper zk = null;
        ZooKeeper newZKClient = null;
        try {
            zk = createClient();
            ZKClientConfig clientConfig = new ZKClientConfig();
            clientConfig.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
            CountdownWatcher watcher = new CountdownWatcher();
            HostProvider aHostProvider = new StaticHostProvider(new ConnectStringParser(hostPort).getServerAddresses());
            newZKClient = new ZooKeeper(
                hostPort,
                zk.getSessionTimeout(),
                watcher,
                zk.getSessionId(),
                zk.getSessionPasswd(),
                false,
                aHostProvider,
                clientConfig);
            watcher.waitForConnected(CONNECTION_TIMEOUT);
            assertEquals("Old client session id and new clinet session id must be same", zk.getSessionId(), newZKClient.getSessionId());
        } finally {
            zk.close();
            newZKClient.close();
        }
    }

    @Test
    public void testSyncCommand() throws Exception {
        final ZooKeeper zk = createClient();
        SyncCommand cmd = new SyncCommand();
        cmd.setZk(zk);
        cmd.parse("sync /".split(" "));
        List<String> expected = new ArrayList<String>();
        expected.add("Sync is OK");

        runCommandExpect(cmd, expected);
    }

}
