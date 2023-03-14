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

/**
 * Set of commands that this controller can execute. Commands are comprised
 * of an action and an optional parameter specific to that action.
 */
public class ControlCommand {
    /**
     * Actions available to the controller
      */
    public enum Action {
        // Simple "are you there" ping to confirm the controller is up and running.
        PING,
        // Shutdown everything, including CommandListener, ControllerService, Controller and the ZooKeeperServer.
        SHUTDOWN,
        // Close a connection triggering a client disconnect (and then reconnect attempt).
        // No parameter indicates close all connections. Optional parameter indicates a specific session id (as long).
        CLOSECONNECTION,
        // More actions go here in the future (force drop sessions, etc).
        EXPIRESESSION,
        // Reject all future connections. No parameter required.
        REJECTCONNECTIONS,
        // Add latency to server replies.
        // Optional parameter indicates time in milliseconds to delay
        // (default = 1 second).
        ADDDELAY,
        // Fail requests.
        // Optional parameter indicates how many requests to fail.
        // (default = all requests until RESET).
        FAILREQUESTS,
        // Process requests but do not send a response.
        // Optional parameter indicates how many requests to fail.
        // (default = all requests until RESET).
        NORESPONSE,
        // No parameter indicates fail all requests.
        // Optional parameter indicates undo all the chaotic action commands
        // (reject connections, add delay, fail requests, eat requests and so on...).
        RESET,
        // Force the quorum to elect a new leader.
        ELECTNEWLEADER,
        // More actions go here in the future...
    }

    public static final String ENDPOINT = "command";
    public static final String ENDPOINT_PREFIX = ENDPOINT + "/";

    private Action action;
    public Action getAction() {
        return action;
    }

    private String parameter;
    protected String getParameter() {
        return parameter;
    }

    public ControlCommand(Action action) {
        this(action, null);
    }

    public ControlCommand(Action action, String param) {
        this.action = action;
        this.parameter = param;
    }

    /**
     * Create a REST command uri.
     * @param action The 'verb' of the command.
     * @param parameter The optional parameter.
     * @return A string to send to the server as the end of the Uri.
     */
    public static String createCommandUri(Action action, String parameter) {
        return ENDPOINT_PREFIX + action.toString() + (parameter != null && !parameter.isEmpty() ? "/" + parameter : "");
    }

    /**
     * Parse a Uri into the required Command action and parameter.
     * @param commandUri the properly formatted Uri.
     */
    public static ControlCommand parseUri(String commandUri) {
        if (commandUri == null) {
            throw new IllegalArgumentException("commandUri can't be null.");
        }

        if (!commandUri.startsWith(ENDPOINT_PREFIX)) {
            throw new IllegalArgumentException("Missing required prefix: " + ENDPOINT_PREFIX);
        }

        String uri = commandUri.substring(ENDPOINT_PREFIX.length());
        String name;
        String param;

        int separatorIndex = uri.indexOf('/');
        if (separatorIndex < 0) {
            name = uri;
            param = null;
        } else {
            name = uri.substring(0, separatorIndex);
            param = uri.substring(separatorIndex + 1);
        }

        return new ControlCommand(Action.valueOf(name.toUpperCase()), param);
    }
}
