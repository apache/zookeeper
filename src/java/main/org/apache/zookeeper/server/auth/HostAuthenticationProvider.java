/**
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

import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.KeeperException;

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
