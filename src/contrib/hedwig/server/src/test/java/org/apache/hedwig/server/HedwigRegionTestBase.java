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
package org.apache.hedwig.server;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Before;

import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.netty.PubSubServer;
import org.apache.hedwig.server.persistence.BookKeeperTestBase;
import org.apache.hedwig.util.HedwigSocketAddress;

/**
 * This is a base class for any tests that need a Hedwig Region(s) setup with a
 * number of Hedwig hubs per region, an associated HedwigClient per region and
 * the required BookKeeper and ZooKeeper instances.
 * 
 */
public abstract class HedwigRegionTestBase extends TestCase {

    protected static Logger logger = Logger.getLogger(HedwigRegionTestBase.class);

    // BookKeeper variables
    // Default number of bookie servers to setup. Extending classes
    // can override this. We should be able to reuse the same BookKeeper
    // ensemble among all of the regions, at least for unit testing purposes.
    protected int numBookies = 3;
    protected BookKeeperTestBase bktb;

    // Hedwig Region variables
    // Default number of Hedwig Regions to setup. Extending classes can
    // override this.
    protected int numRegions = 2;
    protected int numServersPerRegion = 1;
    protected int initialServerPort = 4080;
    protected int initialSSLServerPort = 9876;
    // Map with keys being Region names and values being the list of Hedwig
    // Hubs (PubSubServers) for that particular region.
    protected Map<String, List<PubSubServer>> regionServersMap;
    // Map with keys being Region names and values being the Hedwig Client
    // instance.
    protected Map<String, HedwigClient> regionClientsMap;

    // String constant used as the prefix for the region names.
    protected static final String REGION_PREFIX = "region";

    // Default child class of the ServerConfiguration to be used here.
    // Extending classes can define their own (possibly extending from this) and
    // override the getServerConfiguration method below to return their own
    // configuration.
    protected class RegionServerConfiguration extends ServerConfiguration {
        private final int serverPort, sslServerPort;
        private final String regionName;

        public RegionServerConfiguration(int serverPort, int sslServerPort, String regionName) {
            this.serverPort = serverPort;
            this.sslServerPort = sslServerPort;
            this.regionName = regionName;
            setRegionList();
        }

        protected void setRegionList() {
            List<String> myRegionList = new LinkedList<String>();
            for (int i = 0; i < numRegions; i++) {
                int curDefaultServerPort = initialServerPort + (i * numServersPerRegion);
                int curDefaultSSLServerPort = initialSSLServerPort + (i * numServersPerRegion);
                // Add this region default server port if it is for a region
                // other than its own.
                if (curDefaultServerPort > serverPort
                        || Math.abs(serverPort - curDefaultServerPort) >= numServersPerRegion)
                    myRegionList.add("localhost:" + curDefaultServerPort + ":" + curDefaultSSLServerPort);
            }
            regionList = myRegionList;
        }

        @Override
        public int getServerPort() {
            return serverPort;
        }

        @Override
        public int getSSLServerPort() {
            return sslServerPort;
        }

        @Override
        public String getZkHost() {
            return bktb.getZkHostPort();
        }

        @Override
        public String getMyRegion() {
            return regionName;
        }

        @Override
        public boolean isSSLEnabled() {
            return true;
        }

        @Override
        public boolean isInterRegionSSLEnabled() {
            return true;
        }

        @Override
        public String getCertName() {
            return "/server.p12";
        }

        @Override
        public String getPassword() {
            return "eUySvp2phM2Wk";
        }
    }

    // Method to get a ServerConfiguration for the PubSubServers created using
    // the specified ports and region name. Extending child classes can override
    // this. This default implementation will return the
    // RegionServerConfiguration object defined above.
    protected ServerConfiguration getServerConfiguration(int serverPort, int sslServerPort, String regionName) {
        return new RegionServerConfiguration(serverPort, sslServerPort, regionName);
    }

    // Default ClientConfiguration to use. This just points to the first
    // Hedwig hub server in each region as the "default server host" to connect
    // to.
    protected class RegionClientConfiguration extends ClientConfiguration {
        public RegionClientConfiguration(int serverPort, int sslServerPort) {
            myDefaultServerAddress = new HedwigSocketAddress("localhost:" + serverPort + ":" + sslServerPort);
        }
        // Below you can override any of the default ClientConfiguration
        // parameters if needed.
    }

    // Method to get a ClientConfiguration for the HedwigClients created.
    // Inputs are the default Hedwig hub server's ports to point to.
    protected ClientConfiguration getClientConfiguration(int serverPort, int sslServerPort) {
        return new RegionClientConfiguration(serverPort, sslServerPort);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        logger.info("STARTING " + getName());
        bktb = new BookKeeperTestBase(numBookies);
        bktb.setUp();

        // Create the Hedwig PubSubServer Hubs for all of the regions
        regionServersMap = new HashMap<String, List<PubSubServer>>(numRegions, 1.0f);
        regionClientsMap = new HashMap<String, HedwigClient>(numRegions, 1.0f);        
        for (int i = 0; i < numRegions; i++) {
            List<PubSubServer> serversList = new LinkedList<PubSubServer>();
            // For the current region, create the necessary amount of hub
            // servers. We will basically increment through the port numbers
            // starting from the initial ones defined.
            for (int j = 0; j < numServersPerRegion; j++) {
                serversList.add(new PubSubServer(getServerConfiguration(initialServerPort
                        + (j + i * numServersPerRegion), initialSSLServerPort + (j + i * numServersPerRegion),
                        REGION_PREFIX + i)));
            }
            // Store this list of servers created for the current region
            regionServersMap.put(REGION_PREFIX + i, serversList);

            // Create a Hedwig Client that points to the first Hub server
            // created in the loop above for the current region.
            HedwigClient regionClient = new HedwigClient(getClientConfiguration(initialServerPort
                    + (i * numServersPerRegion), initialSSLServerPort + (i * numServersPerRegion)));
            regionClientsMap.put(REGION_PREFIX + i, regionClient);
        }
        logger.info("HedwigRegion test setup finished");
    }

    @Override
    @After
    public void tearDown() throws Exception {
        logger.info("tearDown starting");
        // Stop all of the HedwigClients for all regions
        for (HedwigClient client : regionClientsMap.values()) {
            client.stop();
        }
        regionClientsMap.clear();
        // Shutdown all of the PubSubServers in all regions
        for (List<PubSubServer> serversList : regionServersMap.values()) {
            for (PubSubServer server : serversList) {
                server.shutdown();
            }
        }
        logger.info("Finished shutting down all of the hub servers!");
        regionServersMap.clear();
        // Shutdown the BookKeeper and ZooKeeper stuff
        bktb.tearDown();
        logger.info("FINISHED " + getName());
    }

}
