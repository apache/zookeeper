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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.qos.logback.classic.Level;
import java.io.IOException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.sasl.AuthorizeCallback;
import org.apache.zookeeper.JaasConfiguration;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SaslServerCallbackHandler} authorization handling.
 *
 * <p>These reproduce the client-port SASL authorization-ID (authzid) impersonation
 * issue and lock in the fix. The handler runs the same code path a real DIGEST-MD5
 * or GSSAPI negotiation drives via {@link AuthorizeCallback}; the SASL server
 * mechanism later returns {@code AuthorizeCallback.getAuthorizedID()} as the
 * connection identity, so asserting on it is equivalent to asserting on the
 * identity ZooKeeperServer.processSasl would adopt.
 *
 * <p>No server, KDC, or network is required.
 */
public class SaslServerCallbackHandlerTest {

    private static LoggerTestTool loggerTestTool;

    @BeforeAll
    public static void setupBeforeClass() {
        loggerTestTool = new LoggerTestTool(SaslServerCallbackHandler.class, Level.INFO);
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        loggerTestTool.close();
    }

    @BeforeEach
    public void resetLog() {
        // discard output from earlier tests so each test sees only its own log lines
        loggerTestTool.getOutputStream().reset();
    }

    private static SaslServerCallbackHandler newHandler() throws IOException {
        // On branch-3.8 the handler reads DIGEST-MD5 credentials from the JAAS
        // server section. Provide that section with no "user_" entries; the
        // authorization path under test does not depend on credentials. Resolve
        // the section name the same way the handler does, in case another test
        // in this JVM has set zookeeper.sasl.serverconfig.
        String serverSection = System.getProperty(
            ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY,
            ZooKeeperSaslServer.DEFAULT_LOGIN_CONTEXT_NAME);
        JaasConfiguration jaas = new JaasConfiguration();
        jaas.addSection(serverSection, "org.apache.zookeeper.server.auth.DigestLoginModule");
        return new SaslServerCallbackHandler(jaas);
    }

    private static AuthorizeCallback authorize(String authnId, String authzId) throws Exception {
        SaslServerCallbackHandler handler = newHandler();
        AuthorizeCallback ac = new AuthorizeCallback(authnId, authzId);
        handler.handle(new Callback[]{ac});
        return ac;
    }

    /**
     * The core exploit: a validly authenticated but low-privilege client requests
     * authzid "super". Before the fix, canonicalization of the cross-realm principal
     * threw NoMatchingRule, the exception was swallowed, authorized stayed true, and
     * getAuthorizedID() returned the client-chosen "super". The fix must deny it.
     */
    @Test
    public void authzidSuperMustBeRejectedWhenItDiffersFromAuthenticationId() throws Exception {
        AuthorizeCallback ac = authorize("bob@CROSSREALM", "super");

        assertFalse(ac.isAuthorized(),
            "client must not be authorized to assume a different identity ('super')");
        assertNull(ac.getAuthorizedID(),
            "no authorized identity may be granted when authorization is denied");
    }

    /**
     * Any authzid different from the authenticated identity is impersonation and
     * must be denied, not just the literal "super".
     */
    @Test
    public void anyMismatchedAuthzidMustBeRejected() throws Exception {
        AuthorizeCallback ac = authorize("mallory@ATTACK.REALM", "realadmin@PROD.REALM");

        assertFalse(ac.isAuthorized());
        assertNull(ac.getAuthorizedID());
    }

    /**
     * Legitimate case: the client did not request a distinct authzid, so the SASL
     * layer defaults authorizationID to authenticationID. A simple (realm-less) name
     * canonicalizes to itself and must be authorized.
     */
    @Test
    public void matchingSimpleNameIsAuthorized() throws Exception {
        AuthorizeCallback ac = authorize("alice", "alice");

        assertTrue(ac.isAuthorized(), "a client authorizing as itself must be allowed");
        assertEquals("alice", ac.getAuthorizedID());
    }

    /**
     * Regression / compatibility guard for cross-realm users. When authzid equals the
     * authenticated principal, the client is authorized as itself even though
     * canonicalization fails under the default auth_to_local rules. The critical
     * property is that the adopted identity is the client's OWN principal and can
     * never be an attacker-chosen value.
     *
     * <p>Assumes the test host's default Kerberos realm is not literally "CROSSREALM"
     * (true on any normal build/CI host), so getShortName() throws NoMatchingRule and
     * the identity falls back to the authenticated principal.
     */
    @Test
    public void matchingCrossRealmPrincipalIsAuthorizedAsItselfNotSpoofable() throws Exception {
        AuthorizeCallback ac = authorize("bob@CROSSREALM", "bob@CROSSREALM");

        assertTrue(ac.isAuthorized());
        assertEquals("bob@CROSSREALM", ac.getAuthorizedID(),
            "cross-realm client must be authorized as its own principal");
        assertNotEquals("super", ac.getAuthorizedID());
    }

