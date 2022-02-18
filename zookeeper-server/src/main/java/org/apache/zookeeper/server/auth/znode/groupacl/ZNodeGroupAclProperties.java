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

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import com.google.common.annotations.VisibleForTesting;

/**
 * Configured properties for ZNode Group ACL feature
 */
public class ZNodeGroupAclProperties {
  private static ZNodeGroupAclProperties instance = null;

  private ZNodeGroupAclProperties() {
  }

  public static ZNodeGroupAclProperties getInstance() {
    if (instance == null) {
      synchronized (ZNodeGroupAclProperties.class) {
        if (instance == null) {
          instance = new ZNodeGroupAclProperties();
        }
      }
    }
    return instance;
  }

  /**
   * Property key values (JVM configs) for ZNode group ACL features
   */
  public static final String ZNODE_GROUP_ACL_CONFIG_PREFIX = "zookeeper.ssl.znodeGroupAcl.";
  // Enables/disables whether znodes created by auth'ed clients
  // should have ACL fields populated with the client Id given by the authentication provider.
  // Has the same effect as the ZK client using ZooDefs.Ids.CREATOR_ALL_ACL.
  public static final String SET_X509_CLIENT_ID_AS_ACL =
      ZNODE_GROUP_ACL_CONFIG_PREFIX + "setX509ClientIdAsAcl";
  // A list of domain names that will have super user privilege, separated by ","
  @VisibleForTesting
  static final String SUPER_USER_DOMAIN_NAME =
      ZNODE_GROUP_ACL_CONFIG_PREFIX + "superUserDomainName";
  // A defined URI represents a super user
  static final String ZOOKEEPER_ZNODEGROUPACL_SUPERUSER = "zookeeper.znodeGroupAcl.superUser";
  // A list of znode path prefixes, separated by ","
  // Znode whose path starts with the defined path prefix would have open read access
  // Meaning the znode will have (world:anyone, r) ACL
  private static final String OPEN_READ_ACCESS_PATH_PREFIX =
      ZNODE_GROUP_ACL_CONFIG_PREFIX + "openReadAccessPathPrefix";
  // If the server is dedicated for one domain, use this config property to define the domain name,
  // and enable connection filtering feature for this domain
  public static final String DEDICATED_DOMAIN = ZNODE_GROUP_ACL_CONFIG_PREFIX + "dedicatedDomain";
  // Although using "volatile" keyword with double checked locking could prevent the undesired
  //creation of multiple objects; not using here for the consideration of read performance
  private Set<String> openReadAccessPathPrefixes;
  private Set<String> superUserDomainNames;
  private final Object openReadAccessPathPrefixesLock = new Object();
  private final Object superUserDomainNamesLock = new Object();
  private final String serverDedicatedDomain = System.getProperty(DEDICATED_DOMAIN);

  /**
   * Get open read access path prefixes from config
   * @return A set of path prefixes
   */
  private Set<String> loadOpenReadAccessPathPrefixes() {
    String openReadAccessPathPrefixesStr = System.getProperty(OPEN_READ_ACCESS_PATH_PREFIX);
    if (openReadAccessPathPrefixesStr == null || openReadAccessPathPrefixesStr.isEmpty()) {
      return Collections.emptySet();
    }
    return Arrays.stream(openReadAccessPathPrefixesStr.split(",")).filter(str -> str.length() > 0)
        .collect(Collectors.toSet());
  }

  /**
   * Get the domain names that are mapped to super user access privilege
   * @return A set of domain names
   */
  private Set<String> loadSuperUserDomainNames() {
    String superUserDomainNameStr = System.getProperty(SUPER_USER_DOMAIN_NAME);
    if (superUserDomainNameStr == null || superUserDomainNameStr.isEmpty()) {
      return Collections.emptySet();
    }
    return Arrays.stream(superUserDomainNameStr.split(",")).filter(str -> str.length() > 0)
        .collect(Collectors.toSet());
  }

  public Set<String> getOpenReadAccessPathPrefixes() {
    if (openReadAccessPathPrefixes == null) {
      synchronized (openReadAccessPathPrefixesLock) {
        if (openReadAccessPathPrefixes == null) {
          openReadAccessPathPrefixes = loadOpenReadAccessPathPrefixes();
        }
      }
    }
    return openReadAccessPathPrefixes;
  }

  public Set<String> getSuperUserDomainNames() {
    if (superUserDomainNames == null) {
      synchronized (superUserDomainNamesLock) {
        if (superUserDomainNames == null) {
          superUserDomainNames = loadSuperUserDomainNames();
        }
      }
    }
    return superUserDomainNames;
  }

  public String getServerDedicatedDomain() {
    return serverDedicatedDomain;
  }

  @VisibleForTesting
  public static void clearProperties() {
    synchronized (ZNodeGroupAclProperties.class) {
      instance = null;
    }
  }
}
