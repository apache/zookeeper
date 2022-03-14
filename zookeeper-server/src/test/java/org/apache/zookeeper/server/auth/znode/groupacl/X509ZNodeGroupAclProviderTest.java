/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.auth.znode.groupacl;

import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.MockServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.ServerAuthenticationProvider;
import org.apache.zookeeper.server.auth.X509AuthenticationConfig;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.X509AuthTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class X509ZNodeGroupAclProviderTest extends ZKTestCase {
  private static final Logger LOG = LoggerFactory.getLogger(X509ZNodeGroupAclProviderTest.class);
  private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
  private static X509AuthTest.TestCertificate domainXCert;
  private static X509AuthTest.TestCertificate superCert;
  private static X509AuthTest.TestCertificate unknownCert;
  private static X509AuthTest.TestCertificate crossDomainCert;
  private static final String CLIENT_CERT_ID_SAN_MATCH_TYPE = "6";
  private static final String SCHEME = "x509";
  private static ZooKeeperServer zks;
  private TestNIOServerCnxnFactory serverCnxnFactory;
  private ZooKeeper admin;
  private static final String AUTH_PROVIDER_PROPERTY_NAME = "zookeeper.authProvider.x509";
  private static final String CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH = "/_CLIENT_URI_DOMAIN_MAPPING";
  private static final String[] MAPPING_PATHS = {CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH,
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/CrossDomain",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/CrossDomain/CrossDomainUser",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainX",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainX/DomainXUser",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainY",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainY/DomainYUser"};
  private static final Map<String, String> SYSTEM_PROPERTIES = new HashMap<>();
    static {
      SYSTEM_PROPERTIES.put(X509AuthenticationConfig.ZOOKEEPER_ZNODEGROUPACL_SUPERUSER_ID, "SuperUser");
      SYSTEM_PROPERTIES.put("zookeeper.ssl.keyManager", "org.apache.zookeeper.test.X509AuthTest.TestKeyManager");
      SYSTEM_PROPERTIES.put("zookeeper.ssl.trustManager", "org.apache.zookeeper.test.X509AuthTest.TestTrustManager");
      SYSTEM_PROPERTIES.put(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_TYPE, X509AuthenticationConfig.SUBJECT_ALTERNATIVE_NAME_SHORT);
      SYSTEM_PROPERTIES.put(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_MATCH_TYPE, CLIENT_CERT_ID_SAN_MATCH_TYPE);
      SYSTEM_PROPERTIES.put(AUTH_PROVIDER_PROPERTY_NAME, X509ZNodeGroupAclProvider.class.getCanonicalName());
      SYSTEM_PROPERTIES.put(X509AuthenticationConfig.CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH, CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH);
      SYSTEM_PROPERTIES.put(X509AuthenticationConfig.CROSS_DOMAIN_ACCESS_DOMAIN_NAME, "CrossDomain");
    }

  @Before
  public void setUp() throws Exception {
    for (Map.Entry<String, String> property : SYSTEM_PROPERTIES.entrySet()) {
      System.setProperty(property.getKey(), property.getValue());
    }
    LOG.info("Starting Zk...");
    zks = new ZooKeeperServer(testBaseDir, testBaseDir, 3000);
    final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
    serverCnxnFactory = new TestNIOServerCnxnFactory();
    serverCnxnFactory.configure(new InetSocketAddress(PORT), -1, -1);
    serverCnxnFactory.startup(zks);
    LOG.info("Waiting for server startup");
    Assert.assertTrue("waiting for server being up ", ClientBase.waitForServerUp(HOSTPORT, 300000));
    admin = ClientBase.createZKClient(HOSTPORT);
    try {
      ZKUtil.deleteRecursive(admin, CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH);
    } catch (Exception ignored) {
    }

    // Create test client certificates
    domainXCert = new X509AuthTest.TestCertificate("CLIENT", "DomainXUser");
    superCert = new X509AuthTest.TestCertificate("SUPER", "SuperUser");
    unknownCert = new X509AuthTest.TestCertificate("UNKNOWN", "UnknownUser");
    crossDomainCert = new X509AuthTest.TestCertificate("CLIENT", "CrossDomainUser");

    // Create Client URI - domain mapping znodes
    for (String path : MAPPING_PATHS) {
      // Create ACL metadata
      admin.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }
  }

  @After
  public void cleanUp() throws InterruptedException, KeeperException {
    LOG.info("X509ZNodeGroupAclProviderTest::cleanUp() called!");
    X509AuthenticationConfig.reset();
    ZKUtil.deleteRecursive(admin, CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH);
    zks.shutdown();
    admin.close();
    serverCnxnFactory.shutdown();
    for (Map.Entry<String, String> property : SYSTEM_PROPERTIES.entrySet()) {
      System.clearProperty(property.getKey());
    }
  }

  @Test
  public void testUntrustedClient() {
    X509ZNodeGroupAclProvider provider = createProvider(domainXCert);
    MockServerCnxn cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{unknownCert};
    Assert.assertEquals(KeeperException.Code.AUTHFAILED, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
  }

  @Test
  public void testAuthorizedClient() {
    X509ZNodeGroupAclProvider provider = createProvider(domainXCert);
    MockServerCnxn cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{domainXCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    List<Id> authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals(SCHEME, authInfo.get(0).getScheme());
    Assert.assertEquals("DomainX", authInfo.get(0).getId());
  }

  @Test
  public void testUnauthorizedClient() {
    X509ZNodeGroupAclProvider provider = createProvider(unknownCert);
    MockServerCnxn cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{unknownCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    List<Id> authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals(SCHEME, authInfo.get(0).getScheme());
    Assert.assertEquals("UnknownUser", authInfo.get(0).getId());
  }

  @Test
  public void testSuperUser() {
    // Belong to super user domain
    X509ZNodeGroupAclProvider provider = createProvider(crossDomainCert);
    MockServerCnxn cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{crossDomainCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    List<Id> authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals("super", authInfo.get(0).getScheme());
    Assert.assertEquals("CrossDomainUser", authInfo.get(0).getId());

    // Directly set in config as super user
    provider = createProvider(superCert);
    cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{superCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals("super", authInfo.get(0).getScheme());
    Assert.assertEquals("SuperUser", authInfo.get(0).getId());
  }

  @Test
  public void testAuthInfoAutoUpdate() throws InterruptedException, KeeperException {
    String clientId = "DomainZUser";
    X509AuthTest.TestCertificate domainZCert = new X509AuthTest.TestCertificate("CLIENT", clientId);
    String oldDomain = CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainZ";
    String newDomain = CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/DomainZN";

    admin.create(oldDomain, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    admin.create(oldDomain + "/" + clientId, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    // Test with original domain info
    X509ZNodeGroupAclProvider provider = createProvider(domainZCert);
    MockServerCnxn cnxn = new MockServerCnxn();

    // Inject the new connection to factory so it's AuthInfo will be auto-refreshed.
    serverCnxnFactory.getClients().add(cnxn);

    // Check the original status.
    cnxn.clientChain = new X509Certificate[]{domainZCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    List<Id> authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals(SCHEME, authInfo.get(0).getScheme());
    Assert.assertEquals("DomainZ", authInfo.get(0).getId());

    // Add new domain info.
    admin.create(newDomain, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    admin.create(newDomain + "/" + clientId, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    waitFor("AuthInfo is not updated after new domain created.", () -> {
      return cnxn.getAuthInfo().size() == 2;
    }, 3);

    // Remove the original domain info
    admin.delete(oldDomain + "/" + clientId, -1);
    admin.delete(oldDomain, -1);
    waitFor("AuthInfo is not updated after old domain removed.", () -> {
      List<Id> newAuthInfo = cnxn.getAuthInfo();
      return 1 == newAuthInfo.size() &&
          SCHEME.equals(newAuthInfo.get(0).getScheme()) &&
          newAuthInfo.get(0).getId().equals("DomainZN");
    }, 3);
  }

  @Test
  public void testConnectionFiltering() {
    // Single domain user
    System.setProperty(X509AuthenticationConfig.DEDICATED_DOMAIN, "DomainX");
    X509ZNodeGroupAclProvider provider = createProvider(domainXCert);
    MockServerCnxn cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{domainXCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    List<Id> authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals(SCHEME, authInfo.get(0).getScheme());
    Assert.assertEquals("DomainXUser", authInfo.get(0).getId());

    // Non-authorized user
    ClosableMockServerCnxn closableMockServerCnxn = new ClosableMockServerCnxn();
    closableMockServerCnxn.clientChain = new X509Certificate[]{domainXCert};
    System.clearProperty(X509AuthenticationConfig.DEDICATED_DOMAIN);
    System.setProperty(X509AuthenticationConfig.DEDICATED_DOMAIN, "DomainY");
    X509AuthenticationConfig.reset();
    provider = createProvider(domainXCert);
    provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, closableMockServerCnxn), new byte[0]);
    Assert.assertTrue(closableMockServerCnxn.isClosed());

    // Super user
    provider = createProvider(superCert);
    cnxn = new MockServerCnxn();
    cnxn.clientChain = new X509Certificate[]{superCert};
    Assert.assertEquals(KeeperException.Code.OK, provider
        .handleAuthentication(new ServerAuthenticationProvider.ServerObjs(zks, cnxn), new byte[0]));
    authInfo = cnxn.getAuthInfo();
    Assert.assertEquals(1, authInfo.size());
    Assert.assertEquals("super", authInfo.get(0).getScheme());
    Assert.assertEquals("SuperUser", authInfo.get(0).getId());
    System.clearProperty(X509AuthenticationConfig.DEDICATED_DOMAIN);
  }

  private X509ZNodeGroupAclProvider createProvider(X509Certificate trustedCert) {
    return new X509ZNodeGroupAclProvider(new X509AuthTest.TestTrustManager(trustedCert),
        new X509AuthTest.TestKeyManager());
  }

  /**
   * Special ServerCnxnFactory which Exposes the client list for testing auto-refresh AuthInfo.
   */
  class TestNIOServerCnxnFactory extends NIOServerCnxnFactory {
    Set<ServerCnxn> getClients() {
      return cnxns;
    }
  }

  private static class ClosableMockServerCnxn extends MockServerCnxn {
    private boolean isClosed = false;

    @Override
    public void close(DisconnectReason reason) {
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }
  }
}

