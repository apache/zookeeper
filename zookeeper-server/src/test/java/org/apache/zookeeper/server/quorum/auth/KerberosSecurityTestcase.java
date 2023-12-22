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
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/*
 * This code is originally from HDFS, see the similarly named file there
 * in case of bug fixing, history, etc.
 *
 * Branch : trunk
 * Github Revision: 1d1ab587e4e92ce3aea4cb144811f69145cb3b33
 */

/**
 * KerberosSecurityTestcase provides a base class for using MiniKdc with other
 * test cases. KerberosSecurityTestcase starts the MiniKdc (@Before) before
 * running tests, and stop the MiniKdc (@After) after the testcases, using
 * default settings (working dir and kdc configurations).
 * <p>
 * Users can directly inherit this class and implement their own test functions
 * using the default settings, or override functions getTestDir() and
 * createMiniKdcConf() to provide new settings.
 */
public class KerberosSecurityTestcase extends QuorumAuthTestBase {

    private static MiniKdc kdc;
    @TempDir
    static File workDir;
    private static Properties conf;

    @BeforeAll
    public static void setUpSasl() throws Exception {
        startMiniKdc();
    }

    @AfterAll
    public static void tearDownSasl() throws Exception {
        stopMiniKdc();
    }

    public static void startMiniKdc() throws Exception {
        createMiniKdcConf();

        kdc = new MiniKdc(conf, workDir);
        kdc.start();
    }

    /**
     * Create a Kdc configuration
     */
    public static void createMiniKdcConf() {
        conf = MiniKdc.createConf();
    }

    public static void stopMiniKdc() {
        if (kdc != null) {
            kdc.stop();
        }
    }

    public static MiniKdc getKdc() {
        return kdc;
    }

    public static File getWorkDir() {
        return workDir;
    }

    public static Properties getConf() {
        return conf;
    }

}
