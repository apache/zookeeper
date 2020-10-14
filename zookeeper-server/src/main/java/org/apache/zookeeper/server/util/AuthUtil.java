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
     * Gets user from id to prepare ClientInfo.
     *
     * @param authInfo List of id objects. id contains scheme and authentication info
     * @return list of client authentication info
     */
    public static List<ClientInfo> getClientInfos(List<Id> authInfo) {
        List<ClientInfo> clientAuthInfo = new ArrayList<>(authInfo.size());
        authInfo.forEach(id -> {
            String user = AuthUtil.getUser(id);
            clientAuthInfo.add(new ClientInfo(id.getScheme(), user == null ? "" : user));
        });
        return clientAuthInfo;
    }
}
