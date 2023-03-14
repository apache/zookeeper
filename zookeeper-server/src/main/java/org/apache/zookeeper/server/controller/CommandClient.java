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

package org.apache.zookeeper.server.controller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A convenient helper to send controller command to ControllerService.
 */
public class CommandClient {
    private final int requestTimeoutInMs;
    private static final int DEFAULT_TIMEOUT = 10000;
    private static final Logger LOG = LoggerFactory.getLogger(CommandClient.class);
    private final int hostPort;
    private final String hostName;
    private HttpClient client;
    private boolean started = false;

    /**
     * Instantiate a client configured to send requests to localhost.
     * @param localHostPort Port that the localhost CommandListener is listening on.
     * @param requestTimeoutInMs Timeout in ms for synchronous requests to timeout.
     */
    public CommandClient(int localHostPort, int requestTimeoutInMs) {
        this.client = new HttpClient();
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.hostName = "localhost";
        this.hostPort = localHostPort;
    }

    /**
     * Instantiate a client configured to send requests to the specified host address.
     * @param  hostAddress The host address of the listening server.
     * @param  requestTimeoutInMs Timeout in ms for synchronous requests to timeout.
     */
    public CommandClient(InetSocketAddress hostAddress, int requestTimeoutInMs) {
        this.client = new HttpClient();
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.hostName = hostAddress.getHostName();
        this.hostPort = hostAddress.getPort();
    }

    public CommandClient(int localhostPort) {
        this(localhostPort, DEFAULT_TIMEOUT);
    }

    public synchronized void close() {
        try {
            if (client != null) {
                client.stop();
                client = null;
            }
        } catch (Exception ex) {
            LOG.warn("Exception during shutdown", ex);
        }
    }

    /**
     * Send a command with no parameters to the server and wait for a response.
     * Returns true if we received a good (200) response and false otherwise.
     */
    public boolean trySendCommand(ControlCommand.Action action) {
        return trySendCommand(action, null);
    }

    /**
     * Send a command with an optional command parameter to the server and wait for a response.
     * @param action The command Action to send.
     * @param commandParameter The command parameter, in the form of command/action/parameter.
     * @return true if we received a good (200) response and false otherwise.
     */
    public boolean trySendCommand(ControlCommand.Action action, String commandParameter)  {
        try {
            if (!started) {
                client.start();
                started = true;
            }
            ContentResponse response = sendCommand(action, commandParameter);
            LOG.info("Received {} response from the server", response);
            return (response.getStatus() == 200);
        } catch (InterruptedException | IOException ex) {
            LOG.warn("Failed to get response from server", ex);
        } catch (Exception ex) {
            LOG.error("Unknown exception when sending command", ex);
        }

        return false;
    }

    /**
     * Send a command and optional command parameter to the server and block until receiving
     * a response.
     *
     * @param action The command Action to send.
     * @param commandParameter The command parameter, in the form of command/action/parameter.
     * @return The full response body from the CommandListener server.
     */
    public ContentResponse sendCommand(ControlCommand.Action action,
                                       String commandParameter) throws Exception {
        String command = String.format("%s%s:%s/%s", "http://",
            this.hostName, this.hostPort, ControlCommand.createCommandUri(action, commandParameter));
        ContentResponse response = this.client.newRequest(command).timeout(this.requestTimeoutInMs,
            TimeUnit.MILLISECONDS).send();
        LOG.info("Sent command {}", command);
        LOG.info("Response body {}", new String(response.getContent(), StandardCharsets.UTF_8));
        return response;
    }
}
