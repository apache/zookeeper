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

package org.apache.zookeeper;

import static org.apache.zookeeper.server.quorum.auth.MiniKdc.MAX_TICKET_LIFETIME;
import static org.apache.zookeeper.server.quorum.auth.MiniKdc.MIN_TICKET_LIFETIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.auth.KerberosTestUtils;
import org.apache.zookeeper.server.quorum.auth.MiniKdc;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test class is mainly testing the TGT renewal logic implemented
 * in the org.apache.zookeeper.Login class.
 */
public class KerberosTicketRenewalTest {


  private static final Logger LOG = LoggerFactory.getLogger(KerberosTicketRenewalTest.class);
  private static final String JAAS_CONFIG_SECTION = "ClientUsingKerberos";
  private static final String TICKET_LIFETIME = "5000";
  private static File testTempDir;
  private static MiniKdc kdc;
  private static File kdcWorkDir;
  private static String PRINCIPAL = KerberosTestUtils.getClientPrincipal();

  TestableKerberosLogin login;

  @BeforeAll
  public static void setupClass() throws Exception {
    // by default, we should wait at least 1 minute between subsequent TGT renewals.
    // changing it to 500ms.
    System.setProperty(Login.MIN_TIME_BEFORE_RELOGIN_CONFIG_KEY, "500");

    testTempDir = ClientBase.createTmpDir();
    startMiniKdcAndAddPrincipal();

    String keytabFilePath = FilenameUtils.normalize(KerberosTestUtils.getKeytabFile(), true);

    // note: we use "refreshKrb5Config=true" to refresh the kerberos config in the JVM,
    // making sure that we use the latest config even if other tests already have been executed
    // and initialized the kerberos client configs before)
    String jaasEntries = ""
      + "ClientUsingKerberos {\n"
      + "  com.sun.security.auth.module.Krb5LoginModule required\n"
      + "  storeKey=\"false\"\n"
      + "  useTicketCache=\"false\"\n"
      + "  useKeyTab=\"true\"\n"
      + "  doNotPrompt=\"true\"\n"
      + "  debug=\"true\"\n"
      + "  refreshKrb5Config=\"true\"\n"
      + "  keyTab=\"" + keytabFilePath + "\"\n"
      + "  principal=\"" + PRINCIPAL + "\";\n"
      + "};\n";
    setupJaasConfig(jaasEntries);
  }

  @AfterAll
  public static void tearDownClass() {
    System.clearProperty(Login.MIN_TIME_BEFORE_RELOGIN_CONFIG_KEY);
    System.clearProperty("java.security.auth.login.config");
    stopMiniKdc();
    if (testTempDir != null) {
      // the testTempDir contains the jaas config file and also the
      // working folder of the currently running KDC server
      FileUtils.deleteQuietly(testTempDir);
    }
  }

  @AfterEach
  public void tearDownTest() throws Exception {
    if (login != null) {
      login.shutdown();
      login.logout();
    }
  }


  /**
   * We extend the regular Login class to be able to properly control the
   * "sleeping" between the retry attempts of ticket refresh actions.
   */
  private static class TestableKerberosLogin extends Login {

    private AtomicBoolean refreshFailed = new AtomicBoolean(false);
    private CountDownLatch continueRefreshThread = new CountDownLatch(1);

    public TestableKerberosLogin() throws LoginException {
      super(JAAS_CONFIG_SECTION, (callbacks) -> {}, new ZKConfig());
    }

    @Override
    protected void sleepBeforeRetryFailedRefresh() throws InterruptedException {
      LOG.info("sleep started due to failed refresh");
      refreshFailed.set(true);
      continueRefreshThread.await(20, TimeUnit.SECONDS);
      LOG.info("sleep due to failed refresh finished");
    }

    public void assertRefreshFailsEventually(Duration timeout) {
      assertEventually(timeout, () -> refreshFailed.get());
    }

    public void continueWithRetryAfterFailedRefresh() {
      LOG.info("continue refresh thread");
      continueRefreshThread.countDown();
    }
  }


  @Test
  public void shouldLoginUsingKerberos() throws Exception {
    login = new TestableKerberosLogin();
    login.startThreadIfNeeded();

    assertPrincipalLoggedIn();
  }


