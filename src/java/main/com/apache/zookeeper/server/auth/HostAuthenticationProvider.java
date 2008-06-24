/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.auth;

import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.server.ServerCnxn;
import com.yahoo.zookeeper.KeeperException;

public class HostAuthenticationProvider implements AuthenticationProvider {

    public String getScheme() {
        return "host";
    }

    public int handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        String id = cnxn.getRemoteAddress().getAddress().getCanonicalHostName();
        cnxn.getAuthInfo().add(new Id(getScheme(), id));
        return KeeperException.Code.Ok;
    }

    public boolean matches(String id, String aclExpr) {
        // We just do suffix matching
        String idParts[] = id.split("\\.");
        String expParts[] = aclExpr.split("\\.");
        int diff = idParts.length - expParts.length;
        if (diff < 0) {
            return false;
        }
        for (int i = 0; i < expParts.length; i++) {
            if (idParts[i + diff].equals(expParts[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean isAuthenticated() {
        return false;
    }

    public boolean isValid(String id) {
        String parts[] = id.split("\\.");
        for (String part : parts) {
            if (part.length() == 0) {
                return false;
            }
        }
        return true;
    }

}
