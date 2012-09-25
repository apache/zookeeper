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

/**
 * 
 */
package org.apache.zookeeper.server.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;
import org.junit.Assert;

import org.junit.Test;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.test.ClientBase;


/**
 * test for backward compatibility of dynamic configuration parameters representation.
 * Currently this only includes membership parameters.
 * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1411}
 */
public class DynamicConfigBCTest extends QuorumPeer {
    final int CLIENT_PORT_QP1 = PortAssignment.unique();
    final int CLIENT_PORT_QP2 = PortAssignment.unique();
    final int CLIENT_PORT_QP3 = PortAssignment.unique();
    
    String configStr1 =
        "server.1=127.0.0.1:" + PortAssignment.unique()
        + ":" + PortAssignment.unique()
        + "\nserver.2=127.0.0.1:" + PortAssignment.unique() 
        + ":" + PortAssignment.unique();

    
    String configStr2 =
        "server.1=127.0.0.1:" + PortAssignment.unique()
        + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
        + "\nserver.2=127.0.0.1:" + PortAssignment.unique() 
        + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP2
        + "\nserver.3=127.0.0.1:" + PortAssignment.unique() 
        + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP3
        + "\nversion=2";

    
        @Test
        public void dynamicConfigBackwardCompatibilityTest() throws IOException, ConfigException
        {           
            //set up an plain old single config file 
            File tmpDir = ClientBase.createTmpDir();            
            File confFile = new File(tmpDir, "zoo.cfg");            
            
            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=4000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

            File dataDir = new File(tmpDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("Unable to mkdir " + dataDir);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = dataDir.toString();
            String osname = java.lang.System.getProperty("os.name");
            if (osname.toLowerCase().contains("windows")) {
                dir = dir.replace('\\', '/');
            }
            fwriter.write("dataDir=" + dir + "\n");
            
            fwriter.write("clientPort=" + CLIENT_PORT_QP1 + "\n");
            
            fwriter.write(configStr1 + "\n");
            fwriter.flush();
            fwriter.close();
            
            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            int myId = 1;
            fwriter.write(Integer.toString(myId));
            fwriter.flush();
            fwriter.close();
            
            //set QuorumPeer's  membership params the same way QuorumPeerMain does            
            QuorumPeerConfig config = new QuorumPeerConfig();
            config.parse(confFile.toString());
            
            Assert.assertTrue(config.getClientPortAddress() != null 
                && config.getClientPortAddress().getPort() == CLIENT_PORT_QP1);

            setDynamicConfigFilename(config.getDynamicConfigFilename());
            setConfigFileName(config.getConfigFilename());
            setConfigBackwardCompatibility(config.getConfigBackwardCompatibility());
            setZKDatabase(new ZKDatabase(getTxnFactory()));
            setQuorumVerifier(config.getQuorumVerifier(), false);
            
            // check that a dynamic configuration file wasn't created and that the static config file contains
            // the membership definitions
            Assert.assertFalse((new File(config.getConfigFilename() + ".dynamic")).exists());
            Properties cfg = new Properties();
            FileInputStream in = new FileInputStream(config.getConfigFilename());
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
            Assert.assertTrue(cfg.containsKey("server.1"));
            Assert.assertTrue(cfg.containsKey("server.2"));
            Assert.assertFalse(cfg.containsKey("server.3"));
            Assert.assertFalse(cfg.containsKey("dynamicConfigFile"));
            
            // check that backward compatibility bit is true
            Assert.assertTrue(getConfigBackwardCompatibility());

            QuorumVerifier qvNew = configFromString(configStr2);
            setQuorumVerifier(qvNew, true);
            
            // check that backward compatibility bit is now false
            Assert.assertFalse(getConfigBackwardCompatibility());

            // check that a dynamic configuration file was created
            Assert.assertTrue((new File(config.getConfigFilename() + ".dynamic")).exists());

            // check that config file doesn't include membership info
            // and has a pointer to the dynamic configuration file
            cfg = new Properties();
            in = new FileInputStream(config.getConfigFilename());
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
            Assert.assertFalse(cfg.containsKey("server.1"));
            Assert.assertFalse(cfg.containsKey("server.2"));
            Assert.assertFalse(cfg.containsKey("server.3"));
            Assert.assertTrue(cfg.containsKey("dynamicConfigFile"));

            // check that the dynamic configuration file contains the membership info            
            cfg = new Properties();
            in = new FileInputStream(getDynamicConfigFilename());
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
            Assert.assertTrue(cfg.containsKey("server.1"));
            Assert.assertTrue(cfg.containsKey("server.2"));
            Assert.assertTrue(cfg.containsKey("server.3"));
            Assert.assertFalse(cfg.containsKey("dynamicConfigFile"));
        }

}
