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

package org.apache.zookeeper.test;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.auth.EnsembleAuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class EnsembleAuthTest extends ClientBase {

    @Before
    public void setUp() throws Exception {
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.server.auth.EnsembleAuthenticationProvider");
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        System.clearProperty("zookeeper.authProvider.1");
        System.clearProperty(EnsembleAuthenticationProvider.ENSEMBLE_PROPERTY);
        ProviderRegistry.removeProvider("ensemble");
    }


    @Test
    public void noAuth() throws Exception {
        resetEnsembleAuth(null, false);
        connectToEnsemble(null);
    }

    @Test
    public void emptyAuth() throws Exception {
        resetEnsembleAuth(null, true);
        connectToEnsemble("foo");
    }

    @Test
    public void skipAuth() throws Exception {
        resetEnsembleAuth("woo", true);
        connectToEnsemble(null);
    }

    @Test
    public void passAuth() throws Exception {
        resetEnsembleAuth("woo", true);
        connectToEnsemble("woo");
    }

    @Test
    public void passAuthCSV() throws Exception {
        resetEnsembleAuth(" foo,bar, baz ", true);

        connectToEnsemble("foo");
        connectToEnsemble("bar");
        connectToEnsemble("baz");
    }

    @Test(expected = KeeperException.ConnectionLossException.class)
    public void failAuth() throws Exception {
        resetEnsembleAuth("woo", true);
        connectToEnsemble("goo");
    }

    @Test(expected = KeeperException.AuthFailedException.class)
    public void removeEnsembleAuthProvider() throws Exception {
        resetEnsembleAuth(null, false);
        connectToEnsemble("goo");
    }


    private void connectToEnsemble(final String auth) throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient()) {
            // pass auth check
            if (auth != null) {
                zk.addAuthInfo("ensemble", auth.getBytes());
            }
            zk.getData("/", false, null);
        }
    }

    private void resetEnsembleAuth(final String auth, final boolean useAuth) throws Exception {
        stopServer();
        if (auth == null) {
            System.clearProperty(EnsembleAuthenticationProvider.ENSEMBLE_PROPERTY);
        } else {
            System.setProperty(EnsembleAuthenticationProvider.ENSEMBLE_PROPERTY, auth);
        }
        if (useAuth) {
            System.setProperty("zookeeper.authProvider.1",
                    "org.apache.zookeeper.server.auth.EnsembleAuthenticationProvider");
        } else {
            System.clearProperty("zookeeper.authProvider.1");
        }
        ProviderRegistry.removeProvider("ensemble");
        ProviderRegistry.initialize();
        startServer();
    }
}
