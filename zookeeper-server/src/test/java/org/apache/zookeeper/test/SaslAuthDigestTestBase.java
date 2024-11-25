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

import org.apache.zookeeper.common.X509Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Created as a base class for Digest Auth based SASL authentication tests.
 * We need to disable Fips mode, otherwise DIGEST-MD5 cannot be used.
 *
 * @see org.apache.zookeeper.server.quorum.auth.DigestSecurityTestcase
 */
public class SaslAuthDigestTestBase extends ClientBase {

  @BeforeAll
  public static void beforeClass() throws Exception {
    // Need to disable Fips-mode, because we use DIGEST-MD5 mech for Sasl
    System.setProperty(X509Util.FIPS_MODE_PROPERTY, "false");
  }

  @AfterAll
  public static void afterClass() throws Exception {
    System.clearProperty(X509Util.FIPS_MODE_PROPERTY);
  }

}
