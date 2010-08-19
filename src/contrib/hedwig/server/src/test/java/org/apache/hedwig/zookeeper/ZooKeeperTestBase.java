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
package org.apache.hedwig.zookeeper;

import java.util.concurrent.SynchronousQueue;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;

import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.Callback;

/**
 * This is a base class for any tests that need a ZooKeeper client/server setup.
 * 
 */
public abstract class ZooKeeperTestBase extends ClientBase {

    protected ZooKeeper zk;

    protected SynchronousQueue<Boolean> queue = new SynchronousQueue<Boolean>();

    protected Callback<Void> cb = new Callback<Void>() {

        @Override
        public void operationFinished(Object ctx, Void result) {
            new Thread(new Runnable() {
                public void run() {
                    ConcurrencyUtils.put(queue, true);
                }
            }).start();
        }

        @Override
        public void operationFailed(Object ctx, PubSubException exception) {
            new Thread(new Runnable() {
                public void run() {
                    ConcurrencyUtils.put(queue, false);
                }
            }).start();
        }
    };

    protected AsyncCallback.StringCallback strCb = new AsyncCallback.StringCallback() {
        @Override
        public void processResult(int rc, String path, Object ctx, String name) {
            ConcurrencyUtils.put(queue, rc == Code.OK.intValue());
        }
    };

    protected AsyncCallback.VoidCallback voidCb = new AsyncCallback.VoidCallback() {
        @Override
        public void processResult(int rc, String path, Object ctx) {
            ConcurrencyUtils.put(queue, rc == Code.OK.intValue());
        }
    };

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

}
