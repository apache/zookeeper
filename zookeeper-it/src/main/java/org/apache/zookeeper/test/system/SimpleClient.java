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

package org.apache.zookeeper.test.system;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

/**
 * The client that gets spawned for the SimpleSysTest
 *
 */
public class SimpleClient implements Instance, Watcher, AsyncCallback.DataCallback, StringCallback, StatCallback {
    private static final long serialVersionUID = 1L;
    String hostPort;
    ZooKeeper zk;
    transient int index;
    transient String myPath;
    byte[] data;
    boolean createdEphemeral;
    public void configure(String params) {
        String parts[] = params.split(" ");
        hostPort = parts[1];
        this.index = Integer.parseInt(parts[0]);
        myPath = "/simpleCase/" + index;
    }

    public void start() {
        try {
            zk = new ZooKeeper(hostPort, 15000, this);
            zk.getData("/simpleCase", true, this, null);
            if (null != r) {
                r.report("Client " + index + " connecting to " + hostPort);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        try {
            if (zk != null) {
                zk.close();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    public void process(WatchedEvent event) {
        if (event.getPath() != null && event.getPath().equals("/simpleCase")) {
            zk.getData("/simpleCase", true, this, null);
        }
    }

    public void processResult(int rc, String path, Object ctx, byte[] data,
            Stat stat) {
        if (rc != 0) {
            zk.getData("/simpleCase", true, this, null);
        } else {
            this.data = data;
            String content = new String(data);
            if (content.equals("die")) {
                this.stop();
                return;
            }
            if (!createdEphemeral) {
                zk.create(myPath, data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL, this, null);
                createdEphemeral = true;
            } else {
                zk.setData(myPath, data, -1, this, null);
            }
        }
    }

    public void processResult(int rc, String path, Object ctx, String name) {
        if (rc != 0) {
            zk.create(myPath, data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL, this, null);
        }
    }
    public void processResult(int rc, String path, Object ctx, Stat stat) {
        if (rc != 0) {
            zk.setData(myPath, data, -1, this, null);
        }
    }
    @Override
    public String toString() {
        return SimpleClient.class.getName() + "[" + index + "] using " + hostPort;
    }

    Reporter r;
    public void setReporter(Reporter r) {
        this.r = r;
    }
}
