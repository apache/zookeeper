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

package org.apache.zookeeper.server.quorum.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import java.io.File;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.LoginContext;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.zookeeper.server.quorum.auth.KerberosTestUtils.KerberosConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/*
 * This code is originally from HDFS, see the file name TestMiniKdc there
 * in case of bug fixing, history, etc.
 *
 * Branch : trunk
 * Github Revision: 916140604ffef59466ba30832478311d3e6249bd
 */
public class MiniKdcTest extends KerberosSecurityTestcase {


    @Test
    @Timeout(value = 60)
    public void testMiniKdcStart() {
        MiniKdc kdc = getKdc();
        assertNotSame(0, kdc.getPort());
    }

    @Test
    @Timeout(value = 60)
    public void testKeytabGen() throws Exception {
        MiniKdc kdc = getKdc();
        File workDir = getWorkDir();

        kdc.createPrincipal(new File(workDir, "keytab"), "foo/bar", "bar/foo");
        List<PrincipalName> principalNameList = Keytab.loadKeytab(new File(workDir, "keytab")).getPrincipals();

        Set<String> principals = new HashSet<String>();
        for (PrincipalName principalName : principalNameList) {
            principals.add(principalName.getName());
        }

        assertEquals(new HashSet<>(Arrays.asList("foo/bar@" + kdc.getRealm(), "bar/foo@" + kdc.getRealm())),
            principals);
    }


    @Test
    @Timeout(value = 60)
    public void testKerberosLogin() throws Exception {
        MiniKdc kdc = getKdc();
        File workDir = getWorkDir();
        LoginContext loginContext = null;
        try {
            String principal = "foo";
            File keytab = new File(workDir, "foo.keytab");
            kdc.createPrincipal(keytab, principal);

            Set<Principal> principals = new HashSet<Principal>();
            principals.add(new KerberosPrincipal(principal));

            // client login
            Subject subject = new Subject(false, principals, new HashSet<Object>(), new HashSet<Object>());
            loginContext = new LoginContext("", subject, null, KerberosConfiguration.createClientConfig(principal, keytab));
            loginContext.login();
            subject = loginContext.getSubject();
            assertEquals(1, subject.getPrincipals().size());
            assertEquals(KerberosPrincipal.class, subject.getPrincipals().iterator().next().getClass());
            assertEquals(principal + "@" + kdc.getRealm(), subject.getPrincipals().iterator().next().getName());
            loginContext.logout();

            // server login
            subject = new Subject(false, principals, new HashSet<Object>(), new HashSet<Object>());
            loginContext = new LoginContext("", subject, null, KerberosConfiguration.createServerConfig(principal, keytab));
            loginContext.login();
            subject = loginContext.getSubject();
            assertEquals(1, subject.getPrincipals().size());
            assertEquals(KerberosPrincipal.class, subject.getPrincipals().iterator().next().getClass());
            assertEquals(principal + "@" + kdc.getRealm(), subject.getPrincipals().iterator().next().getName());
            loginContext.logout();

        } finally {
            if (loginContext != null
                && loginContext.getSubject() != null
                && !loginContext.getSubject().getPrincipals().isEmpty()) {
                loginContext.logout();
            }
        }
    }

}
