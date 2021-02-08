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

package org.apache.zookeeper.itest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.configureConsole;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

/**
 * Tests the ZooKeeper bundle headers to make sure
 * it will work properly in an OSGi container.
 *
 * <p>The tests in this class are run inside the configured OSGi container
 * instance, so ZooKeeper client and server must be running properly
 * inside that environment for the tests to pass.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class OSGiContainerIT {

    private static final int PORT = 21818;

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    // configure the OSGi container via pax exam
    @Configuration
    public Option[] config() throws IOException {
        tempDir.create();
        return options(
            karafDistributionConfiguration().frameworkUrl(
                maven().groupId("org.apache.karaf").artifactId("apache-karaf")
                    .type("zip").versionAsInProject())
                .unpackDirectory(tempDir.newFolder("pax-exam")),
            configureConsole().ignoreLocalConsole(),
            junitBundles(),
            mavenBundle("org.xerial.snappy", "snappy-java").versionAsInProject(),
            mavenBundle("io.dropwizard.metrics", "metrics-core").versionAsInProject(),
            projectBundle("zookeeper-jute", "org.apache.zookeeper", "zookeeper-jute"),
            projectBundle("zookeeper-server", "org.apache.zookeeper", "zookeeper")
        );
    }

    /**
     * Returns a provision option that installs a bundle created by one of the modules
     * in the current maven project.
     *
     * <p>This is necessary when the project is built using the verify phase rather than
     * the install phase (i.e. 'mvn verify'), so that the bundles are not installed
     * in the local maven repository and cannot be found by the
     * {@link org.ops4j.pax.exam.CoreOptions#mavenBundle mavenBundle} option.
     *
     * @param module the project module name (i.e. directory name)
     * @param groupId the artifact group id
     * @param artifactId the artifact id
     * @return the provisioning option for the bundle
     */
    public static Option projectBundle(String module, String groupId, String artifactId) {
        String version = MavenUtils.getArtifactVersion(groupId, artifactId);
        String url = OSGiContainerIT.class.getResource("/") + "../../../"
            + module + "/target/" + artifactId + "-" + version + ".jar";
        return bundle(url);
    }

    /**
     * A convenient try-with-resources construct to temporarily
     * change the thread context classloader in a code block.
     */
    static class TemporaryClassLoader implements Closeable {

        private final ClassLoader prev;

        public TemporaryClassLoader(ClassLoader loader) {
            prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
        }

        @Override
        public void close() {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    /**
     * A wrapper class to configure, start and stop the ZooKeeper server,
     * adjusting the classloader and running the blocking server code
     * in a separate thread.
     */
    static class ZooKeeperServer extends ZooKeeperServerMain implements Runnable {

        ServerConfig config;
        Thread thread;

        public void configure(Properties props) {
            try (TemporaryClassLoader tcl = new TemporaryClassLoader(ZooKeeper.class.getClassLoader())) {
                QuorumPeerConfig quorumConfiguration = new QuorumPeerConfig();
                quorumConfiguration.parseProperties(props);
                config = new ServerConfig();
                config.readFrom(quorumConfiguration);
            } catch (Exception e) {
                throw new RuntimeException("error configuring ZooKeeper server", e);
            }
        }

        public void configure(int port, String dir) {
            Properties props = new Properties();
            props.put("dataDir", dir);
            props.put("clientPort", port);
            configure(props);
        }

        public void start() {
            try (TemporaryClassLoader tcl = new TemporaryClassLoader(ZooKeeper.class.getClassLoader())) {
                thread = new Thread(this, "ZooKeeperRunner");
                thread.start();
            }
        }

        public void stop() {
            shutdown();
            try {
                thread.join();
            } catch (InterruptedException ie) {
                throw new RuntimeException("error stopping ZooKeeper server", ie);
            }
        }

        @Override
        public void run() {
            try {
                try (TemporaryClassLoader tcl = new TemporaryClassLoader(ZooKeeper.class.getClassLoader())) {
                    runFromConfig(config);
                }
            } catch (Exception e) {
                throw new RuntimeException("error running ZooKeeper server", e);
            }
        }
    }

    private ZooKeeper createClient(String connectString) throws IOException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(connectString, 10000, event -> {
            if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
        return zk;
    }

    @Test
    public void testZooKeeper() throws IOException, InterruptedException, KeeperException {
        // start server
        ZooKeeperServer server = new ZooKeeperServer();
        server.configure(PORT, tempDir.newFolder("zookeeper").toString());
        server.start();

        // start client
        ZooKeeper zk = createClient("127.0.0.1:" + PORT);

        // create node
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        String node = "/test-" + System.nanoTime();
        zk.create(node, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // test node
        Stat stat = zk.exists(node, false);
        assertNotNull(stat);
        byte[] actual = zk.getData(node, false, stat);
        assertArrayEquals(data, actual);

        // stop client
        zk.delete(node, stat.getVersion());
        zk.close();

        // stop server
        server.stop();
    }
}
