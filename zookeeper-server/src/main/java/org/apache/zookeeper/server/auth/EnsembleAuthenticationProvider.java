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

package org.apache.zookeeper.server.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.jmx.ZKMBeanInfo;

import javax.management.JMException;

/**
 * This is not a true AuthenticationProvider in the strict sense. it does
 * handle add auth requests, but rather than authenticate the client, it checks
 * to make sure that the ensemble name the client intends to connect to
 * matches the name that the server thinks it belongs to. if the name does not match,
 * this provider will close the connection.
 */

public class EnsembleAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG = LoggerFactory
            .getLogger(EnsembleAuthenticationProvider.class);

    public static final String ENSEMBLE_PROPERTY = "zookeeper.ensembleAuthName";
    private static final int MIN_LOGGING_INTERVAL_MS = 1000;
    private Set<String> ensembleNames;

    public EnsembleAuthenticationProvider() {
        String namesCSV = System.getProperty(ENSEMBLE_PROPERTY);
        if (namesCSV != null) {
            LOG.info("Set expected ensemble names to {}", namesCSV);
            setEnsembleNames(namesCSV);
        }
    }

    public void setEnsembleNames(String namesCSV) {
        ensembleNames = new HashSet<String>();
        for (String name: namesCSV.split(",")) {
            ensembleNames.add(name.trim());    
        }
    }

    /* provider methods */
    @Override
    public String getScheme() {
        return "ensemble";
    }

    /**
     * if things go bad, we don't want to freak out with the logging, so track
     * the last time we logged something here.
     */
    private long lastFailureLogged;

    @Override
    public KeeperException.Code
    handleAuthentication(ServerCnxn cnxn, byte[] authData)
    {
        if (authData == null || authData.length == 0) {
            ServerMetrics.ENSEMBLE_AUTH_SKIP.add(1);
            return KeeperException.Code.OK;
        }

        String receivedEnsembleName = new String(authData);

        if (ensembleNames == null) {
            ServerMetrics.ENSEMBLE_AUTH_SKIP.add(1);
            return KeeperException.Code.OK;
        }

        if (ensembleNames.contains(receivedEnsembleName)) {
            ServerMetrics.ENSEMBLE_AUTH_SUCCESS.add(1);
            return KeeperException.Code.OK;
        }

        long currentTime = System.currentTimeMillis();
        if (lastFailureLogged + MIN_LOGGING_INTERVAL_MS < currentTime) {
            String id = cnxn.getRemoteSocketAddress().getAddress().getHostAddress();
            LOG.warn("Unexpected ensemble name: ensemble name: {} client ip: {}", receivedEnsembleName, id);
            lastFailureLogged = currentTime;
        }
        /*
         * we are doing a close here rather than returning some other error
         * since we want the client to choose another server to connect to. if
         * we return an error, the client will get a fatal auth error and
         * shutdown.
         */
        ServerMetrics.ENSEMBLE_AUTH_FAIL.add(1);
        cnxn.close();
        return KeeperException.Code.BADARGUMENTS;
    }

    /*
     * since we aren't a true provider we return false for everything so that
     * it isn't used in ACLs.
     */
    @Override
    public boolean matches(String id, String aclExpr) {
        return false;
    }

    @Override
    public boolean isAuthenticated() {
        return false;
    }

    @Override
    public boolean isValid(String id) {
        return false;
    }
}
