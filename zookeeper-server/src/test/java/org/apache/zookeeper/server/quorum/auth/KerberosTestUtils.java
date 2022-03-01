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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.util.SecurityUtils;

public class KerberosTestUtils {

    private static final boolean IBM_JAVA = System.getProperty("java.vendor").contains("IBM");

    private static String keytabFile = new File(System.getProperty("build.test.dir", "build"), UUID.randomUUID().toString()).getAbsolutePath();

    public static String getRealm() {
        return "EXAMPLE.COM";
    }

    public static String getLearnerPrincipal() {
        return "learner@EXAMPLE.COM";
    }

    public static String getServerPrincipal() {
        return "zkquorum/localhost@EXAMPLE.COM";
    }

    public static String getClientPrincipal() {
        return getClientUsername() + "/localhost@EXAMPLE.COM";
    }

    public static String getClientUsername() {
        return "zkclient";
    }

    public static String getHostLearnerPrincipal() {
        return "learner/_HOST@EXAMPLE.COM";
    }

    public static String getHostServerPrincipal() {
        return "zkquorum/_HOST@EXAMPLE.COM";
    }

    public static String getHostNamedLearnerPrincipal(String myHostname) {
        return "learner/" + myHostname + "@EXAMPLE.COM";
    }

    public static String getKeytabFile() {
        return keytabFile;
    }

    public static String replaceHostPattern(String principal) {
        String[] components = principal.split("[/@]");
        if (components == null
                    || components.length < 2
                    || !components[1].equals(SecurityUtils.QUORUM_HOSTNAME_PATTERN)) {
            return principal;
        } else {
            return replacePattern(components, "localhost");
        }
    }

    public static String replacePattern(String[] components, String hostname) {
        if (components.length == 3) {
            return components[0] + "/" + hostname.toLowerCase() + "@" + components[2];
        } else {
            return components[0] + "/" + hostname.toLowerCase();
        }
    }

    public static class KerberosConfiguration extends Configuration {

        private String principal;
        private String keytab;
        private boolean isInitiator;

        private KerberosConfiguration(String principal, File keytab, boolean client) {
            this.principal = principal;
            this.keytab = keytab.getAbsolutePath();
            this.isInitiator = client;
        }

        public static Configuration createClientConfig(String principal, File keytab) {
            return new KerberosConfiguration(principal, keytab, true);
        }

        public static Configuration createServerConfig(String principal, File keytab) {
            return new KerberosConfiguration(principal, keytab, false);
        }

        private static String getKrb5LoginModuleName() {
            return System.getProperty("java.vendor").contains("IBM")
              ? "com.ibm.security.auth.module.Krb5LoginModule"
              : "com.sun.security.auth.module.Krb5LoginModule";
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
            Map<String, String> options = new HashMap<String, String>();
            options.put("principal", principal);
            options.put("refreshKrb5Config", "true");
            if (IBM_JAVA) {
                options.put("useKeytab", keytab);
                options.put("credsType", "both");
            } else {
                options.put("keyTab", keytab);
                options.put("useKeyTab", "true");
                options.put("storeKey", "true");
                options.put("doNotPrompt", "true");
                options.put("useTicketCache", "true");
                options.put("renewTGT", "true");
                options.put("isInitiator", Boolean.toString(isInitiator));
            }
            String ticketCache = System.getenv("KRB5CCNAME");
            if (ticketCache != null) {
                options.put("ticketCache", ticketCache);
            }
            options.put("debug", "true");

            return new AppConfigurationEntry[]{new AppConfigurationEntry(getKrb5LoginModuleName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options)};
        }

    }


}
