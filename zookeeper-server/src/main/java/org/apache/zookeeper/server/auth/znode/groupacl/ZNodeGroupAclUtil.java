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

/**
 * Util class for ZNode Group ACL. Contains util methods and constants.
 */
public class ZNodeGroupAclUtil {

  /**
   * Property key values (JVM configs) for ZNode group ACL features
   */
  public static final String ZNODE_GROUP_ACL_CONFIG_PREFIX = "zookeeper.ssl.znodeGroupAcl.";
  // Enables/disables whether znodes created by auth'ed clients
  // should have ACL fields populated with the client Id given by the authentication provider.
  // Has the same effect as the ZK client using ZooDefs.Ids.CREATOR_ALL_ACL.
  public static final String SET_X509_CLIENT_ID_AS_ACL =
      ZNODE_GROUP_ACL_CONFIG_PREFIX + "setX509ClientIdAsAcl";
}
