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

import java.util.HashMap;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;

/**
 * This helper class allows to programmatically create a JAAS configuration.
 * Each section must have a name and a login module, and a set of key/values
 * to describe login options.
 *
 * Example:
 *   jaas = new JaasConfiguration();
 *   jaas.addSection("Server", "org.apache.zookeeper.server.auth.DigestLoginModule",
 *                   "username", "passowrd");
 */
public class JaasConfiguration extends javax.security.auth.login.Configuration {

    private final Map<String, AppConfigurationEntry[]> sections = new HashMap<String, AppConfigurationEntry[]>();

    public JaasConfiguration() {
    }

    /**
     * Add a section to the jaas.conf
     * @param name Section name
     * @param loginModuleName Login module name
     * @param args login key/value args
     */
    public void addSection(String name, String loginModuleName, String... args) {
        Map<String, String> conf = new HashMap<String, String>();
        // loop through the args (must be key/value sequence)
        for (int i = 0; i < args.length - 1; i += 2) {
            conf.put(args[i], args[i + 1]);
        }
        addSection(name, loginModuleName, conf);
    }

    /**
     * Add a section to the jaas.conf
     * @param name Section name
     * @param loginModuleName Login module name
     * @param conf login key/value args
     */
    public void addSection(String name, String loginModuleName, final Map<String, String> conf) {
        AppConfigurationEntry[] entries = new AppConfigurationEntry[1];
        entries[0] = new AppConfigurationEntry(loginModuleName, LoginModuleControlFlag.REQUIRED, conf);
        this.sections.put(name, entries);
    }

    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String appName) {
        return sections.get(appName);
    }

}
