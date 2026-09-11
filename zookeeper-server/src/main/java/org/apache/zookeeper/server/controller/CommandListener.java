/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.controller;

import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.util.ServiceUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTP server listening to incoming controller commands sent from CommandClient (or any of your favorite REST client
 * ) and dispatching the command to the ZooKeeperServerController for execution.
 */
public class CommandListener {
    private static final Logger LOG = LoggerFactory.getLogger(CommandListener.class);

    private ZooKeeperServerController controller;
    private Server server;

    public CommandListener(ZooKeeperServerController controller, ControllerServerConfig config) {
        try {
            this.controller = controller;

            String host = config.getControllerAddress().getHostName();
            int port = config.getControllerAddress().getPort();

            server = new Server(port);
            LOG.info("CommandListener server host: {} with port: {}", host, port);
            server.setHandler(new CommandHandler());
            server.start();
        } catch (Exception ex) {
            LOG.error("Failed to instantiate CommandListener.", ex);
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

    public void close() {
        try {
            if (server != null) {
                server.stop();
                server = null;
            }
        } catch (Exception ex) {
            LOG.warn("Exception during shutdown CommandListener server", ex);
        }
    }

    private class CommandHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            // Extract command string from request path. Remove leading '/'.
            String path = Request.getPathInContext(request);
            String commandStr = path.startsWith("/") ? path.substring(1) : path;
            int responseCode;
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");

            try {
                ControlCommand command = ControlCommand.parseUri(commandStr);
                controller.processCommand(command);
                responseCode = HttpStatus.OK_200;
            } catch (IllegalArgumentException ex) {
                LOG.error("Bad argument or command", ex);
                responseCode = HttpStatus.BAD_REQUEST_400;
            } catch (Exception ex) {
                LOG.error("Failed processing the request", ex);
                Response.writeError(request, response, callback, ex);
                return true;
            }
            response.setStatus(responseCode);
            Content.Sink.write(response, true, commandStr + "\n", callback);
            LOG.info("CommandListener processed command {} with response code {}", commandStr, responseCode);
            return true;
        }
    }
}
