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

import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.ServerAuthenticationProvider;
import org.apache.zookeeper.server.auth.X509AuthenticationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ServerAuthenticationProvider implementation that does both authentication and authorization for protecting znodes from unauthorized access.
 * Znodes are grouped into domains according to their ownership, and clients are granted access permission to domains.
 * Authentication mechanism is same as in X509AuthenticationProvider. If authentication is failed,
 *  decline the connection request.
 * Authorization is done by checking clientId which is usually an URI (uniform resource identifier)in ACL metadata for matched domains.
 * Detailed step for authorization is:
 *  1. Acl provider attempts to extract the clientId from the cert provided by the client
 *  2. Acl provider attempts to look up the client's domain using the clientId and ClientUriDomainMappingHelper
 *  3. If matched domain is found, add the domain in authInfo in the connection object;
 *     if no matched domain is found, add the clientId in authInfo instead; either way, establish the connection.
 * This class is meant to support the following use patterns:
 *  1. Single domain: The creator and the accessor to the znodes belong to the same domain. This is
 *     the most straightforward use case, and accessors in other domains won't be able to access the
 *     znodes due to their clientId not matching any in the znodes.
 *  2. Super user domain: The accessors need permission to access znodes created by creators belong
 *     to multiple domains. In this case the accessors will be mapped to super user domain and be
 *     given super user privilege.
 *  3. Open read access: The znodes need to be accessed by accessors from many different domains, so when
 *     the creators create these znodes, these nodes will be given "open read access" (see below).
 * Optional features include:
 *    "auto-set ACL": add ZooDefs.Ids.CREATOR_ALL_ACL to all newly-created znodes,
 *    "open read access": this feature concerns read access only. Add (world, anyone r) to all
 *          newly-written znodes whose path prefixes are given in the znode group acl config
 *          (comma-delimited, multiple such prefixes are possible).
 */
public class X509ZNodeGroupAclProvider extends ServerAuthenticationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(X509ZNodeGroupAclProvider.class);
  private final String logStrPrefix = this.getClass().getName() + ":: ";
  private final X509TrustManager trustManager;
  private final X509KeyManager keyManager;
  static final String ZOOKEEPER_ZNODEGROUPACL_SUPERUSER = "zookeeper.znodeGroupAcl.superUser";
  // Although using "volatile" keyword with double checked locking could prevent the undesired
  //creation of multiple objects; not using here for the consideration of read performance
  private ClientUriDomainMappingHelper uriDomainMappingHelper = null;

  public X509ZNodeGroupAclProvider() {
    ZKConfig config = new ZKConfig();
    this.keyManager = X509AuthenticationUtil.createKeyManager(config);
    this.trustManager = X509AuthenticationUtil.createTrustManager(config);
  }

  public X509ZNodeGroupAclProvider(X509TrustManager trustManager, X509KeyManager keyManager) {
    this.trustManager = trustManager;
    this.keyManager = keyManager;
  }

  @Override
  public KeeperException.Code handleAuthentication(ServerObjs serverObjs, byte[] authData) {
    ServerCnxn cnxn = serverObjs.getCnxn();
    X509Certificate clientCert;
    try {
      clientCert = X509AuthenticationUtil.getAuthenticatedClientCert(cnxn, trustManager);
    } catch (KeeperException.AuthFailedException e) {
      return KeeperException.Code.AUTHFAILED;
    }

    // The clientId can be any string matched and extracted using regex from Subject Distinguished
    // Name or Subject Alternative Name from x509 certificate.
    // The clientId string is intended to be an URI for client and map the client to certain domain.
    //The user can use the properties defined in X509AuthenticationUtil to extract a desired string as clientId.
    String clientId;
    try {
      clientId = X509AuthenticationUtil.getClientId(clientCert);
    } catch (Exception e) {
      // Failed to extract clientId from certificate
      LOG.error(logStrPrefix + "Failed to extract URI from certificate for session 0x{}",
          Long.toHexString(cnxn.getSessionId()), e);
      return KeeperException.Code.OK;
    }

    // User belongs to super user group
    if (clientId.equals(System.getProperty(ZOOKEEPER_ZNODEGROUPACL_SUPERUSER))) {
      cnxn.addAuthInfo(new Id("super", clientId));
      LOG.info("Authenticated Id '{}' as super user", clientId);
      return KeeperException.Code.OK;
    }

    // Get authorized domain names for client
    Set<String> domains = getUriDomainMappingHelper(serverObjs.getZks()).getDomains(clientId);
    if (domains.isEmpty()) {
      // If no domain name is found, use URI as domain name
      domains = new HashSet<>();
      domains.add(clientId);
    }

    Set<String> superUserDomainNames =
        ZNodeGroupAclProperties.getInstance().getSuperUserDomainNames();
    for (String domain : domains) {
      // Grant super user privilege to users belong to super user domains
      if (superUserDomainNames.contains(domain)) {
        cnxn.addAuthInfo(new Id("super", clientId));
        LOG.info(
            logStrPrefix + "Id '{}' belongs to superUser domain '{}', authenticated as super user",
            clientId, domain);
      } else {
        cnxn.addAuthInfo(new Id(getScheme(), domain));
        LOG.info(logStrPrefix + "Authenticated Id '{}' for Scheme '{}', Domain '{}'.", clientId,
            getScheme(), domain);
      }
    }

    return KeeperException.Code.OK;
  }

  @Override
  public boolean matches(ServerObjs serverObjs, MatchValues matchValues) {
    for (Id id : serverObjs.getCnxn().getAuthInfo()) {
      // Not checking for super user here because the check is already covered
      // in checkAcl() in ZookeeperServer.class
      if (id.getId().equals(matchValues.getAclExpr())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String getScheme() {
    // Same scheme as X509AuthenticationProvider since they both use x509 certificate
    return "x509";
  }

  @Override
  public boolean isAuthenticated() {
    return true;
  }

  @Override
  public boolean isValid(String id) {
    try {
      new X500Principal(id);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private ClientUriDomainMappingHelper getUriDomainMappingHelper(ZooKeeperServer zks) {
    if (uriDomainMappingHelper == null) {
      synchronized (this) {
        if (uriDomainMappingHelper == null) {
          uriDomainMappingHelper = new ZkClientUriDomainMappingHelper(zks);
        }
      }
    }
    return uriDomainMappingHelper;
  }
}
