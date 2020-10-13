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

import java.io.IOException;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class which starts a ZooKeeperServer, a ZooKeeperServerController and the ControllerService.
 * Tests should either invoke this class as the main target of a new JVM process OR explicitly
 * start and stop a singleton of this class in their test process.
 */
public class ControllerService {
    private static final Logger LOG = LoggerFactory.getLogger(ControllerService.class);

    private ZooKeeperServerController controller;
    private CommandListener listener;

    protected QuorumPeerConfig config;
    private ServerCnxnFactory serverCnxnFactory = null;
    protected QuorumPeer quorumPeer = null;

    /**
     * Starts the ControllerService as a stand alone app. Useful for out of process testing
     * - such as during integration testing.
     */
    public static void main(String[] args) {
        ControllerServerConfig config;
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("Require config file as cmd line argument");
            } else {
                config = new ControllerServerConfig(args[0]);
            }
            new ControllerService().start(config);
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            System.err.println("Usage: TestControllerMain controller-port configfile");
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

    /**
     * Starts a new thread to run the controller (useful when this service is hosted in process
     * - such as during unit testing).
     */
    public Thread start(ControllerServerConfig controllerConfig) {
        this.config = controllerConfig;
        final ControllerService svc = this;

        Thread runner = new Thread(() -> {
            try {
                svc.run();
            } catch (Exception e) {
            }
        });
        runner.setDaemon(true);
        runner.start();
        return runner;
    }

    public synchronized void shutdown() {
        if (listener != null) {
            listener.close();
            listener = null;
        }

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
    }

    /**
     * Initializes an instance of the ZooKeeperServer, the ZooKeeperServerController, and a new
     * Http listener (CommandListener) for the controller.
     */
    protected void initService() throws IOException {
        ControllerServerConfig controllerConfig = (ControllerServerConfig) config;
        controllerConfig.ensureComplete();
        this.controller = new ZooKeeperServerController(controllerConfig);
        this.listener = new CommandListener(controller, controllerConfig);
        this.serverCnxnFactory = controller.getCnxnFactory();
    }

    protected void runServices() {
        this.controller.run();
    }

    protected void cleanup() {
        if (listener != null) {
            listener.close();
            listener = null;
        }
    }

    /**
     * Runs the main loop for this application but does not exit the process.
     */
    public void initializeAndRun(String[] args) throws QuorumPeerConfig.ConfigException {
        initConfig(args);
        run();
    }

    /**
     * Derived classes may override to do custom initialization of command line args.
     */
    protected void initConfig(String[] args) throws QuorumPeerConfig.ConfigException {
        if (args.length == 1) {
            config.parse(args[0]);
        }
    }

    /**
     * Run the app given a QuorumPeerConfig.
     *
     * @param config The quorum peer config.
     */
    public void runFromConfig(QuorumPeerConfig config) {
        LOG.info("Starting quorum peer from peer config");
        this.config = config;
        run();
    }

    protected void run() {
        try {
            initService();
        } catch (Exception ex) {
            LOG.error("Failed to start ControllerService.", ex);
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());

        }
        runServices();
        cleanup();
    }

    /**
     * Is the service up with all necessary initialization and port opening complete?
     *
     * @return true if the controller service is ready to use; false otherwise.
     */
    public boolean isReady() {
        return controller != null && controller.isReady();
    }
}
