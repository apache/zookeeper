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

package org.apache.zookeeper.test;

import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthFailX509AuthenticationProvider extends X509AuthenticationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(AuthFailX509AuthenticationProvider.class);

  public AuthFailX509AuthenticationProvider() throws X509Exception {
    super();
  }

  public AuthFailX509AuthenticationProvider(X509TrustManager trustManager, X509KeyManager keyManager) {
    super(trustManager, keyManager);
  }

  @Override
  public KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
    LOG.info("Authentication failed");
    return KeeperException.Code.AUTHFAILED;
  }

  @Override
  public String getScheme() {
    return "authfail";
  }
}