    // ---------------------------------------------------------------------
    // Log sanitization (CWE-117). authenticationID and authorizationID are
    // client-controlled SASL token fields; control characters must be stripped
    // before logging so a client cannot forge adjacent log lines.
    // ---------------------------------------------------------------------

    private static final String FORGED_MARKER = "FORGED";

    /**
     * Captures everything the handler logged during this test, then asserts that
     * no physical log line begins with the injected marker, i.e. no line was forged.
     */
    private static String capturedLogAssertingNoForgedLines() {
        String output = loggerTestTool.getOutputStream().toString();
        for (String line : output.split("\\R")) {
            assertFalse(line.startsWith(FORGED_MARKER),
                "client-controlled input started a new log line (log forgery): " + line);
        }
        return output;
    }

    private static String lineContaining(String output, String needle) {
        for (String line : output.split("\\R")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return null;
    }

    @Test
    public void denialLogSanitizesControlCharactersAndLogsAtWarn() throws Exception {
        String authnId = "mallory\n" + FORGED_MARKER + " AUTHN\r\t LINE";
        String authzId = "super\n" + FORGED_MARKER + " AUTHZ\r\t LINE";

        AuthorizeCallback ac = authorize(authnId, authzId);
        assertFalse(ac.isAuthorized());

        String output = capturedLogAssertingNoForgedLines();
        String line = lineContaining(output, "Client attempted to authorize as a different identity");
        assertNotNull(line, "denial was not logged");

        // CR, LF and TAB are removed; the remaining text stays on the single log line
        assertTrue(line.contains("authenticationID=mallory" + FORGED_MARKER + " AUTHN LINE"),
            "authenticationID not logged sanitized on one line: " + line);
        assertTrue(line.contains("requested authorizationID=super" + FORGED_MARKER + " AUTHZ LINE"),
            "authorizationID not logged sanitized on one line: " + line);

        // denial must be WARN, not ERROR
        assertTrue(line.contains(" WARN "), "denial should be logged at WARN: " + line);
        assertFalse(line.contains(" ERROR "), "denial must not be logged at ERROR: " + line);
    }

    @Test
    public void successLogSanitizesControlCharacters() throws Exception {
        // authcid == authzid, so this takes the success path; the realm-less name
        // canonicalizes to itself without throwing
        String id = "alice\n" + FORGED_MARKER + " SUCCESS\r\t LINE";

        AuthorizeCallback ac = authorize(id, id);
        assertTrue(ac.isAuthorized());

        String output = capturedLogAssertingNoForgedLines();
        String line = lineContaining(output, "Successfully authenticated client");
        assertNotNull(line, "successful authorization was not logged");
        assertTrue(line.contains("authorizationID=alice" + FORGED_MARKER + " SUCCESS LINE"),
            "authorizationID not logged sanitized on one line: " + line);

        // the canonicalized identity is logged separately; for a realm-less name it
        // is the client-supplied string verbatim, so it must be sanitized as well
        String setLine = lineContaining(output, "Setting authorizedID");
        assertNotNull(setLine, "canonicalized authorizedID was not logged");
        assertTrue(setLine.contains("Setting authorizedID: alice" + FORGED_MARKER + " SUCCESS LINE"),
            "canonicalized authorizedID not logged sanitized on one line: " + setLine);

        // only the log output is sanitized; the identity itself is not altered
        assertEquals(id, ac.getAuthorizedID());
    }

    @Test
    public void unknownDigestUserLogSanitizesControlCharacters() throws Exception {
        // this runs before authentication, so any client can reach it
        String userName = "ghost\n" + FORGED_MARKER + " UNKNOWN\r\t USER";
        NameCallback nc = new NameCallback("username: ", userName);
        newHandler().handle(new Callback[]{nc});

        assertNull(nc.getName(), "unknown user must not be accepted");

        String output = capturedLogAssertingNoForgedLines();
        String line = lineContaining(output, "not found in list of DIGEST-MD5 authenticateable users");
        assertNotNull(line, "unknown user was not logged");
        assertTrue(line.contains("User 'ghost" + FORGED_MARKER + " UNKNOWN USER'"),
            "username not logged sanitized on one line: " + line);
    }

    @Test
    public void denialWithNullAuthenticationIdIsLoggedWithoutError() throws Exception {
        AuthorizeCallback ac = authorize(null, "super");

        assertFalse(ac.isAuthorized());
        assertNull(ac.getAuthorizedID());

        String line = lineContaining(loggerTestTool.getOutputStream().toString(),
            "Client attempted to authorize as a different identity");
        assertNotNull(line, "denial with null authenticationID was not logged");
        assertTrue(line.contains("authenticationID=null"), line);
    }
}
