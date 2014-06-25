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

package org.apache.zookeeper.test;

import java.lang.Exception;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.AsyncOps.ACLCB;
import org.apache.zookeeper.test.AsyncOps.Children2CB;
import org.apache.zookeeper.test.AsyncOps.ChildrenCB;
import org.apache.zookeeper.test.AsyncOps.Create2CB;
import org.apache.zookeeper.test.AsyncOps.DataCB;
import org.apache.zookeeper.test.AsyncOps.StatCB;
import org.apache.zookeeper.test.AsyncOps.StringCB;
import org.apache.zookeeper.test.AsyncOps.VoidCB;
import org.apache.zookeeper.test.AsyncOps.MultiCB;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AsyncOpsTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncOpsTest.class);

    private ZooKeeper zk;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        LOG.info("Creating client " + getTestName());

        zk = createClient();
        zk.addAuthInfo("digest", "ben:passwd".getBytes());
    }

    @After
    @Override
    public void tearDown() throws Exception {
        zk.close();

        super.tearDown();

        LOG.info("Test clients shutting down");
    }

    @Test
    public void testAsyncCreate() {
        new StringCB(zk).verifyCreate();
    }

    @Test
    public void testAsyncCreate2() {
        new Create2CB(zk).verifyCreate();
    }

    @Test
    public void testAsyncCreateThree() {
        CountDownLatch latch = new CountDownLatch(3);

        StringCB op1 = new StringCB(zk, latch);
        op1.setPath("/op1");
        StringCB op2 = new StringCB(zk, latch);
        op2.setPath("/op2");
        StringCB op3 = new StringCB(zk, latch);
        op3.setPath("/op3");

        op1.create();
        op2.create();
        op3.create();

        op1.verify();
        op2.verify();
        op3.verify();
    }

    @Test
    public void testAsyncCreateFailure_NodeExists() {
        new StringCB(zk).verifyCreateFailure_NodeExists();
    }

    @Test
    public void testAsyncCreateFailure_NoNode() {
        new StringCB(zk).verifyCreateFailure_NoNode();
    }

    @Test
    public void testAsyncCreateFailure_NoChildForEphemeral() {
        new StringCB(zk).verifyCreateFailure_NoChildForEphemeral();
    }

    @Test
    public void testAsyncCreate2Failure_NodeExists() {
        new Create2CB(zk).verifyCreateFailure_NodeExists();
    }

    @Test
    public void testAsyncCreate2Failure_NoNode() {
        new Create2CB(zk).verifyCreateFailure_NoNode();
    }


    @Test
    public void testAsyncCreate2Failure_NoChildForEphemeral() {
        new Create2CB(zk).verifyCreateFailure_NoChildForEphemeral();
    }

    @Test
    public void testAsyncDelete() {
        new VoidCB(zk).verifyDelete();
    }

    @Test
    public void testAsyncDeleteFailure_NoNode() {
        new VoidCB(zk).verifyDeleteFailure_NoNode();
    }

    @Test
    public void testAsyncDeleteFailure_BadVersion() {
        new VoidCB(zk).verifyDeleteFailure_BadVersion();
    }

    @Test
    public void testAsyncDeleteFailure_NotEmpty() {
        new VoidCB(zk).verifyDeleteFailure_NotEmpty();
    }

    @Test
    public void testAsyncSync() {
        new VoidCB(zk).verifySync();
    }

    @Test
    public void testAsyncSetACL() {
        new StatCB(zk).verifySetACL();
    }

    @Test
    public void testAsyncSetACLFailure_NoNode() {
        new StatCB(zk).verifySetACLFailure_NoNode();
    }

    @Test
    public void testAsyncSetACLFailure_BadVersion() {
        new StatCB(zk).verifySetACLFailure_BadVersion();
    }

    @Test
    public void testAsyncSetData() {
        new StatCB(zk).verifySetData();
    }

    @Test
    public void testAsyncSetDataFailure_NoNode() {
        new StatCB(zk).verifySetDataFailure_NoNode();
    }

    @Test
    public void testAsyncSetDataFailure_BadVersion() {
        new StatCB(zk).verifySetDataFailure_BadVersion();
    }

    @Test
    public void testAsyncExists() {
        new StatCB(zk).verifyExists();
    }

    @Test
    public void testAsyncExistsFailure_NoNode() {
        new StatCB(zk).verifyExistsFailure_NoNode();
    }

    @Test
    public void testAsyncGetACL() {
        new ACLCB(zk).verifyGetACL();
    }

    @Test
    public void testAsyncGetACLFailure_NoNode() {
        new ACLCB(zk).verifyGetACLFailure_NoNode();
    }

    @Test
    public void testAsyncGetChildrenEmpty() {
        new ChildrenCB(zk).verifyGetChildrenEmpty();
    }

    @Test
    public void testAsyncGetChildrenSingle() {
        new ChildrenCB(zk).verifyGetChildrenSingle();
    }

    @Test
    public void testAsyncGetChildrenTwo() {
        new ChildrenCB(zk).verifyGetChildrenTwo();
    }

    @Test
    public void testAsyncGetChildrenFailure_NoNode() {
        new ChildrenCB(zk).verifyGetChildrenFailure_NoNode();
    }

    @Test
    public void testAsyncGetChildren2Empty() {
        new Children2CB(zk).verifyGetChildrenEmpty();
    }

    @Test
    public void testAsyncGetChildren2Single() {
        new Children2CB(zk).verifyGetChildrenSingle();
    }

    @Test
    public void testAsyncGetChildren2Two() {
        new Children2CB(zk).verifyGetChildrenTwo();
    }

    @Test
    public void testAsyncGetChildren2Failure_NoNode() {
        new Children2CB(zk).verifyGetChildrenFailure_NoNode();
    }

    @Test
    public void testAsyncGetData() {
        new DataCB(zk).verifyGetData();
    }

    @Test
    public void testAsyncGetDataFailure_NoNode() {
        new DataCB(zk).verifyGetDataFailure_NoNode();
    }

    @Test
    public void testAsyncMulti() {
        new MultiCB(zk).verifyMulti();
    }

    @Test
    public void testAsyncMultiFailure_AllErrorResult() {
        new MultiCB(zk).verifyMultiFailure_AllErrorResult();
    }

    @Test
    public void testAsyncMultiFailure_NoSideEffect() throws Exception{
        new MultiCB(zk).verifyMultiFailure_NoSideEffect();
    }

    @Test
    public void testAsyncMultiSequential_NoSideEffect() throws Exception{
        new MultiCB(zk).verifyMultiSequential_NoSideEffect();
    }
}
