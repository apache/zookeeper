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

import static java.nio.charset.StandardCharsets.UTF_8;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DigestAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DigestAuthenticationProvider.class);

    private static final String DEFAULT_DIGEST_ALGORITHM = "SHA1";

    public static final String DIGEST_ALGORITHM_KEY = "zookeeper.DigestAuthenticationProvider.digestAlg";

    private static final String DIGEST_ALGORITHM = System.getProperty(DIGEST_ALGORITHM_KEY, DEFAULT_DIGEST_ALGORITHM);

    static {
        try {
            //sanity check, pre-check the availability of the algorithm to avoid some unexpected exceptions in the runtime
            generateDigest(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("don't support this ACL digest algorithm: " + DIGEST_ALGORITHM + " in the current environment");
        }
        LOG.info("ACL digest algorithm is: {}", DIGEST_ALGORITHM);
    }

    private static final String DIGEST_AUTH_ENABLED = "zookeeper.DigestAuthenticationProvider.enabled";

    /** specify a command line property with key of
     * "zookeeper.DigestAuthenticationProvider.superDigest"
     * and value of "super:&lt;base64encoded(SHA1(password))&gt;" to enable
     * super user access (i.e. acls disabled)
     */
    private static final String superDigest = System.getProperty("zookeeper.DigestAuthenticationProvider.superDigest");

    public static boolean isEnabled() {
        boolean enabled = Boolean.parseBoolean(System.getProperty(DIGEST_AUTH_ENABLED, "true"));
        LOG.info("{} = {}", DIGEST_AUTH_ENABLED, enabled);
        return enabled;
    }

    public String getScheme() {
        return "digest";
    }

    private static String base64Encode(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; ) {
            int pad = 0;
            int v = (b[i++] & 0xff) << 16;
            if (i < b.length) {
                v |= (b[i++] & 0xff) << 8;
            } else {
                pad++;
            }
            if (i < b.length) {
                v |= (b[i++] & 0xff);
            } else {
                pad++;
            }
            sb.append(encode(v >> 18));
            sb.append(encode(v >> 12));
            if (pad < 2) {
                sb.append(encode(v >> 6));
            } else {
                sb.append('=');
            }
            if (pad < 1) {
                sb.append(encode(v));
            } else {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    private static char encode(int i) {
        i &= 0x3f;
        if (i < 26) {
            return (char) ('A' + i);
        }
        if (i < 52) {
            return (char) ('a' + i - 26);
        }
        if (i < 62) {
            return (char) ('0' + i - 52);
        }
        return i == 62 ? '+' : '/';
    }

    public static String generateDigest(String idPassword) throws NoSuchAlgorithmException {
        String[] parts = idPassword.split(":", 2);
        byte[] digest = digest(idPassword);
        return parts[0] + ":" + base64Encode(digest);
    }

    // @VisibleForTesting
    public static byte[] digest(String idPassword) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(idPassword.getBytes(UTF_8));
    }

    public KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        String id = new String(authData);
        try {
            String digest = generateDigest(id);
            if (digest.equals(superDigest)) {
                cnxn.addAuthInfo(new Id("super", ""));
            }
            cnxn.addAuthInfo(new Id(getScheme(), digest));
            return KeeperException.Code.OK;
        } catch (NoSuchAlgorithmException e) {
            LOG.error("Missing algorithm", e);
        }
        return KeeperException.Code.AUTHFAILED;
    }

    public boolean isAuthenticated() {
        return true;
    }

    public boolean isValid(String id) {
        String[] parts = id.split(":");
        return parts.length == 2;
    }

    public boolean matches(String id, String aclExpr) {
        return id.equals(aclExpr);
    }

    @Override
    public String getUserName(String id) {
        /**
         * format is already enforced in server code. so no need to check it
         * again, just assume it is in correct format
         */
        return id.split(":")[0];
    }

    /** Call with a single argument of user:pass to generate authdata.
     * Authdata output can be used when setting superDigest for example.
     * @param args single argument of user:pass
     * @throws NoSuchAlgorithmException
     */
    public static void main(String[] args) throws NoSuchAlgorithmException {
        for (int i = 0; i < args.length; i++) {
            System.out.println(args[i] + "->" + generateDigest(args[i]));
        }
    }

}
