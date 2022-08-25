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

package org.apache.zookeeper.server.auth;

import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ServerCnxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Util class for x509 certificate-based authentication providers.
 */
public class X509AuthenticationUtil extends X509Util {

  private static final Logger LOG = LoggerFactory.getLogger(X509AuthenticationUtil.class);

  // Super user Auth Id scheme
  public static final String SUPERUSER_AUTH_SCHEME = "super";
  public static final String X509_SCHEME = "x509";

  @Override
  protected String getConfigPrefix() {
    return X509AuthenticationConfig.SSL_X509_CONFIG_PREFIX;
  }

  @Override
  protected boolean shouldVerifyClientHostname() {
    return false;
  }

  /**
   * Create key manager from config for x509-based authentication provider
   * @param config ZooKeeper config
   * @return An X509KeyManager instance
   */
  public static X509KeyManager createKeyManager(ZKConfig config) {
    try (X509Util x509Util = new ClientX509Util()) {
      String keyStoreLocation = config.getProperty(x509Util.getSslKeystoreLocationProperty(), "");
      String keyStorePassword = config.getProperty(x509Util.getSslKeystorePasswdProperty(), "");
      String keyStoreTypeProp = config.getProperty(x509Util.getSslKeystoreTypeProperty());

      X509KeyManager km = null;
      if (keyStoreLocation.isEmpty()) {
        LOG.warn("Key store location not specified for SSL");
      } else {
        try {
          km = X509Util.createKeyManager(keyStoreLocation, keyStorePassword, keyStoreTypeProp);
        } catch (X509Exception.KeyManagerException e) {
          LOG.error("Failed to create key manager", e);
        }
      }
      return km;
    }
  }

  /**
   * Create trust manager from config for x509-based authentication provider
   * @param config ZooKeeper config
   * @return An X509TrustManager instance
   */
  public static X509TrustManager createTrustManager(ZKConfig config) {
    try (X509Util x509Util = new ClientX509Util()) {
      boolean crlEnabled =
          Boolean.parseBoolean(config.getProperty(x509Util.getSslCrlEnabledProperty()));
      boolean ocspEnabled =
          Boolean.parseBoolean(config.getProperty(x509Util.getSslOcspEnabledProperty()));
      boolean hostnameVerificationEnabled = Boolean
          .parseBoolean(config.getProperty(x509Util.getSslHostnameVerificationEnabledProperty()));
      String trustStoreLocation =
          config.getProperty(x509Util.getSslTruststoreLocationProperty(), "");
      String trustStorePassword = config.getProperty(x509Util.getSslTruststorePasswdProperty(), "");
      String trustStoreTypeProp = config.getProperty(x509Util.getSslTruststoreTypeProperty());

      X509TrustManager tm = null;
      if (trustStoreLocation.isEmpty()) {
        LOG.warn("Truststore location not specified for SSL");
      } else {
        try {
          tm = X509Util
              .createTrustManager(trustStoreLocation, trustStorePassword, trustStoreTypeProp,
                  crlEnabled, ocspEnabled, hostnameVerificationEnabled, false);
        } catch (X509Exception.TrustManagerException e) {
          LOG.error("Failed to create trust manager", e);
        }
      }
      return tm;
    }
  }

  /**
   * Determine the string to be used as the remote host session Id for
   * authorization purposes. Associate this client identifier with a
   * ServerCnxn that has been authenticated over SSL, and any ACLs that refer
   * to the authenticated client.
   *
   * @param clientCert Authenticated X509Certificate associated with the
   *                   remote host.
   * @return Identifier string to be associated with the client.
   *         The clientId can be any string matched and extracted using regex from Subject Distinguished Name or
   *         Subject Alternative Name from x509 certificate.
   *         The clientId string is intended to be an URI for client and map the client to certain domain.
   */
  public static String getClientId(X509Certificate clientCert) {
    String clientCertIdType = X509AuthenticationConfig.getInstance().getClientCertIdType();
    if (clientCertIdType != null && clientCertIdType
        .equalsIgnoreCase(X509AuthenticationConfig.SUBJECT_ALTERNATIVE_NAME_SHORT)) {
      try {
        return X509AuthenticationUtil.matchAndExtractSAN(clientCert);
      } catch (Exception ce) {
        LOG.warn("Failed to match and extract a client ID from SAN. Using Subject DN instead.", ce);
      }
    }
    // return Subject DN by default
    return clientCert.getSubjectX500Principal().getName();
  }

  /**
   * Extract the authenticated client Id from the specified server connection object.
   * @param cnxn Server connection object that contains the certificate.
   * @param trustManager X509 TrustManager for authentication.
   * @return Identifier string to be associated with the client.
   *         The clientId can be any string matched and extracted using regex from Subject Distinguished Name or
   *         Subject Alternative Name from x509 certificate.
   *         The clientId string is intended to be an URI for client and map the client to certain domain.
   * @throws KeeperException.AuthFailedException Failed to authenticate the client certificate
   */
  public static String getClientId(ServerCnxn cnxn, X509TrustManager trustManager)
      throws KeeperException.AuthFailedException {
    X509Certificate clientCert = X509AuthenticationUtil.getAuthenticatedClientCert(cnxn, trustManager);
    return X509AuthenticationUtil.getClientId(clientCert);
  }

