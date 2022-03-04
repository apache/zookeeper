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
package org.apache.zookeeper.inspector.manager;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.inspector.encryption.BasicDataEncryptionManager;
import org.apache.zookeeper.retry.ZooKeeperRetry;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;

public class ZooInspectorManagerImplTest {

    /**
     * test create zookeeper node operation,
     * no easy way to create a real zk server so use a mocked client that only validate path
     *
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */
    @Test
    public void testNodeCreateRoot() throws IOException, KeeperException, InterruptedException {
        ZooKeeper mockedZk = getMockedZk();

        ZooInspectorManagerImpl manager = getInspectorManagerImpl(mockedZk);

        boolean createSuccess = manager.createNode("/", "test");
        Assert.assertTrue(createSuccess);
    }

    /**
     * test create a normal child node
     *
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */

    @Test
    public void testNodeCreateNormal() throws IOException, KeeperException, InterruptedException {
        ZooKeeper mockedZk = getMockedZk();

        ZooInspectorManagerImpl manager = getInspectorManagerImpl(mockedZk);

        boolean createSuccess = manager.createNode("/parent", "test");
        Assert.assertTrue(createSuccess);
    }


    /**
     * create a mocked zk client only check path validate
     *
     * @return
     * @throws KeeperException
     * @throws InterruptedException
     */
    private ZooKeeper getMockedZk() throws KeeperException, InterruptedException {
        ZooKeeper mockZk = Mockito.mock(ZooKeeperRetry.class);
        Mockito.when(mockZk.exists(Mockito.anyString(), Mockito.anyBoolean())).then((Answer<Stat>) invocation -> {
            String path = invocation.getArgument(0);
            PathUtils.validatePath(path);
            return null;
        });
        Mockito.when(mockZk.create(Mockito.anyString(), Mockito.any(), Mockito.any(),
                Mockito.any())).then(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                String path = invocation.getArgument(0);
                PathUtils.validatePath(path);
                return path;
            }
        });
        return mockZk;
    }

    /**
     * create a inspector manager instance from zk
     *
     * @param zooKeeper
     * @return
     * @throws IOException
     */
    private ZooInspectorManagerImpl getInspectorManagerImpl(ZooKeeper zooKeeper) throws IOException {
        ZooInspectorManagerImpl manager = new ZooInspectorManagerImpl();
        manager.zooKeeper = zooKeeper;
        manager.connected = true;
        manager.encryptionManager = new BasicDataEncryptionManager();
        return manager;
    }
}
