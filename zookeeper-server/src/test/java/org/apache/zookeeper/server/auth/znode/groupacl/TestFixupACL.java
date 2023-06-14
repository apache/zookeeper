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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    assertACLsEqual(
        Collections.singletonList(new ACL(ZooDefs.Perms.ALL, new Id(X509AuthenticationUtil.X509_SCHEME, crossDomain))),
        returnedList
    );
  }

  @Test
  public void testSuperUserId() throws KeeperException.InvalidACLException {
    // User provided ACL list will be honored for super users.
    // No additional ACLs to be set to the znodes besides the user provided ACL list
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, superUserAuthInfo, aclList);
    assertACLsEqual(aclList, returnedList);
  }

  @Test
  public void testSingleDomainUser() throws KeeperException.InvalidACLException {
    // User provided ACL list will not be honored for single domain users.
    // (x509 :  domain name) will be set to the znodes
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, singleDomainAuthInfo, aclList);
    assertACLsEqual(Collections.singletonList(new ACL(ZooDefs.Perms.ALL, singleDomainAuthInfo.get(0))), returnedList);
  }

  @Test
  public void testDedicatedServer() throws KeeperException.InvalidACLException {
    // Should route to original fixupACL logic
    withProperty(X509AuthenticationConfig.DEDICATED_DOMAIN, dedicatedDomain, () -> {
      List<ACL> returnedList =
          PrepRequestProcessor.fixupACL(testPath, dedicatedDomainAuthInfo, aclList);
      assertACLsEqual(aclList, returnedList);
    });
  }

  @Test
  public void testOpenReadPaths() throws KeeperException.InvalidACLException {
    // Should add (world: anyone, r) to returned ACL list
    withProperty(X509AuthenticationConfig.OPEN_READ_ACCESS_PATH_PREFIX, testPath, () -> {
      List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, singleDomainAuthInfo, aclList);
      List<ACL> expected = new ArrayList<>();
      expected.add(new ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE));
      expected.add(new ACL(ZooDefs.Perms.ALL, singleDomainAuthInfo.get(0)));
      assertACLsEqual(expected, returnedList);
    });
  }

  @Test
  public void testMultiId() throws KeeperException.InvalidACLException {
    // User provided ACL list will not be honored for single domain users.
    // One ACL of (x509 :  domain name) for each of the Id in authInfo will be set to the znodes
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, multiIdAuthInfo, aclList);
    List<ACL> expectedList = multiIdAuthInfo.stream()
        .map(id -> new ACL(ZooDefs.Perms.ALL, id))
        .collect(Collectors.toList());
    assertACLsEqual(expectedList, returnedList);
  }

  @Test
  public void testNonX509ZnodeGroupAclUser() throws KeeperException.InvalidACLException {
    // Should route to original fixupACL logic
    List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, nonX509AuthInfo, aclList);
    assertACLsEqual(aclList, returnedList);
  }

  @Test
  public void testAllowedClientIdAsAclDomains() throws KeeperException.InvalidACLException {
    withProperty(X509AuthenticationConfig.SET_X509_CLIENT_ID_AS_ACL, "false", () -> {
      withProperty(X509AuthenticationConfig.ALLOWED_CLIENT_ID_AS_ACL_DOMAINS, "d0,d1", () -> {
        // setClientIdAsACL is disabled but d0 and d1 appear in the allowlist so the returned ACL should contain d0 and
        // d1 but not d2.
        List<Id> authInfo = new ArrayList<>();
        List<ACL> input = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          Id id = new Id(X509AuthenticationUtil.X509_SCHEME, "d" + i);
          authInfo.add(id);
          input.add(new ACL(ZooDefs.Perms.ALL, id));
        }
        List<ACL> expected = input.subList(0, 2);
        List<ACL> returnedList = PrepRequestProcessor.fixupACL(testPath, authInfo, input);
        assertACLsEqual(expected, returnedList);
      });
    });
  }

  private static void assertACLsEqual(Collection<ACL> expected, Collection<ACL> actual) {
    Assert.assertEquals(new HashSet<>(expected), new HashSet<>(actual));
  }

  private static void withProperty(String key, String value, Callable callable) throws KeeperException.InvalidACLException {
    String oldValue = System.setProperty(key, value);
    // Reset the config to ensure it picks up the new property value
    X509AuthenticationConfig.reset();
    try {
      callable.call();
    } finally {
      if (oldValue != null) {
        System.setProperty(key, oldValue);
      } else {
        System.clearProperty(key);
      }
    }
  }

  private interface Callable {
    void call() throws KeeperException.InvalidACLException;
  }
}
