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

package org.apache.zookeeper.server.auth.znode.groupacl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.auth.X509AuthenticationConfig;
import org.apache.zookeeper.server.auth.X509AuthenticationUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * This class test fixupACL method in {@link org.apache.zookeeper.server.PrepRequestProcessor}
 * under the znode group acl settings
 */
public class TestFixupACL {
  private final String testPath = "/zookeeper/testPath";
  private final String dedicatedDomain = "dedicatedDomain";
  private final String crossDomain = "crossDomain";
  private final List<Id> singleDomainAuthInfo =
      Collections.singletonList(new Id(X509AuthenticationUtil.X509_SCHEME, "domain"));
  private final List<Id> superUserAuthInfo =
      Collections.singletonList(new Id("super", "superUserId"));
  private final List<Id> crossDomainComponentAuthInfo =
      Collections.singletonList(new Id("super", crossDomain));
  private final List<Id> dedicatedDomainAuthInfo =
      Collections.singletonList(new Id(X509AuthenticationUtil.X509_SCHEME, dedicatedDomain));
  private final List<Id> multiIdAuthInfo = new ArrayList<>();

  {
    multiIdAuthInfo.add(new Id(X509AuthenticationUtil.X509_SCHEME, "domain1"));
    multiIdAuthInfo.add(new Id(X509AuthenticationUtil.X509_SCHEME, "domain2"));
  }

  private final List<Id> nonX509AuthInfo =
      Collections.singletonList(new Id("ip", "127.0.0.1:2183"));

  private List<ACL> aclList = new ArrayList<>();

  {
    aclList
        .add(new ACL(ZooDefs.Perms.ALL, new Id(X509AuthenticationUtil.X509_SCHEME, "toBeAdded")));
    aclList.add(new ACL(ZooDefs.Perms.ALL, ZooDefs.Ids.ANYONE_ID_UNSAFE));
  }

  private final Map<String, String> SYSTEM_PROPERTIES = new HashMap<>();

  {
    SYSTEM_PROPERTIES
        .put("zookeeper.authProvider.x509", X509ZNodeGroupAclProvider.class.getCanonicalName());
    SYSTEM_PROPERTIES.put(X509AuthenticationConfig.SET_X509_CLIENT_ID_AS_ACL, "true");
    SYSTEM_PROPERTIES
        .put(X509AuthenticationConfig.ZOOKEEPER_ZNODEGROUPACL_SUPERUSER_ID, "superUserId");
  }

  @Before
  public void before() {
    SYSTEM_PROPERTIES.forEach(System::setProperty);
  }

  @After
  public void after() {
    SYSTEM_PROPERTIES.keySet().forEach(System::clearProperty);
    X509AuthenticationConfig.reset();
  }

  @Test
  public void testCrossDomainComponents() throws KeeperException.InvalidACLException {
    // User provided ACL list will not be honored for cross domain components.
    // (x509 :  domain name) will be set to the znodes
    List<ACL> returnedList =
        PrepRequestProcessor.fixupACL(testPath, crossDomainComponentAuthInfo, aclList);
    Assert.assertEquals(1, returnedList.size());
    Assert.assertTrue(returnedList.contains(
        new ACL(ZooDefs.Perms.ALL, new Id(X509AuthenticationUtil.X509_SCHEME, crossDomain))));
  }

  @Test
  public void testSuperUserId() throws KeeperException.InvalidACLException {
    // User provided ACL list will be honored for super users.
    // No additional ACLs to be set to the znodes besides the user provided ACL list
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, superUserAuthInfo, aclList);
    Assert.assertEquals(2, returnedList.size());
    Assert.assertTrue(returnedList.containsAll(aclList));
  }

  @Test
  public void testSingleDomainUser() throws KeeperException.InvalidACLException {
    // User provided ACL list will not be honored for single domain users.
    // (x509 :  domain name) will be set to the znodes
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, singleDomainAuthInfo, aclList);
    Assert.assertEquals(1, returnedList.size());
    Assert
        .assertTrue(returnedList.contains(new ACL(ZooDefs.Perms.ALL, singleDomainAuthInfo.get(0))));
  }

  @Test
  public void testDedicatedServer() throws KeeperException.InvalidACLException {
    // Should route to original fixupACL logic
    System.setProperty(X509AuthenticationConfig.DEDICATED_DOMAIN, dedicatedDomain);
    List<ACL> returnedList =
        PrepRequestProcessor.fixupACL(testPath, dedicatedDomainAuthInfo, aclList);
    Assert.assertEquals(2, returnedList.size());
    Assert.assertTrue(returnedList.containsAll(aclList));
    System.clearProperty(X509AuthenticationConfig.DEDICATED_DOMAIN);
  }

  @Test
  public void testOpenReadPaths() throws KeeperException.InvalidACLException {
    // Should add (world: anyone, r) to returned ACL list
    System.setProperty(X509AuthenticationConfig.OPEN_READ_ACCESS_PATH_PREFIX, testPath);
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, singleDomainAuthInfo, aclList);
    Assert.assertEquals(2, returnedList.size());
    Assert.assertTrue(
        returnedList.contains(new ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE)));
    System.clearProperty(X509AuthenticationConfig.OPEN_READ_ACCESS_PATH_PREFIX);
  }

  @Test
  public void testMultiId() throws KeeperException.InvalidACLException {
    // User provided ACL list will not be honored for single domain users.
    // One ACL of (x509 :  domain name) for each of the Id in authInfo will be set to the znodes
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, multiIdAuthInfo, aclList);
    Assert.assertEquals(2, returnedList.size());
    multiIdAuthInfo
        .forEach(id -> Assert.assertTrue(returnedList.contains(new ACL(ZooDefs.Perms.ALL, id))));
  }

  @Test
  public void testNonX509ZnodeGroupAclUser() throws KeeperException.InvalidACLException {
    // Should route to original fixupACL logic
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, nonX509AuthInfo, aclList);
    Assert.assertEquals(2, returnedList.size());
    Assert.assertTrue(returnedList.containsAll(aclList));
  }
}