  @Test
  public void shouldRenewTicketUsingKerberos() throws Exception {
    login = new TestableKerberosLogin();
    login.startThreadIfNeeded();

    long initialLoginTime = login.getLastLogin();

    // ticket lifetime is 5sec, so we will trigger ticket renewal in each ~2-3 sec
    assertTicketRefreshHappenedUntil(Duration.ofSeconds(15));

    assertPrincipalLoggedIn();
    assertTrue(initialLoginTime < login.getLastLogin());
  }


  @Test
  public void shouldRecoverIfKerberosNotAvailableForSomeTime() throws Exception {
    login = new TestableKerberosLogin();
    login.startThreadIfNeeded();

    assertTicketRefreshHappenedUntil(Duration.ofSeconds(15));

    stopMiniKdc();

    // ticket lifetime is 5sec, so we will trigger ticket renewal in each ~2-3 sec
    // the very next ticket renewal should fail (as KDC is offline)
    login.assertRefreshFailsEventually(Duration.ofSeconds(15));

    // now the ticket thread is "sleeping", it will retry the refresh later

    // we restart KDC, then terminate the "sleeping" and expecting
    // that the next retry should succeed
    startMiniKdcAndAddPrincipal();
    login.continueWithRetryAfterFailedRefresh();
    assertTicketRefreshHappenedUntil(Duration.ofSeconds(15));

    assertPrincipalLoggedIn();
  }


  private void assertPrincipalLoggedIn() {
    assertEquals(PRINCIPAL, login.getUserName());
    assertNotNull(login.getSubject());
    assertEquals(1, login.getSubject().getPrincipals().size());
    Principal actualPrincipal = login.getSubject().getPrincipals().iterator().next();
    assertEquals(PRINCIPAL, actualPrincipal.getName());
  }

  private void assertTicketRefreshHappenedUntil(Duration timeout) {
    long lastLoginTime = login.getLastLogin();
    assertEventually(timeout, () -> login.getLastLogin() != lastLoginTime
      && login.getSubject() != null && !login.getSubject().getPrincipals().isEmpty());
  }

  private static void assertEventually(Duration timeout, Supplier<Boolean> test) {
    assertTimeout(timeout, () -> {
      while (true) {
        if (test.get()) {
          return;
        }
        Thread.sleep(100);
      }
    });
  }

  public static void startMiniKdcAndAddPrincipal() throws Exception {
    kdcWorkDir = createTmpDirInside(testTempDir);

    Properties conf = MiniKdc.createConf();
    conf.setProperty(MAX_TICKET_LIFETIME, TICKET_LIFETIME);
    conf.setProperty(MIN_TICKET_LIFETIME, TICKET_LIFETIME);

    kdc = new MiniKdc(conf, kdcWorkDir);
    kdc.start();

    String principalName = PRINCIPAL.substring(0, PRINCIPAL.lastIndexOf("@"));
    kdc.createPrincipal(new File(KerberosTestUtils.getKeytabFile()), principalName);
  }

  private static void stopMiniKdc() {
    if (kdc != null) {
      kdc.stop();
      kdc = null;
    }
    if (kdcWorkDir != null) {
      FileUtils.deleteQuietly(kdcWorkDir);
      kdcWorkDir = null;
    }
  }

  private static File createTmpDirInside(File parentDir) throws IOException {
    File tmpFile = File.createTempFile("test", ".junit", parentDir);
    // don't delete tmpFile - this ensures we don't attempt to create
    // a tmpDir with a duplicate name
    File tmpDir = new File(tmpFile + ".dir");
    // never true if tmpfile does it's job
    assertFalse(tmpDir.exists());
    assertTrue(tmpDir.mkdirs());
    return tmpDir;
  }

  private static void setupJaasConfig(String jaasEntries) {
    try {
      File saslConfFile = new File(testTempDir, "jaas.conf");
      FileWriter fwriter = new FileWriter(saslConfFile);
      fwriter.write(jaasEntries);
      fwriter.close();
      System.setProperty("java.security.auth.login.config", saslConfFile.getAbsolutePath());
    } catch (IOException ioe) {
      LOG.error("Failed to initialize JAAS conf file", ioe);
    }

    // refresh the SASL configuration in this JVM (making sure that we use the latest config
    // even if other tests already have been executed and initialized the SASL configs before)
    Configuration.getConfiguration().refresh();
  }

}
