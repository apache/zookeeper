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

import java.util.Set;
import org.apache.zookeeper.server.ServerCnxn;

/**
 * Helper class for looking up the domain name for the client connection. It uses the client's
 * Uniform Resource Identifier (URI) to look up the application domain it belongs to.
 *
 * This class is to be used for ZNode group-based security where one or more clients can be
 * "authorized" to be given clientId that matches the application domain name.
 *
 * To illustrate ZNode group-based security, ZNodes written by a host of "foo" applications will
 * have "foo" as an ACL entity with all accesses (rwcd). Then all ZooKeeper clients belonging to
 * "foo" application will be "authorized" as "foo", as in at TLS handshake/auth time, they will be
 * given "foo" as the clientId. These clients will present their client X509 certificates with their
 * URIs, and this ClientUriDomainMappingHelper will be used at auth time to convert the extracted
 * URI to the appropriate domain name "foo".
 */
public interface ClientUriDomainMappingHelper {
  /**
   * Return a set of application domain names that the given clientUri maps to.
   * @param clientUri
   * @return set of domain names. If not found, returns an empty set.
   */
  Set<String> getDomains(String clientUri);

  /**
   * Update the domain-based AuthInfo for the specified connection.
   * @param cnxn Connection to update
   */
  void updateDomainBasedAuthInfo(ServerCnxn cnxn);
}
