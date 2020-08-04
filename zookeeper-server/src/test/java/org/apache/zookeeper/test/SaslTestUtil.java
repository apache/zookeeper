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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class SaslTestUtil extends ClientBase {

    // The maximum time (in milliseconds) a client should take to observe
    // a disconnect event of the same client from server.
    static Integer CLIENT_DISCONNECT_TIMEOUT = 3000;
    static String requireSASLAuthProperty = "zookeeper.sessionRequireClientSASLAuth";
    static String authProviderProperty = "zookeeper.authProvider.1";
    static String authProvider = "org.apache.zookeeper.server.auth.SASLAuthenticationProvider";
    static String digestLoginModule = "org.apache.zookeeper.server.auth.DigestLoginModule";
    static String jaasConfig = "java.security.auth.login.config";

    static String createJAASConfigFile(String fileName, String password) {
        String ret = null;
        try {
            File tmpDir = createTmpDir();
            File jaasFile = new File(tmpDir, fileName);
            FileWriter fwriter = new FileWriter(jaasFile);
            fwriter.write(""
                    + "Server {\n"
                    + "          " + digestLoginModule + " required\n"
                    + "          user_super=\"test\";\n"
                    + "};\n"
                    + "Client {\n"
                    + "       " + digestLoginModule + " required\n"
                    + "       username=\"super\"\n"
                    + "       password=\"" + password + "\";\n"
                    + "};" + "\n");
            fwriter.close();
            ret = jaasFile.getAbsolutePath();
        } catch (IOException e) {
            fail("Unable to create JaaS configuration file!");
        }

        return ret;
    }

}
