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

#include <cppunit/extensions/HelperMacros.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <errno.h>
#include <iostream>
#include <sstream>
#include <arpa/inet.h>
#include <exception>
#include <stdlib.h>

#include "Util.h"
#include "LibCMocks.h"
#include "ZKMocks.h"

using namespace std;

static const int portOffset = 2000;

class Client
{

private:    
    // Member variables
    zhandle_t *zh;
    unsigned int seed;

public:
    /**
     * Create a client with given connection host string and add to our internal
     * vector of clients. These are disconnected and cleaned up in tearDown().
     */
    Client(const string hosts, unsigned int seed) :
        seed((seed * seed) + 0xAFAFAFAF)
    {
        reSeed();

        zh = zookeeper_init(hosts.c_str(),0,1000,0,0,0);
        CPPUNIT_ASSERT(zh);

        // Set the flag to disable ZK from reconnecting to a different server.
        // Our reconfig test case will do explicit server shuffling through
        // zoo_cycle_next_server, and the reconnection attempts would interfere
        // with the server states the tests cases assume.
        zh->disable_reconnection_attempt = 1;
        reSeed();

        cycleNextServer();
    }

    void close()
    {
        zookeeper_close(zh);
        zh = NULL;
    }

    bool isReconfig()
    {
        return zh->reconfig != 0;
    }

    /**
     * re-seed this client with it's own previously generated seed so its
     * random choices are unique and separate from the other clients
     */
    void reSeed()
    {
        srandom(seed);
        srand48(seed);
    }

    /**
     * Get the server that this client is currently connected to.
     */
    string getServer()
    {
        const char* addrstring = zoo_get_current_server(zh);
        return string(addrstring);
    }

    /**
     * Get the server this client is currently connected to with no port
     * specification.
     */
    string getServerNoPort()
    {
        string addrstring = getServer();
        size_t found = addrstring.find_last_of(":");
        CPPUNIT_ASSERT(found != string::npos);

        // ipv6 address case (to remove leading and trailing bracket)
        if (addrstring.find("[") != string::npos)
        {
            return addrstring.substr(1, found-2);
        }
        else
        {
            return addrstring.substr(0, found);
        }
    }

    /**
     * Get the port of the server this client is currently connected to.
     */
    uint32_t getServerPort()
    {
        string addrstring = getServer();

        size_t found = addrstring.find_last_of(":");
        CPPUNIT_ASSERT(found != string::npos);

        string portStr = addrstring.substr(found+1);

        stringstream ss(portStr);
        uint32_t port;
        ss >> port;

        CPPUNIT_ASSERT(port >= portOffset);

        return port;
    }

    /**
     * Cycle to the next available server on the next connect attempt. It also
     * calls into getServer (above) to return the server connected to.
     */ 
    string cycleNextServer()
    {
        zoo_cycle_next_server(zh);
        return getServer();
    }

    void cycleUntilServer(const string requested)
    {
        // Call cycleNextServer until the one it's connected to is the one
        // specified (disregarding port).
        string first;

        while(true)
        {
            string next = cycleNextServer();
            if (first.empty())
            {
                first = next;
            } 
            // Else we've looped around!
            else if (first == next)
            {
                CPPUNIT_ASSERT(false);
            }

            // Strip port off
            string server = getServerNoPort();

            // If it matches the requested host we're now 'connected' to the right host
            if (server == requested)
            {
                break;
            }
        }
    }

    /**
     * Set servers for this client.
     */
    void setServers(const string new_hosts)
    {
        int rc = zoo_set_servers(zh, new_hosts.c_str());
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
    }

    /**
     * Set servers for this client and validate reconfig value matches expected.
     */
    void setServersAndVerifyReconfig(const string new_hosts, bool is_reconfig)
    {
        setServers(new_hosts);
        CPPUNIT_ASSERT_EQUAL(is_reconfig, isReconfig());
    }

    /**
     * Sets the server list this client is connecting to AND if this requires
     * the client to be reconfigured (as dictated by internal client policy)
     * then it will trigger a call to cycleNextServer.
     */
    void setServersAndCycleIfNeeded(const string new_hosts)
    {
        setServers(new_hosts);
        if (isReconfig())
        {
            cycleNextServer();
        }
    }
};

