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

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.spi.LoginModule;

public class DigestLoginModule implements LoginModule {

    private Subject subject;

    public boolean abort() {
        return false;
    }

    public boolean commit() {
        return true;
    }

    public void initialize(
        Subject subject,
        CallbackHandler callbackHandler,
        Map<String, ?> sharedState,
        Map<String, ?> options) {
        if (options.containsKey("username")) {
            // Zookeeper client: get username and password from JAAS conf (only used if using DIGEST-MD5).
            this.subject = subject;
            String username = (String) options.get("username");
            this.subject.getPublicCredentials().add(username);
            String password = (String) options.get("password");
            this.subject.getPrivateCredentials().add(password);
        }
        return;
    }

    public boolean logout() {
        return true;
    }

    public boolean login() {
        // Unlike with Krb5LoginModule, we don't do any actual login or credential passing here: authentication to Zookeeper
        // is done later, through the SASLClient object.
        return true;
    }

}


