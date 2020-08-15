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

package org.apache.zookeeper.audit;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;



public class StandaloneServerAuditTest extends ClientBase {
    private static ByteArrayOutputStream os;

    @BeforeAll
    public static void setup() {
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "true");
        // setup the logger to capture all the logs
        Layout layout = new SimpleLayout();
        os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zLogger = Logger.getLogger(Log4jAuditLogger.class);
        zLogger.addAppender(appender);
    }

    @AfterAll
    public static void teardown() {
        System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
    }

    @Test
    public void testCreateAuditLog() throws KeeperException, InterruptedException, IOException {
        final ZooKeeper zk = createClient();
        String path = "/createPath";
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        List<String> logs = readAuditLog(os);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).endsWith("operation=create\tznode=/createPath\tznode_type=persistent\tresult=success"));
    }

    private static List<String> readAuditLog(ByteArrayOutputStream os) throws IOException {
        List<String> logs = new ArrayList<>();
        LineNumberReader r = new LineNumberReader(
                new StringReader(os.toString()));
        String line;
        while ((line = r.readLine()) != null) {
            logs.add(line);
        }
        os.reset();
        return logs;
    }
}

