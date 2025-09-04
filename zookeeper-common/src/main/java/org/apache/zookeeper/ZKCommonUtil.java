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

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.data.ACL;

public class ZKCommonUtil {
    private static final Map<Integer, String> permCache = new ConcurrentHashMap<>();

    /**
     * @param filePath the file path to be validated
     * @return Returns null if valid otherwise error message
     */
    public static String validateFileInput(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "File '" + file.getAbsolutePath() + "' does not exist.";
        }
        if (!file.canRead()) {
            return "Read permission is denied on the file '" + file.getAbsolutePath() + "'";
        }
        if (file.isDirectory()) {
            return "'" + file.getAbsolutePath() + "' is a directory. it must be a file.";
        }
        return null;
    }

    /**
     * @param perms
     *            ACL permissions
     * @return string representation of permissions
     */
    public static String getPermString(int perms) {
        return permCache.computeIfAbsent(perms, ZKCommonUtil::constructPermString);
    }

    private static String constructPermString(int perms) {
        StringBuilder p = new StringBuilder();
        if ((perms & ZooDefs.Perms.CREATE) != 0) {
            p.append('c');
        }
        if ((perms & ZooDefs.Perms.DELETE) != 0) {
            p.append('d');
        }
        if ((perms & ZooDefs.Perms.READ) != 0) {
            p.append('r');
        }
        if ((perms & ZooDefs.Perms.WRITE) != 0) {
            p.append('w');
        }
        if ((perms & ZooDefs.Perms.ADMIN) != 0) {
            p.append('a');
        }
        return p.toString();
    }

    public static String aclToString(List<ACL> acls) {
        StringBuilder sb = new StringBuilder();
        for (ACL acl : acls) {
            sb.append(acl.getId().getScheme());
            sb.append(":");
            sb.append(acl.getId().getId());
            sb.append(":");
            sb.append(getPermString(acl.getPerms()));
        }
        return sb.toString();
    }
}