class Zookeeper_reconfig : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_reconfig);

    // Test cases
    CPPUNIT_TEST(testcycleNextServer);
    CPPUNIT_TEST(testMigrateOrNot);
    CPPUNIT_TEST(testMigrationCycle);

    // In threaded mode each 'create' is a thread -- it's not practical to create
    // 10,000 threads to test load balancing. The load balancing code can easily
    // be tested in single threaded mode as concurrency doesn't affect the algorithm.
#ifndef THREADED
    CPPUNIT_TEST(testMigrateProbability);
    CPPUNIT_TEST(testLoadBalancing);
#endif

    CPPUNIT_TEST_SUITE_END();

    FILE *logfile;

    double slackPercent;
    static const int numClients = 10000;
    static const int portOffset = 2000;

    vector<Client> clients;
    vector<uint32_t> numClientsPerHost;

public:
    Zookeeper_reconfig() :
        slackPercent(10.0)
    {
      logfile = openlogfile("Zookeeper_reconfig");
    }

    ~Zookeeper_reconfig() 
    {
      if (logfile) 
      {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);
        zoo_deterministic_conn_order(1);

        numClientsPerHost.resize(numClients);
    }

    void tearDown()
    {
        for (int i = 0; i < clients.size(); i++)
        {
            clients.at(i).close();
        }
    }

    /**
     * Create a client with given connection host string and add to our internal
     * vector of clients. These are disconnected and cleaned up in tearDown().
     */
    Client& createClient(const string hosts)
    {
        Client client(hosts, clients.size());
        clients.push_back(client);

        return clients.back();
    }

    /**
     * Same as createClient(hosts) only it takes a specific host that this client
     * should simulate being connected to.
     */
    Client& createClient(const string hosts, const string host)
    {
        // Ensure requested host is in the list
        size_t found = hosts.find(host);
        CPPUNIT_ASSERT(found != hosts.npos);

        Client client(hosts, clients.size());
        client.cycleUntilServer(host);
        clients.push_back(client);

        return clients.back();
    }

    /**
     * Create a connection host list starting at 'start' and stopping at 'stop'
     * where start >= stop. This creates a connection string with host:port pairs
     * separated by commas. The given 'octet' is the starting octet that is used
     * as the last octet in the host's IP. This is decremented on each iteration. 
     * Each port will be portOffset + octet.
     */
    string createHostList(uint32_t start, uint32_t stop = 1, uint32_t octet = 0)
    {
        if (octet == 0)
        {
            octet = start;
        }

        stringstream ss;

        for (int i = start; i >= stop; i--, octet--)
        {
            ss << "10.10.10." << octet << ":" << portOffset + octet;

            if (i > stop)
            {
                ss << ", ";
            }
        }

        return ss.str();
    }

    /**
     * Gets the lower bound of the number of clients per server that we expect
     * based on the probabilistic load balancing algorithm implemented by the
     * client code.
     */
    double lowerboundClientsPerServer(int numClients, int numServers)
    {
        return (1 - slackPercent/100.0) * numClients / numServers;
    }

    /**
     * Gets the upper bound of the number of clients per server that we expect
     * based on the probabilistic load balancing algorithm implemented by the
     * client code.
     */
    double upperboundClientsPerServer(int numClients, int numServers)
    {
        return (1 + slackPercent/100.0) * numClients / numServers;
    }

    /**
     * Update all the clients to use a new list of servers. This will also cause
     * the client to cycle to the next server as needed (e.g. due to a reconfig).
     * It then updates the number of clients connected to the server based on
     * this change.
     * 
     * Afterwards it validates that all of the servers have the correct amount of
     * clients based on the probabilistic load balancing algorithm.
     */
    void updateAllClientsAndServers(int start, int stop = 1)
    {
        string newServers = createHostList(start, stop);
        int numServers = start - stop + 1;

        for (int i = 0; i < numClients; i++) {

            Client &client = clients.at(i);
            client.reSeed();

            client.setServersAndCycleIfNeeded(newServers);
            numClientsPerHost.at(client.getServerPort() - portOffset - 1)++;
        }

        int offset = stop - 1;
        for (int index = offset; index < numServers; index++) {

            if (numClientsPerHost.at(index) > upperboundClientsPerServer(numClients, numServers))
            {
                cout << "INDEX=" << index << " too many -- actual=" << numClientsPerHost.at(index) 
                     << " expected=" << upperboundClientsPerServer(numClients, numServers) << endl;
            }


            CPPUNIT_ASSERT(numClientsPerHost.at(index) <= upperboundClientsPerServer(numClients, numServers));

            if (numClientsPerHost.at(index) < lowerboundClientsPerServer(numClients, numServers))
            {
                cout << "INDEX=" << index << " too few -- actual=" << numClientsPerHost.at(index) 
                     << " expected=" << lowerboundClientsPerServer(numClients, numServers) << endl;
            }

            CPPUNIT_ASSERT(numClientsPerHost.at(index) >= lowerboundClientsPerServer(numClients, numServers));
            numClientsPerHost.at(index) = 0; // prepare for next test
        }
    }

    /*-------------------------------------------------------------------------*
     * TESTCASES
     *------------------------------------------------------------------------*/

    /**
     * Very basic sunny day test to ensure basic functionality of zoo_set_servers
     * and zoo_cycle_next_server.
     */
    void testcycleNextServer()
    {
        const string initial_hosts = createHostList(10); // 2010..2001
        const string new_hosts = createHostList(4);      // 2004..2001

        Client &client = createClient(initial_hosts);

        client.setServersAndVerifyReconfig(new_hosts, true);

        for (int i = 0; i < 10; i++)
        {
            string next = client.cycleNextServer();
        }
    }

    /**
     * Test the migration policy implicit within the probabilistic load balancing
     * algorithm the Client implements. Tests all the corner cases whereby the
     * list of servers is decreased, increased, and stays the same. Also combines
     * various combinations of the currently connected server being in the new
     * configuration and not.
     */
    void testMigrateOrNot()
    {
        const string initial_hosts = createHostList(4); // 2004..2001

        Client &client = createClient(initial_hosts, "10.10.10.3");

        // Ensemble size decreasing, my server is in the new list
        client.setServersAndVerifyReconfig(createHostList(3), false);

        // Ensemble size decreasing, my server is NOT in the new list
        client.setServersAndVerifyReconfig(createHostList(2), true);

        // Ensemble size stayed the same, my server is NOT in the new list
        client.setServersAndVerifyReconfig(createHostList(2), true);

        // Ensemble size increased, my server is not in the new ensemble
        client.setServers(createHostList(4));
        client.cycleUntilServer("10.10.10.1");
        client.setServersAndVerifyReconfig(createHostList(7,2), true);
    }

    /**
     * This tests that as a client is in reconfig mode it will properly try to
     * connect to all the new servers first. Then it will try to connect to all
     * the 'old' servers that are staying in the new configuration. Finally it
     * will fallback to the normal behavior of trying servers in round-robin.
     */
    void testMigrationCycle()
    {
        int num_initial = 4;
        const string initial_hosts = createHostList(num_initial); // {2004..2001}

        int num_new = 10;
        string new_hosts = createHostList(12, 3);      // {2012..2003}

        // servers from the old list that appear in the new list {2004..2003}
        int num_staying = 2;
        string oldStaying = createHostList(4, 3);

        // servers in the new list that are not in the old list  {2012..2005}
        int num_coming = 8;
        string newComing = createHostList(12, 5);

        // Ensemble in increasing in size, my server is not in the new ensemble
        // load on the old servers must be decreased, so must connect to one of
        // new servers (pNew = 1)
        Client &client = createClient(initial_hosts, "10.10.10.1");
        client.setServersAndVerifyReconfig(new_hosts, true);

        // Since we're in reconfig mode, next connect should be from new list
        // We should try all the new servers *BEFORE* trying any old servers
        string seen;
        for (int i = 0; i < num_coming; i++) {
            client.cycleNextServer();

            // Assert next server is in the 'new' list
            stringstream next;
            next << client.getServerNoPort() << ":" << client.getServerPort();
            size_t found = newComing.find(next.str());
            CPPUNIT_ASSERT_MESSAGE(next.str() + " not in newComing list",
                                   found != string::npos);

            // Assert not in seen list then append
            found = seen.find(next.str());
            CPPUNIT_ASSERT_MESSAGE(next.str() + " in seen list",
                                   found == string::npos);
            seen += found + ", ";
        }

        // Now it should start connecting to the old servers
        seen.clear();
        for (int i = 0; i < num_staying; i++) {
            client.cycleNextServer();

            // Assert it's in the old list
            stringstream next;
            next << client.getServerNoPort() << ":" << client.getServerPort();
            size_t found = oldStaying.find(next.str());
            CPPUNIT_ASSERT(found != string::npos);

            // Assert not in seen list then append
            found = seen.find(next.str());
            CPPUNIT_ASSERT(found == string::npos);
            seen += found + ", ";
        }

        // NOW it goes back to normal as we've tried all the new and old
        string first = client.cycleNextServer();
        for (int i = 0; i < num_new - 1; i++) {
            client.cycleNextServer();
        }

        CPPUNIT_ASSERT_EQUAL(first, client.cycleNextServer());
    }

    /**
     * Test the migration probability to ensure that it conforms to our expected
     * lower and upper bounds of the number of clients per server as we are 
     * reconfigured.
     * 
     * In this case, the list of servers is increased and the client's server is
     * in the new list. Whether to move or not depends on the difference of
     * server sizes with probability 1 - |old|/|new| the client disconnects.
     * 
     * In the test below 1-9/10 = 1/10 chance of disconnecting
     */
    void testMigrateProbability()
    {
        const string initial_hosts = createHostList(9); // 10.10.10.9:2009...10.10.10.1:2001
        string new_hosts = createHostList(10); // 10.10.10.10:2010...10.10.10.1:2001

        uint32_t numDisconnects = 0;
        for (int i = 0; i < numClients; i++) {
            Client &client = createClient(initial_hosts, "10.10.10.3");
            client.setServers(new_hosts);
            if (client.isReconfig())
            {
                numDisconnects++;
            }
        }

        // should be numClients/10 in expectation, we test that it's numClients/10 +- slackPercent
        CPPUNIT_ASSERT(numDisconnects < upperboundClientsPerServer(numClients, 10));
    }

    /**
     * Tests the probabilistic load balancing algorithm implemented by the Client
     * code. 
     * 
     * Test strategy:
     * 
     * (1) Start with 9 servers and 10,000 clients. Remove a server, update
     *     everything, and ensure that the clients are redistributed properly.
     * 
     * (2) Remove two more nodes and repeat the same validations of proper client
     *     redistribution. Ensure no clients are connected to the two removed
     *     nodes.
     * 
     * (3) Remove the first server in the list and simultaneously add the three
     *     previously removed servers. Ensure everything is redistributed and
     *     no clients are connected to the one missing node.
     * 
     * (4) Add the one missing server back into the mix and validate.
     */
    void testLoadBalancing()
    {
        zoo_deterministic_conn_order(0);

        int rc = ZOK;

        uint32_t numServers = 9;
        const string initial_hosts = createHostList(numServers); // 10.10.10.9:2009...10.10.10.1:2001

        // Create connections to servers
        for (int i = 0; i < numClients; i++) {
            Client &client = createClient(initial_hosts);
            numClientsPerHost.at(client.getServerPort() - portOffset - 1)++;
        }

        for (int i = 0; i < numServers; i++) {
            CPPUNIT_ASSERT(numClientsPerHost.at(i) <= upperboundClientsPerServer(numClients, numServers));
            CPPUNIT_ASSERT(numClientsPerHost.at(i) >= lowerboundClientsPerServer(numClients, numServers));
            numClientsPerHost.at(i) = 0; // prepare for next test
        }

        // remove last server
        numServers = 8;
        updateAllClientsAndServers(numServers);
        CPPUNIT_ASSERT_EQUAL((uint32_t)0, numClientsPerHost.at(numServers));

        // Remove two more nodes
        numServers = 6;
        updateAllClientsAndServers(numServers);
        CPPUNIT_ASSERT_EQUAL((uint32_t)0, numClientsPerHost.at(numServers));
        CPPUNIT_ASSERT_EQUAL((uint32_t)0, numClientsPerHost.at(numServers+1));
        CPPUNIT_ASSERT_EQUAL((uint32_t)0, numClientsPerHost.at(numServers+2));

        // remove host 0 (first one in list) and add back 6, 7, and 8
        numServers = 8;
        updateAllClientsAndServers(numServers, 1);
        CPPUNIT_ASSERT_EQUAL((uint32_t)0, numClientsPerHost.at(0));

        // add back host number 0
        numServers = 9;
        updateAllClientsAndServers(numServers);
    }
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_reconfig);
