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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServerCnxnTest extends ClientBase {
    protected static final Logger LOG =
        LoggerFactory.getLogger(ServerCnxnTest.class);

    private static int cnxnTimeout = 1000;

    @Before
    public void setUp() throws Exception {
        System.setProperty(
            NIOServerCnxnFactory.ZOOKEEPER_NIO_SESSIONLESS_CNXN_TIMEOUT,
            Integer.toString(cnxnTimeout));
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        System.clearProperty(
            NIOServerCnxnFactory.ZOOKEEPER_NIO_SESSIONLESS_CNXN_TIMEOUT);
    }

    @Test
    public void testServerCnxnExpiry() throws Exception {
        verify("ruok", "imok");

        // Expiry time is (now/cnxnTimeout + 1)*cnxnTimeout
        // Range is (now + cnxnTimeout) to (now + 2*cnxnTimeout)
        // Add 1s buffer to be safe.
        String resp = sendRequest("ruok", 2 * cnxnTimeout + 1000);
        Assert.assertEquals("Connection should have closed", "", resp);
    }


    private void verify(String cmd, String expected) throws IOException {
        String resp = sendRequest(cmd, 0);
        LOG.info("cmd " + cmd + " expected " + expected + " got " + resp);
        Assert.assertTrue(resp.contains(expected));
    }

    private String sendRequest(String cmd, int delay) throws IOException {
        HostPort hpobj = ClientBase.parseHostPortList(hostPort).get(0);
        return send4LetterWord(hpobj.host, hpobj.port, cmd, delay);
    }

    private static String send4LetterWord(
        String host, int port, String cmd, int delay) throws IOException
    {
        LOG.info("connecting to " + host + " " + port);
        Socket sock = new Socket(host, port);
        BufferedReader reader = null;
        try {
            try {
                LOG.info("Sleeping for " + delay + "ms");
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // ignore
            }

            OutputStream outstream = sock.getOutputStream();
            outstream.write(cmd.getBytes());
            outstream.flush();
            // this replicates NC - close the output stream before reading
            sock.shutdownOutput();

            reader =
                    new BufferedReader(
                            new InputStreamReader(sock.getInputStream()));
            StringBuilder sb = readLine(reader);
            return sb.toString();
        } finally {
            sock.close();
            if (reader != null) {
                reader.close();
            }
        }
    }

    private static StringBuilder readLine(BufferedReader reader) {
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
        } catch (IOException ioe) {
            // During connection expiry the server will close the connection.
            // After the socket is closed, when the client tries to read a
            // line of text it will throw java.net.SocketException.
            // @see jira issue ZOOKEEPER-1862
            LOG.info("Connnection is expired", ioe);
        }
        return sb;
    }
}
