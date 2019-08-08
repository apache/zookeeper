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
package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.Properties;

import javax.management.JMException;
import javax.security.sasl.SaslException;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 */
//服务器启动类
@InterfaceAudience.Public
public class QuorumPeerMain {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMain.class);

    private static final String USAGE = "Usage: QuorumPeerMain configfile";

    protected QuorumPeer quorumPeer;

    /**
     * To start the replicated server specify the configuration file name on
     * the command line.
     * 要启动复制的服务器，请在命令行上指定配置文件名。
     * @param args path to the configfile
     */
    public static void main(String[] args) {
        QuorumPeerMain main = new QuorumPeerMain();
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
        LOG.info("Exiting normally正常退出");
        System.exit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException, AdminServerException
    {
        // 解析配置文件  QuorumPeerConfig
        QuorumPeerConfig config = new QuorumPeerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        }

        // 配置并启动清除任务
        DatadirCleanupManager purgeMgr = new DatadirCleanupManager(config
                .getDataDir(), config.getDataLogDir(), config
                .getSnapRetainCount(), config.getPurgeInterval());
        purgeMgr.start();

        if (args.length == 1 && config.isDistributed()) {//集群模式运行
            runFromConfig(config); //解析 zoo.cfg
        } else {// standalone模式运行
            LOG.warn("Either no config or no quorum defined in config, running "
                    + " in standalone mode");
            ZooKeeperServerMain.main(args);
        }
    }

    // zookeeper集群启动情况下
    public void runFromConfig(QuorumPeerConfig config) throws IOException, AdminServerException
    {
      try {
          ManagedUtil.registerLog4jMBeans();
      } catch (JMException e) {
          LOG.warn("Unable to register log4j JMX control", e);
      }

      LOG.info("Starting quorum peer");
      MetricsProvider metricsProvider;
      try {
          // 配置并启动DefaultMetricsProvider
        metricsProvider = MetricsProviderBootstrap
                      .startMetricsProvider(config.getMetricsProviderClassName(),// 配置文件中 metricsProvider.className，默认是DefaultMetricsProvider
                                            config.getMetricsProviderConfiguration());// 配置文件中已 metricsProvider. 开头的
      } catch (MetricsProviderLifeCycleException error) {
        throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(),
                      error);
      }
      try {
          ServerMetrics.metricsProviderInitialized(metricsProvider);

          ServerCnxnFactory cnxnFactory = null;
          if (config.getClientPortAddress() != null) { //配置文件中 clientPortAddress
              cnxnFactory = ServerCnxnFactory.createFactory();
              cnxnFactory.configure(config.getClientPortAddress(),//clientPortAddress + clientPort
                      config.getMaxClientCnxns(), // maxClientCnxns 默认60
                      config.getClientPortListenBacklog(), false);//clientPortListenBacklog   默认-1
          }

          ServerCnxnFactory secureCnxnFactory = null;
          if (config.getSecureClientPortAddress() != null) { // 配置文件中 secureClientPortAddress
              secureCnxnFactory = ServerCnxnFactory.createFactory();
              secureCnxnFactory.configure(config.getSecureClientPortAddress(),// secureClientPortAddress + secureClientPort
                      config.getMaxClientCnxns(),// maxClientCnxns 默认60
                      config.getClientPortListenBacklog(), true);//clientPortListenBacklog   默认-1
          }

          quorumPeer = getQuorumPeer();
          quorumPeer.setTxnFactory(new FileTxnSnapLog(
                      config.getDataLogDir(), // dataLogDir
                      config.getDataDir())); // dataDir
          quorumPeer.enableLocalSessions(config.areLocalSessionsEnabled()); //localSessionsEnabled 默认false
          quorumPeer.enableLocalSessionsUpgrading(
              config.isLocalSessionsUpgradingEnabled()); // localSessionsUpgradingEnabled 默认false
//          quorumPeer.setQuorumPeers(config.getAllMembers());
          quorumPeer.setElectionType(config.getElectionAlg()); // electionAlg  默认是3  只支持1、2、3
          quorumPeer.setMyid(config.getServerId()); //myid  myid文件中配置  默认-1 standalone模式不需要myid文件
          quorumPeer.setTickTime(config.getTickTime()); //tickTime  心跳时间 默认3000
          quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout()); //minSessionTimeout  默认3000 * 2
          quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout()); //maxSessionTimeout  默认3000 * 20
          quorumPeer.setInitLimit(config.getInitLimit()); //initLimit
          quorumPeer.setSyncLimit(config.getSyncLimit()); //syncLimit
          quorumPeer.setObserverMasterPort(config.getObserverMasterPort()); //observerMasterPort
          quorumPeer.setConfigFileName(config.getConfigFilename());  //配置文件路径
          quorumPeer.setClientPortListenBacklog(config.getClientPortListenBacklog()); //clientPortListenBacklog  默认-1
          quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));  //quorumPeer.getTxnFactory()拿到了上面传入的new FileTxnSnapLog(config.getDataLogDir(),dataLogDirconfig.getDataDir())对象

          quorumPeer.setQuorumVerifier(config.getQuorumVerifier(), false);
          if (config.getLastSeenQuorumVerifier()!=null) {
              quorumPeer.setLastSeenQuorumVerifier(config.getLastSeenQuorumVerifier(), false);
          }
          quorumPeer.initConfigInZKDatabase();
          quorumPeer.setCnxnFactory(cnxnFactory); // 如果配置文件中没有配置clientPortAddress，则cnxnFactory为空
          quorumPeer.setSecureCnxnFactory(secureCnxnFactory); // 如果配置文件中没有配置secureClientPortAddress，则secureCnxnFactory为空
          quorumPeer.setSslQuorum(config.isSslQuorum());
          quorumPeer.setUsePortUnification(config.shouldUsePortUnification());
          quorumPeer.setLearnerType(config.getPeerType());
          quorumPeer.setSyncEnabled(config.getSyncEnabled());
          quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());
          if (config.sslQuorumReloadCertFiles) {
              quorumPeer.getX509Util().enableCertFileReloading();
          }

          // sets quorum sasl authentication configurations设置仲裁sasl身份验证配置
          quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
          if(quorumPeer.isQuorumSaslAuthEnabled()){
              quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
              quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
              quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
              quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
              quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
          }
          quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
          quorumPeer.initialize();

          if(config.jvmPauseMonitorToRun) {
              quorumPeer.setJvmPauseMonitor(new JvmPauseMonitor(config));
          }

          quorumPeer.start();
          quorumPeer.join();
      } catch (InterruptedException e) {
          // warn, but generally this is ok
          LOG.warn("Quorum Peer interrupted", e);
      } finally {
          if (metricsProvider != null) {
              try {
                  metricsProvider.stop();
              } catch (Throwable error) {
                  LOG.warn("Error while stopping metrics", error);
              }
          }
      }
    }

    // @VisibleForTesting
    protected QuorumPeer getQuorumPeer() throws SaslException {
        return new QuorumPeer();
    }
}
