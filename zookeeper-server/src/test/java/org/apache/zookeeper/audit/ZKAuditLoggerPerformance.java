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

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKAuditLoggerPerformance {
    private static final Logger LOG = LoggerFactory
            .getLogger(ZKAuditLoggerPerformance.class);
    private ZooKeeper zkClient;
    private String parentPath;
    private int numberOfRecords;

    public ZKAuditLoggerPerformance(ZooKeeper zkClient, String parentPath,
                                    int numberOfRecords) {
        this.zkClient = zkClient;
        this.parentPath = parentPath;
        this.numberOfRecords = numberOfRecords;
    }

    public void create() throws Exception {
        for (int i = 0; i < numberOfRecords; i++) {
            zkClient.create(getPath(i), "0123456789".getBytes(),
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);

        }
    }

    public void setData() throws Exception {
        for (int i = 0; i < numberOfRecords; i++) {
            zkClient.setData(getPath(i), "9876543210".getBytes(), -1);
        }
    }

    public void delete() throws Exception {
        for (int i = 0; i < numberOfRecords; i++) {
            zkClient.delete(getPath(i), -1);
        }
    }

    public AuditLogPerfReading doOperations() throws Exception {
        AuditLogPerfReading perfReading = new AuditLogPerfReading();
        // create
        long startTime = Time.currentElapsedTime();
        create();
        perfReading.setCreate(Time.currentElapsedTime() - startTime);

        // setData
        startTime = Time.currentElapsedTime();
        setData();
        perfReading.setSetData(Time.currentElapsedTime() - startTime);

        // delete
        startTime = Time.currentElapsedTime();
        delete();
        perfReading.setDelete(Time.currentElapsedTime() - startTime);
        return perfReading;
    }

    private String getPath(int i) {
        return parentPath + "zNode" + i;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "USAGE: ZKAuditLoggerPerformance connectionString parentPath numberOfRecords");
            System.exit(1);
        }
        String cxnString = args[0];
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zkClient = null;
        try {
            zkClient = new ZooKeeper(cxnString, 60000, watcher);
            watcher.waitForConnected(30000);
        } catch (InterruptedException | TimeoutException | IOException e) {
            String msg = "ZooKeeper client can not connect to " + cxnString;
            logErrorAndExit(e, msg);
        }
        String parentPath = args[1];
        try {
            Stat exists = zkClient.exists(parentPath, false);
            if (exists == null) {
                System.err.println(
                        "Parent path '" + parentPath + "' must exist.");
                System.exit(1);
            }
        } catch (KeeperException | InterruptedException e1) {
            String msg = "Error while checking the existence of parent path";
            logErrorAndExit(e1, msg);
        }
        int recordCount = 0;
        try {
            recordCount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            String msg = "Failed to parse '" + args[2] + "' to integer";
            LOG.error(msg, e);
            System.err.println(msg);
            System.exit(1);
        }
        ZKAuditLoggerPerformance auditLoggingPerf = new ZKAuditLoggerPerformance(
                zkClient,
                parentPath, recordCount);
        AuditLogPerfReading doOperations = null;
        try {
            doOperations = auditLoggingPerf.doOperations();
        } catch (Exception e) {
            String msg = "Error while doing operations.";
            LOG.error(msg, e);
            System.err.println(msg);
            System.exit(1);
        }
        System.out
                .println("Time taken for " + recordCount + " operations are:");
        System.out.println(doOperations.report());
        System.exit(0);
    }

    private static void logErrorAndExit(Exception e, String msg) {
        LOG.error(msg, e);
        System.err.println(msg + ", error=" + e.getMessage());
        System.exit(1);
    }
}
