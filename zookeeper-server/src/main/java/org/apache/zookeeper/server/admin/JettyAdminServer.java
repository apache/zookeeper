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

package org.apache.zookeeper.server.admin;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.common.QuorumX509Util;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates a Jetty server for running Commands.
 *
 * Given the default settings, start a ZooKeeper server and visit
 * http://hostname:8080/commands for links to all registered commands. Visiting
 * http://hostname:8080/commands/commandname will execute the associated
 * Command and return the result in the body of the response. Any keyword
 * arguments to the command are specified with URL parameters (e.g.,
 * http://localhost:8080/commands/set_trace_mask?traceMask=306).
 *
 * @see Commands
 * @see CommandOutputter
 */
public class JettyAdminServer implements AdminServer {

    static final Logger LOG = LoggerFactory.getLogger(JettyAdminServer.class);

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_IDLE_TIMEOUT = 30000;
    public static final String DEFAULT_COMMAND_URL = "/commands";
    private static final String DEFAULT_ADDRESS = "0.0.0.0";
    public static final int DEFAULT_STS_MAX_AGE = 1 * 24 * 60 * 60;  // seconds in a day
    public static final int DEFAULT_HTTP_VERSION = 11;  // based on HttpVersion.java in jetty

    private final Server server;
    private final String address;
    private final int port;
    private final int idleTimeout;
    private final String commandUrl;
    private ZooKeeperServer zkServer;

    public JettyAdminServer() throws AdminServerException, IOException, GeneralSecurityException {
        this(
            System.getProperty("zookeeper.admin.serverAddress", DEFAULT_ADDRESS),
            Integer.getInteger("zookeeper.admin.serverPort", DEFAULT_PORT),
            Integer.getInteger("zookeeper.admin.idleTimeout", DEFAULT_IDLE_TIMEOUT),
            System.getProperty("zookeeper.admin.commandURL", DEFAULT_COMMAND_URL),
            Integer.getInteger("zookeeper.admin.httpVersion", DEFAULT_HTTP_VERSION),
            Boolean.getBoolean("zookeeper.admin.portUnification"),
            Boolean.getBoolean("zookeeper.admin.forceHttps"));
    }

    public JettyAdminServer(
        String address,
        int port,
        int timeout,
        String commandUrl,
        int httpVersion,
        boolean portUnification,
        boolean forceHttps) throws IOException, GeneralSecurityException {

        this.port = port;
        this.idleTimeout = timeout;
        this.commandUrl = commandUrl;
        this.address = address;

        server = new Server();
        ServerConnector connector = null;

        if (!portUnification && !forceHttps) {
            connector = new ServerConnector(server);
        } else {
            SecureRequestCustomizer customizer = new SecureRequestCustomizer();
            customizer.setStsMaxAge(DEFAULT_STS_MAX_AGE);
            customizer.setStsIncludeSubDomains(true);

            HttpConfiguration config = new HttpConfiguration();
            config.setSecureScheme("https");
            config.addCustomizer(customizer);

            try (QuorumX509Util x509Util = new QuorumX509Util()) {
                String privateKeyType = System.getProperty(x509Util.getSslKeystoreTypeProperty(), "");
                String privateKeyPath = System.getProperty(x509Util.getSslKeystoreLocationProperty(), "");
                String privateKeyPassword = System.getProperty(x509Util.getSslKeystorePasswdProperty(), "");
                String certAuthType = System.getProperty(x509Util.getSslTruststoreTypeProperty(), "");
                String certAuthPath = System.getProperty(x509Util.getSslTruststoreLocationProperty(), "");
                String certAuthPassword = System.getProperty(x509Util.getSslTruststorePasswdProperty(), "");
                KeyStore keyStore = null, trustStore = null;

                try {
                    keyStore = X509Util.loadKeyStore(privateKeyPath, privateKeyPassword, privateKeyType);
                    trustStore = X509Util.loadTrustStore(certAuthPath, certAuthPassword, certAuthType);
                    LOG.info("Successfully loaded private key from {}", privateKeyPath);
                    LOG.info("Successfully loaded certificate authority from {}", certAuthPath);
                } catch (Exception e) {
                    LOG.error("Failed to load authentication certificates for admin server.", e);
                    throw e;
                }

                SslContextFactory sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStore(keyStore);
                sslContextFactory.setKeyStorePassword(privateKeyPassword);
                sslContextFactory.setTrustStore(trustStore);
                sslContextFactory.setTrustStorePassword(certAuthPassword);

                if (forceHttps) {
                    connector = new ServerConnector(server,
                            new SslConnectionFactory(sslContextFactory, HttpVersion.fromVersion(httpVersion).asString()),
                            new HttpConnectionFactory(config));
                } else {
                    connector = new ServerConnector(
                            server,
                            new UnifiedConnectionFactory(sslContextFactory, HttpVersion.fromVersion(httpVersion).asString()),
                            new HttpConnectionFactory(config));
                }
            }
        }

        connector.setHost(address);
        connector.setPort(port);
        connector.setIdleTimeout(idleTimeout);

        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/*");
        constrainTraceMethod(context);
        server.setHandler(context);

        context.addServlet(new ServletHolder(new CommandServlet()), commandUrl + "/*");
    }

    /**
     * Start the embedded Jetty server.
     */
    @Override
    public void start() throws AdminServerException {
        try {
            server.start();
        } catch (Exception e) {
            // Server.start() only throws Exception, so let's at least wrap it
            // in an identifiable subclass
            String message = String.format(
                "Problem starting AdminServer on address %s, port %d and command URL %s",
                address,
                port,
                commandUrl);
            throw new AdminServerException(message, e);
        }
        LOG.info("Started AdminServer on address {}, port {} and command URL {}", address, port, commandUrl);
    }

    /**
     * Stop the embedded Jetty server.
     *
     * This is not very important except for tests where multiple
     * JettyAdminServers are started and may try to bind to the same ports if
     * previous servers aren't shut down.
     */
    @Override
    public void shutdown() throws AdminServerException {
        try {
            server.stop();
        } catch (Exception e) {
            String message = String.format(
                "Problem stopping AdminServer on address %s, port %d and command URL %s",
                address,
                port,
                commandUrl);
            throw new AdminServerException(message, e);
        }
    }

    /**
     * Set the ZooKeeperServer that will be used to run Commands.
     *
     * It is not necessary to set the ZK server before calling
     * AdminServer.start(), and the ZK server can be set to null when, e.g.,
     * that server is being shut down. If the ZK server is not set or set to
     * null, the AdminServer will still be able to issue Commands, but they will
     * return an error until a ZK server is set.
     */
    @Override
    public void setZooKeeperServer(ZooKeeperServer zkServer) {
        this.zkServer = zkServer;
    }

    private class CommandServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
            // Capture the command name from the URL
            String cmd = request.getPathInfo();
            if (cmd == null || cmd.equals("/")) {
                // No command specified, print links to all commands instead
                for (String link : commandLinks()) {
                    response.getWriter().println(link);
                    response.getWriter().println("<br/>");
                }
                return;
            }
            // Strip leading "/"
            cmd = cmd.substring(1);

            // Extract keyword arguments to command from request parameters
            @SuppressWarnings("unchecked") Map<String, String[]> parameterMap = request.getParameterMap();
            Map<String, String> kwargs = new HashMap<String, String>();
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                kwargs.put(entry.getKey(), entry.getValue()[0]);
            }

            // Run the command
            CommandResponse cmdResponse = Commands.runCommand(cmd, zkServer, kwargs);

            // Format and print the output of the command
            CommandOutputter outputter = new JsonOutputter();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(outputter.getContentType());
            outputter.output(cmdResponse, response.getWriter());
        }

    }

    /**
     * Returns a list of URLs to each registered Command.
     */
    private List<String> commandLinks() {
        return Commands.getPrimaryNames().stream().sorted().map(command -> String.format("<a href=\"%s\">%s</a>", commandUrl + "/" + command , command)).collect(Collectors.toList());
    }

    /**
     * Add constraint to a given context to disallow TRACE method
     * @param ctxHandler the context to modify
     */
    private void constrainTraceMethod(ServletContextHandler ctxHandler) {
        Constraint c = new Constraint();
        c.setAuthenticate(true);

        ConstraintMapping cmt = new ConstraintMapping();
        cmt.setConstraint(c);
        cmt.setMethod("TRACE");
        cmt.setPathSpec("/*");

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setConstraintMappings(new ConstraintMapping[] {cmt});

        ctxHandler.setSecurityHandler(securityHandler);
    }
}
