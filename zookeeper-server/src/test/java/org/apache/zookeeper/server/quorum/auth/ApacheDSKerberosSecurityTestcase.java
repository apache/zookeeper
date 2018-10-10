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
package org.apache.zookeeper.server.quorum.auth;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

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
public class ApacheDSKerberosSecurityTestcase extends QuorumAuthTestBase {
    private static ApacheDSMiniKdc kdc;
    private static File workDir;
    private static Properties conf;

    @BeforeClass
    public static void setUpSasl() throws Exception {
        startMiniKdc();
    }

    @AfterClass
    public static void tearDownSasl() throws Exception {
        stopMiniKdc();
        FileUtils.deleteQuietly(workDir);
    }

    public static void startMiniKdc() throws Exception {
        createTestDir();
        createMiniKdcConf();

        kdc = new ApacheDSMiniKdc(conf, workDir);
        kdc.start();
    }

    /**
     * Create a working directory, it should be the build directory. Under this
     * directory an ApacheDS working directory will be created, this directory
     * will be deleted when the MiniKdc stops.
     *
     * @throws IOException
     */
    public static void createTestDir() throws IOException {
        workDir = createTmpDir(
                new File(System.getProperty("build.test.dir", "build")));
    }

    static File createTmpDir(File parentDir) throws IOException {
        File tmpFile = File.createTempFile("test", ".junit", parentDir);
        // don't delete tmpFile - this ensures we don't attempt to create
        // a tmpDir with a duplicate name
        File tmpDir = new File(tmpFile + ".dir");
        // never true if tmpfile does it's job
        Assert.assertFalse(tmpDir.exists());
        Assert.assertTrue(tmpDir.mkdirs());
        return tmpDir;
    }

    /**
     * Create a Kdc configuration
     */
    public static void createMiniKdcConf() {
        conf = ApacheDSMiniKdc.createConf();
    }

    public static void stopMiniKdc() {
        if (kdc != null) {
            kdc.stop();
        }
    }

    public static ApacheDSMiniKdc getKdc() {
        return kdc;
    }

    public static File getWorkDir() {
        return workDir;
    }

    public static Properties getConf() {
        return conf;
    }
}