  /**
   * Extract SAN field from an X509 certificate
   * @param clientCert Client x509 certificate
   * @return Subject alternative name (SAN) string
   * @throws CertificateParsingException
   */
  private static String matchAndExtractSAN(X509Certificate clientCert)
      throws CertificateParsingException {
    int matchType = X509AuthenticationConfig.getInstance().getClientCertIdSanMatchType();
    String matchRegex = X509AuthenticationConfig.getInstance().getClientCertIdSanMatchRegex();
    String extractRegex = X509AuthenticationConfig.getInstance().getClientCertIdSanExtractRegex();
    int extractMatcherGroupIndex =
        X509AuthenticationConfig.getInstance().getClientCertIdSanExtractMatcherGroupIndex();
    LOG.debug("Using SAN from client cert to extract client ID. matchType: {}, matchRegex: {}, extractRegex: {}, "
            + "extractMatcherGroupIndex: {}", matchType, matchRegex, extractRegex,
        extractMatcherGroupIndex);
    if (matchRegex == null || extractRegex == null || matchType < 0 || matchType > 8) {
      // SAN extension must be in the range of [0, 8].
      // See GeneralName object defined in RFC 5280 (The ASN.1 definition of the SubjectAltName extension)
      String errStr = "Client cert ID type 'SAN' was provided but matchType or matchRegex given is invalid! "
          + "matchType: " + matchType + " matchRegex: " + matchRegex;
      LOG.error(errStr);
      throw new IllegalArgumentException(errStr);
    }
    // filter by match type and match regex
    LOG.debug("Number of SAN entries found in client cert: " + clientCert.getSubjectAlternativeNames().size());
    Pattern matchPattern = Pattern.compile(matchRegex);
    Collection<List<?>> matched = clientCert.getSubjectAlternativeNames().stream().filter(
        list -> list.get(0).equals(matchType) && matchPattern.matcher((CharSequence) list.get(1))
            .find()).collect(Collectors.toList());

    LOG.debug("Number of SAN entries matched: " + matched.size() + ". Printing all matches...");
    for (List<?> match : matched) {
      LOG.debug("Match: (" + match.get(0) + ", " + match.get(1) + ")");
    }

    // if there are more than one match or 0 matches, throw an error
    if (matched.size() != 1) {
      String errStr = "Zero or multiple matches found in SAN! Please fix match type and regex so that exactly one match "
          + "is found.";
      LOG.error(errStr);
      throw new IllegalArgumentException(errStr);
    }

    // Extract a substring from the found match using extractRegex
    Pattern extractPattern = Pattern.compile(extractRegex);
    Matcher matcher = extractPattern.matcher(matched.iterator().next().get(1).toString());
    if (matcher.find()) {
      // If extractMatcherGroupIndex is not given, return the 1st index by default
      String result = matcher.group(extractMatcherGroupIndex);
      LOG.debug("Returning extracted client ID: {} using Matcher group index: {}", result, extractMatcherGroupIndex);
      return result;
    }
    String errStr = "Failed to find an extract substring to determine client ID. Please review the extract regex.";
    LOG.error(errStr);
    throw new IllegalArgumentException(errStr);
  }

  /**
   * Get a client certificate from server connection object and authenticate the certificate using X509TrustManager
   * @param cnxn ServerCnxn object
   * @param trustManager
   * @return The authenticated client certificate
   * @throws KeeperException.AuthFailedException Failed to authenticate the client certificate
   */
  public static X509Certificate getAuthenticatedClientCert(ServerCnxn cnxn, X509TrustManager trustManager)
      throws KeeperException.AuthFailedException {
    X509Certificate[] certChain = (X509Certificate[]) cnxn.getClientCertificateChain();

    if (certChain == null || certChain.length == 0) {
      String errMsg = "No X509 certificate is found in cert chain.";
      LOG.error(errMsg);
      throw new KeeperException.AuthFailedException();
    }

    X509Certificate clientCert = certChain[0];

    if (trustManager == null) {
      String errMsg = "No trust manager available to authenticate session 0x" + Long.toHexString(cnxn.getSessionId());
      LOG.error(errMsg);
      throw new KeeperException.AuthFailedException();
    }

    try {
      // Authenticate client certificate
      trustManager.checkClientTrusted((X509Certificate[]) cnxn.getClientCertificateChain(), clientCert.getPublicKey().getAlgorithm());
    } catch (CertificateException ce) {
      String errMsg = "Failed to trust certificate for session 0x" + Long.toHexString(cnxn.getSessionId());
      LOG.error(errMsg, ce);
      throw new KeeperException.AuthFailedException();
    }
    return certChain[0];
  }
}
