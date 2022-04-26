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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This class test fixupACL method in {@link org.apache.zookeeper.server.PrepRequestProcessor}
 * under the znode group acl settings
 */
public class TestFixupACL {
  private final List<Id> superUserAuthInfo =
      Collections.singletonList(new Id("super", "superUserId"));
  private final List<Id> crossDomainComponentAuthInfo =
      Collections.singletonList(new Id("super", "crossDomain"));
  private final List<ACL> aclList =
      Collections.singletonList(new ACL(ZooDefs.Perms.ALL, new Id("x509", "toBeAdded")));
  private final String testPath = "/zookeeper/testPath";
  private static final Map<String, String> SYSTEM_PROPERTIES = new HashMap<>();

  static {
    SYSTEM_PROPERTIES.put(X509AuthenticationConfig.SET_X509_CLIENT_ID_AS_ACL, "true");
    SYSTEM_PROPERTIES
        .put(X509AuthenticationConfig.ZOOKEEPER_ZNODEGROUPACL_SUPERUSER_ID, "superUserId");
    SYSTEM_PROPERTIES.put(X509AuthenticationConfig.CROSS_DOMAIN_ACCESS_DOMAIN_NAME, "crossDomain");
  }

  @BeforeClass
  public static void beforeClass() {
    SYSTEM_PROPERTIES.forEach(System::setProperty);
  }

  @AfterClass
  public static void afterClass() {
    SYSTEM_PROPERTIES.keySet().forEach(System::clearProperty);
  }

  @Test
  public void testCrossDomainComponents() throws KeeperException.InvalidACLException {
    List<ACL> returnedList =
        PrepRequestProcessor.fixupACL(testPath, crossDomainComponentAuthInfo, aclList);
    Assert.assertEquals(1, returnedList.size());
    Id returnedId = returnedList.get(0).getId();
    Assert.assertEquals("x509", returnedId.getScheme());
    Assert.assertEquals("crossDomain", returnedId.getId());
  }

  @Test
  public void testSuperUserId() throws KeeperException.InvalidACLException {
    List<ACL> returnedList =
        PrepRequestProcessor.fixupACL(testPath, superUserAuthInfo, aclList);
    Assert.assertEquals(1, returnedList.size());
    Id returnedId = returnedList.get(0).getId();
    Assert.assertEquals("x509", returnedId.getScheme());
    Assert.assertEquals("toBeAdded", returnedId.getId());
  }
}
