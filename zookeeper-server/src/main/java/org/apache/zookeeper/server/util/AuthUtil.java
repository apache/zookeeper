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
package org.apache.zookeeper.server.util;

import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.data.ClientInfo;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;

public final class AuthUtil {
    private AuthUtil() {
        //Utility classes should not have public constructors
    }
    /**
     * Gives user name
     *
     * @param id contains scheme and authentication info
     * @return returns null if authentication scheme does not exist or
     * authentication provider returns null as user
     */
    public static String getUser(Id id) {
        AuthenticationProvider provider = ProviderRegistry.getProvider(id.getScheme());
        return provider == null ? null : provider.getUserName(id.getId());
    }

    /**
     * Returns an encoded, comma-separated list of the {@code id}s
     * held in {@code authInfo}.
     *
     * Note that while the result may be easy on the eyes, it is
     * underspecified as it does not mention the corresponding
     * {@code scheme}.
     *
     * @param authInfo A list of {@code Id} objects, or {@code null}.
     * @return a formatted list of {@code id}s, or {@code null} if no
     * usable {@code id}s were found.
     */
    public static String getUsers(List<Id> authInfo) {
        if (authInfo == null) {
            return (String) null;
        }
        if (authInfo.size() == 1) {
            return getUser(authInfo.get(0));
        }
        StringBuilder users = new StringBuilder();
        boolean first = true;
        for (Id id : authInfo) {
            String user = getUser(id);
            if (user != null) {
                if (first) {
                    first = false;
                } else {
                    users.append(",");
                }
                users.append(user);
            }
        }
        return users.toString();
    }

    /**
     * Gets user from id to prepare ClientInfo.
     *
     * @param authInfo List of id objects. id contains scheme and authentication info
     * @return list of client authentication info
     */
    public static List<ClientInfo> getClientInfos(List<Id> authInfo) {
        List<ClientInfo> clientAuthInfo = new ArrayList<>(authInfo.size());
        authInfo.forEach(id -> {
            String user = getUser(id);
            clientAuthInfo.add(new ClientInfo(id.getScheme(), user == null ? "" : user));
        });
        return clientAuthInfo;
    }
}
