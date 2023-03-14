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

package org.apache.zookeeper.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@InterfaceAudience.Public
public class FourLetterWordMain {

    //in milliseconds, socket should connect/read within this period otherwise SocketTimeoutException
    private static final int DEFAULT_SOCKET_TIMEOUT = 5000;
    protected static final Logger LOG = LoggerFactory.getLogger(FourLetterWordMain.class);
    /**
     * Send the 4letterword
     * @param host the destination host
     * @param port the destination port
     * @param cmd the 4letterword
     * @return server response
     * @throws java.io.IOException
     * @throws SSLContextException
     */
    public static String send4LetterWord(String host, int port, String cmd) throws IOException, SSLContextException {
        return send4LetterWord(host, port, cmd, false, DEFAULT_SOCKET_TIMEOUT);
    }

    /**
     * Send the 4letterword
     * @param host the destination host
     * @param port the destination port
     * @param cmd the 4letterword
     * @param secure whether to use SSL
     * @return server response
     * @throws java.io.IOException
     * @throws SSLContextException
     */
    public static String send4LetterWord(
        String host,
        int port,
        String cmd,
        boolean secure) throws IOException, SSLContextException {
        return send4LetterWord(host, port, cmd, secure, DEFAULT_SOCKET_TIMEOUT);
    }

    /**
     * Send the 4letterword
     * @param host the destination host
     * @param port the destination port
     * @param cmd the 4letterword
     * @param secure whether to use SSL
     * @param timeout in milliseconds, maximum time to wait while connecting/reading data
     * @return server response
     * @throws java.io.IOException
     * @throws SSLContextException
     */
    public static String send4LetterWord(
        String host,
        int port,
        String cmd,
        boolean secure,
        int timeout) throws IOException, SSLContextException {
        LOG.info("connecting to {} {}", host, port);
        Socket sock;
        InetSocketAddress hostaddress = host != null
            ? new InetSocketAddress(host, port)
            : new InetSocketAddress(InetAddress.getByName(null), port);
        if (secure) {
            LOG.info("using secure socket");
            try (X509Util x509Util = new ClientX509Util()) {
                SSLContext sslContext = x509Util.getDefaultSSLContext();
                SSLSocketFactory socketFactory = sslContext.getSocketFactory();
                SSLSocket sslSock = (SSLSocket) socketFactory.createSocket();
                sslSock.connect(hostaddress, timeout);
                sslSock.startHandshake();
                sock = sslSock;
            }
        } else {
            sock = new Socket();
            sock.connect(hostaddress, timeout);
        }
        sock.setSoTimeout(timeout);
        BufferedReader reader = null;
        try {
            OutputStream outstream = sock.getOutputStream();
            outstream.write(cmd.getBytes(UTF_8));
            outstream.flush();

            // this replicates NC - close the output stream before reading
            if (!secure) {
                // SSL prohibits unilateral half-close
                sock.shutdownOutput();
            }

            reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (SocketTimeoutException e) {
            throw new IOException("Exception while executing four letter word: " + cmd, e);
        } finally {
            sock.close();
            if (reader != null) {
                reader.close();
            }
        }
    }

    public static void main(String[] args) throws IOException, SSLContextException {
        if (args.length == 3) {
            System.out.println(send4LetterWord(args[0], Integer.parseInt(args[1]), args[2]));
        } else if (args.length == 4) {
            System.out.println(send4LetterWord(args[0], Integer.parseInt(args[1]), args[2], Boolean.parseBoolean(args[3])));
        } else {
            System.out.println("Usage: FourLetterWordMain <host> <port> <cmd> <secure(optional)>");
        }
    }

}
