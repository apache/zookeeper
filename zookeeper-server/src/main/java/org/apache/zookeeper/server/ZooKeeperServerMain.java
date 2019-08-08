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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.JMException;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.admin.AdminServer;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.admin.AdminServerFactory;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * standalone ZooKeeperServer.
 * 该类启动并运行独立的ZooKeeperServer。
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {
    private static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE =
        "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    // ZooKeeper server supports two kinds of connection: unencrypted and encrypted.
    private ServerCnxnFactory cnxnFactory;
    private ServerCnxnFactory secureCnxnFactory;
    private ContainerManager containerManager;
    private MetricsProvider metricsProvider;
    private AdminServer adminServer;

    /*
     * Start up the ZooKeeper server.
     * 单机情况下
     * 启动ZooKeeper服务器。
     *
     * @param args the configfile or the port datadir [ticktime]
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain();
        try {
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (DatadirException e) {
            LOG.error("Unable to access datadir, exiting abnormally", e);
            System.err.println("Unable to access datadir, exiting abnormally");
            System.exit(ExitCode.UNABLE_TO_ACCESS_DATADIR.getValue());
        } catch (AdminServerException e) {
            LOG.error("Unable to start AdminServer, exiting abnormally", e);
            System.err.println("Unable to start AdminServer, exiting abnormally");
            System.exit(ExitCode.ERROR_STARTING_ADMIN_SERVER.getValue());
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
        LOG.info("Exiting normally");
        System.exit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    protected void initializeAndRun(String[] args) throws ConfigException, IOException, AdminServerException {
        try {
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }
        // 解析配置文件 ServerConfig
        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        } else {
            config.parse(args);
        }

        runFromConfig(config);
    }

    /**
     * Run from a ServerConfig.查找所有要删除的候选项，即名称中的zxid小于leastZxidToBeRetain的文件。如上所述，这条规则有一个例外。
     * @param config ServerConfig to use.
     * @throws IOException
     * @throws AdminServerException
     */
    public void runFromConfig(ServerConfig config) throws IOException, AdminServerException {
        LOG.info("Starting server");
        FileTxnSnapLog txnLog = null;
        try {
            try {
                // 配置并启动DefaultMetricsProvider
                metricsProvider = MetricsProviderBootstrap
                        .startMetricsProvider(config.getMetricsProviderClassName(),// 配置文件中 metricsProvider.className，默认是DefaultMetricsProvider
                                config.getMetricsProviderConfiguration());// 配置文件中已 metricsProvider. 开头的
            } catch (MetricsProviderLifeCycleException error) {
                throw new IOException("Cannot boot MetricsProvider无法启动MetricsProvider "+config.getMetricsProviderClassName(),
                    error);
            }
            ServerMetrics.metricsProviderInitialized(metricsProvider);
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            // create a file logger url from the command line args
            txnLog = new FileTxnSnapLog(config.dataLogDir, config.dataDir);
            JvmPauseMonitor jvmPauseMonitor = null;
            if(config.jvmPauseMonitorToRun) {
                // jvm 监控程序
                jvmPauseMonitor = new JvmPauseMonitor(config);
            }
            final ZooKeeperServer zkServer = new ZooKeeperServer(jvmPauseMonitor, txnLog,
                    config.tickTime, config.minSessionTimeout, config.maxSessionTimeout,
                    config.listenBacklog, null);
            txnLog.setServerStats(zkServer.serverStats());

            // Registers shutdown handler which will be used to know the
            // server error or shutdown state changes.
            // 注册shutdown处理程序，用于了解//服务器错误或关闭状态更改。
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            // 注册服务关闭回调
            zkServer.registerServerShutdownHandler(
                    new ZooKeeperServerShutdownHandler(shutdownLatch));

            // Start Admin server启动管理服务器
            // 默认不开启，这里可以启动JettyAdminServer服务器
            adminServer = AdminServerFactory.createAdminServer();
            adminServer.setZooKeeperServer(zkServer);
            adminServer.start();

            boolean needStartZKServer = true;
            // 创建并启动网络IO管理器
            if (config.getClientPortAddress() != null) {
                // ServerCnxnFactory
                cnxnFactory = ServerCnxnFactory.createFactory();
                // clientPortAddress  maxClientCnxns（默认60） clientPortListenBacklog（默认 -1）
                cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(),
                    config.getClientPortListenBacklog(), false);
                // 此方法除了启动ServerCnxnFactory,还会启动ZooKeeper
                cnxnFactory.startup(zkServer);
                // zkServer has been started. So we don't need to start it again in secureCnxnFactory.
                needStartZKServer = false;
            }
            // 创建并启动secureCnxnFactory
            if (config.getSecureClientPortAddress() != null) {
                // ServerCnxnFactory
                secureCnxnFactory = ServerCnxnFactory.createFactory();
                secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(),
                    config.getClientPortListenBacklog(), true);
                // 如果创建了cnxnFactory，则needStartZKServer就是false，否则就是true
                // needStartZKServer是为了启动zkServer服务，如果cnxnFactory启动过这里就不会多次启动
                secureCnxnFactory.startup(zkServer, needStartZKServer);
            }


            // 容器节点管理器   当容器节点的最后一个孩子节点被删除之后，容器节点将被标注并在一段时间后删除.
            // checkIntervalMs	znode.container.checkIntervalMs	系统属性	执行两次检查任务之间的时间间隔,单位:ms,默认1min
            // maxPerMinute	znode.container.maxPerMinute 系统属性 一分钟内最多删除多少个容器节点,即删除两个容器节点之间的最少时间间隔为60000/10000=6ms
            containerManager = new ContainerManager(zkServer.getZKDatabase(), zkServer.firstProcessor, // 这里需要请求处理器
                    Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
                    Integer.getInteger("znode.container.maxPerMinute", 10000));

            containerManager.start();

            // Watch status of ZooKeeper server. It will do a graceful shutdown
            // if the server is not running or hits an internal error.
            // 观察ZooKeeper服务器的状态。如果服务器没有运行或遇到内部错误，它将正常关闭
            // 在ZooKeeperServerShutdownHandler中执行了shutdownLatch.countDown()
            // 服务器正常启动时,运行到此处阻塞,只有server的state变为ERROR或SHUTDOWN时继续运行后面的代码
            shutdownLatch.await();

            shutdown();

            if (cnxnFactory != null) {
                // 主线程等到cnxnFactory关闭
                cnxnFactory.join();
            }
            if (secureCnxnFactory != null) {
                // 主线程等到secureCnxnFactory关闭
                secureCnxnFactory.join();
            }
            if (zkServer.canShutdown()) {
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) {
            // warn, but generally this is ok
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close();
            }
            if (metricsProvider != null) {
                try {
                    metricsProvider.stop();
                } catch (Throwable error) {
                    LOG.warn("Error while stopping metrics", error);
                }
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
        if (secureCnxnFactory != null) {
            secureCnxnFactory.shutdown();
        }
        try {
            if (adminServer != null) {
                adminServer.shutdown();
            }
        } catch (AdminServerException e) {
            LOG.warn("Problem stopping AdminServer", e);
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }
}
