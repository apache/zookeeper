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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.ACLCallback;
import org.apache.zookeeper.AsyncCallback.Children2Callback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.Create2Callback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;

public class AsyncOps {
    /**
     * This is the base class for all of the async callback classes. It will
     * verify the expected value against the actual value.
     * 
     * Basic operation is that the subclasses will generate an "expected" value
     * which is defined by the "toString" method of the subclass. This is
     * passed through to the verify clause by specifying it as the ctx object
     * of each async call (processResult methods get the ctx as part of
     * the callback). Additionally the callback will also overwrite any
     * instance fields with matching parameter arguments to the processResult
     * method. The cb instance can then compare the expected to the
     * actual value by again calling toString and comparing the two.
     * 
     * The format of each expected value differs (is defined) by subclass.
     * Generally the expected value starts with the result code (rc) and path
     * of the node being operated on, followed by the fields specific to
     * each operation type (cb subclass). For example ChildrenCB specifies
     * a list of the expected children suffixed onto the rc and path. See
     * the toString() method of each subclass for details of it's format. 
     */
    public static abstract class AsyncCB {
        protected final ZooKeeper zk;
        protected long defaultTimeoutMillis = 30000;
        
        /** the latch is used to await the results from the server */
        CountDownLatch latch;

        Code rc = Code.OK;
        String path = "/foo";
        String expected;
        
        public AsyncCB(ZooKeeper zk, CountDownLatch latch) {
            this.zk = zk;
            this.latch = latch;
        }
        
        public void setRC(Code rc) {
            this.rc = rc;
        }
        
        public void setPath(String path) {
            this.path = path;
        }
        
        public void processResult(Code rc, String path, Object ctx)
        {
            this.rc = rc;
            this.path = path;
            this.expected = (String)ctx;
            latch.countDown();
        }
        
        /** String format is rc:path:<suffix> where <suffix> is defined by each
         * subclass individually. */
        @Override
        public String toString() {
            return rc + ":" + path + ":"; 
        }

        protected void verify() {
            try {
                latch.await(defaultTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Assert.fail("unexpected interrupt");
            }
            // on the lookout for timeout
            Assert.assertSame(0L, latch.getCount());
            
            String actual = toString();
            
            Assert.assertEquals(expected, actual);
        }
    }
    
    public static class StringCB extends AsyncCB implements StringCallback {
        byte[] data = new byte[10];
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        CreateMode flags = CreateMode.PERSISTENT;
        String name = path;
        
        StringCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }
        
        StringCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void setPath(String path) {
            super.setPath(path);
            this.name = path;
        }
        
        public String nodeName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        
        public void processResult(int rc, String path, Object ctx, String name)
        {
            this.name = name;
            super.processResult(Code.get(rc), path, ctx);
        }

        public AsyncCB create() {
            zk.create(path, data, acl, flags, this, toString());
            return this;
        }

        public AsyncCB createEphemeral() {
            zk.create(path, data, acl, CreateMode.EPHEMERAL, this, toString());
            return this;
        }
        
        public void verifyCreate() {
            create();
            verify();
        }

        public void verifyCreateEphemeral() {
            createEphemeral();
            verify();
        }
        
        public void verifyCreateFailure_NodeExists() {
            new StringCB(zk).verifyCreate();
            
            rc = Code.NODEEXISTS;
            name = null;
            zk.create(path, data, acl, flags, this, toString());
            verify();
        }

        public void verifyCreateFailure_NoNode() {

            rc = Code.NONODE;
            name = null;
            path = path + "/bar";
            zk.create(path, data, acl, flags, this, toString());

            verify();
        }

        public void verifyCreateFailure_NoChildForEphemeral() {
            new StringCB(zk).verifyCreateEphemeral();

            rc = Code.NOCHILDRENFOREPHEMERALS;
            name = null;
            path = path + "/bar";
            zk.create(path, data, acl, flags, this, toString());

            verify();
        }

        @Override
        public String toString() {
            return super.toString() + name; 
        }
    }

    public static class ACLCB extends AsyncCB implements ACLCallback {
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        int version = 0;
        Stat stat = new Stat();
        byte[] data = "testing".getBytes();
        
        ACLCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        ACLCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }

        public void processResult(int rc, String path, Object ctx,
                List<ACL> acl, Stat stat)
        {
            this.acl = acl;
            this.stat = stat;
            super.processResult(Code.get(rc), path, ctx);
        }
        
        public void verifyGetACL() {
            new StringCB(zk).verifyCreate();

            zk.getACL(path, stat, this, toString());
            verify();
        }

        public void verifyGetACLFailure_NoNode(){
            rc = Code.NONODE;
            stat = null;
            acl = null;
            zk.getACL(path, stat, this, toString());

            verify();
        }
        
        public String toString(List<ACL> acls) {
            if (acls == null) {
                return "";
            }

            StringBuilder result = new StringBuilder();
            for(ACL acl : acls) {
                result.append(acl.getPerms()).append("::");
            }
            return result.toString();
        }
        
        @Override
        public String toString() {
            return super.toString() + toString(acl) + ":" 
                + ":" + version + ":" + new String(data)
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                        + stat.getCversion() + ":" + stat.getEphemeralOwner()
                        + ":" + stat.getVersion()); 
        }
    }

    public static class ChildrenCB extends AsyncCB implements ChildrenCallback {
        List<String> children = new ArrayList<String>();
        
        ChildrenCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        ChildrenCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void processResult(int rc, String path, Object ctx,
                List<String> children)
        {
            this.children =
                (children == null ? new ArrayList<String>() : children);
            Collections.sort(this.children);
            super.processResult(Code.get(rc), path, ctx);
        }
        
        public StringCB createNode() {
            StringCB parent = new StringCB(zk);
            parent.verifyCreate();

            return parent;
        }
        
        public StringCB createNode(StringCB parent) {
            String childName = "bar";

            return createNode(parent, childName);
        }

        public StringCB createNode(StringCB parent, String childName) {
            StringCB child = new StringCB(zk);
            child.setPath(parent.path + "/" + childName);
            child.verifyCreate();
            
            return child;
        }
        
        public void verifyGetChildrenEmpty() {
            StringCB parent = createNode();
            path = parent.path;
            verify();
        }

        public void verifyGetChildrenSingle() {
            StringCB parent = createNode();
            StringCB child = createNode(parent);

            path = parent.path;
            children.add(child.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenTwo() {
            StringCB parent = createNode();
            StringCB child1 = createNode(parent, "child1");
            StringCB child2 = createNode(parent, "child2");
        
            path = parent.path;
            children.add(child1.nodeName());
            children.add(child2.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            verify();
        }
        
        @Override
        public void verify() {
            zk.getChildren(path, false, this, toString());
            super.verify();
        }

        @Override
        public String toString() {
            return super.toString() + children.toString();
        }
    }

    public static class Children2CB extends AsyncCB implements Children2Callback {
        List<String> children = new ArrayList<String>();

        Children2CB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        Children2CB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }

        public void processResult(int rc, String path, Object ctx,
                List<String> children, Stat stat)
        {
            this.children =
                (children == null ? new ArrayList<String>() : children);
            Collections.sort(this.children);
            super.processResult(Code.get(rc), path, ctx);
        }
        
        public StringCB createNode() {
            StringCB parent = new StringCB(zk);
            parent.verifyCreate();

            return parent;
        }
        
        public StringCB createNode(StringCB parent) {
            String childName = "bar";

            return createNode(parent, childName);
        }

        public StringCB createNode(StringCB parent, String childName) {
            StringCB child = new StringCB(zk);
            child.setPath(parent.path + "/" + childName);
            child.verifyCreate();
            
            return child;
        }
        
        public void verifyGetChildrenEmpty() {
            StringCB parent = createNode();
            path = parent.path;
            verify();
        }

        public void verifyGetChildrenSingle() {
            StringCB parent = createNode();
            StringCB child = createNode(parent);

            path = parent.path;
            children.add(child.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenTwo() {
            StringCB parent = createNode();
            StringCB child1 = createNode(parent, "child1");
            StringCB child2 = createNode(parent, "child2");
        
            path = parent.path;
            children.add(child1.nodeName());
            children.add(child2.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            verify();
        }
        
        @Override
        public void verify() {
            zk.getChildren(path, false, this, toString());
            super.verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + children.toString(); 
        }
    }

    public static class Create2CB extends AsyncCB implements Create2Callback {
    	  byte[] data = new byte[10];
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        CreateMode flags = CreateMode.PERSISTENT;
        String name = path;
        Stat stat = new Stat();

        Create2CB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        Create2CB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }

        public void setPath(String path) {
            super.setPath(path);
            this.name = path;
        }

        public String nodeName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public void processResult(int rc, String path, Object ctx,
                String name, Stat stat) {
            this.name = name;
            this.stat = stat;
            super.processResult(Code.get(rc), path, ctx);
        }

        public AsyncCB create() {
            zk.create(path, data, acl, flags, this, toString());
            return this;
        }

        public void verifyCreate() {
            create();
            verify();
        }

        public void verifyCreateFailure_NodeExists() {
            new Create2CB(zk).verifyCreate();
            rc = Code.NODEEXISTS;
            name = null;
            stat = null;
            zk.create(path, data, acl, flags, this, toString());
            verify();
        }

        public void verifyCreateFailure_NoNode() {
            rc = Code.NONODE;
            name = null;
            stat = null;
            path = path + "/bar";
            zk.create(path, data, acl, flags, this, toString());

            verify();
        }

        public void verifyCreateFailure_NoChildForEphemeral() {
            new StringCB(zk).verifyCreateEphemeral();

            rc = Code.NOCHILDRENFOREPHEMERALS;
            name = null;
            stat = null;
            path = path + "/bar";
            zk.create(path, data, acl, flags, this, toString());

            verify();
        }

        @Override
        public String toString() {
            return super.toString() + name + ":" +
                (stat == null ? "null" : stat.getAversion() + ":" +
            		 stat.getCversion() + ":" + stat.getEphemeralOwner() +
                 ":" + stat.getVersion());
        }
    }

    public static class DataCB extends AsyncCB implements DataCallback {
        byte[] data = new byte[10];
        Stat stat = new Stat();
        
        DataCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        DataCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }
        
        public void processResult(int rc, String path, Object ctx, byte[] data,
                Stat stat)
        {
            this.data = data;
            this.stat = stat;
            super.processResult(Code.get(rc), path, ctx);
        }
        
        public void verifyGetData() {
            new StringCB(zk).verifyCreate();

            zk.getData(path, false, this, toString());
            verify();
        }
        
        public void verifyGetDataFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            data = null;
            stat = null;
            zk.getData(path, false, this, toString());
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString()
                + ":" + (data == null ? "null" : new String(data))
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                    + stat.getCversion() + ":" + stat.getEphemeralOwner()
                    + ":" + stat.getVersion()); 
        }
    }

    public static class StatCB extends AsyncCB implements StatCallback {
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        int version = 0;
        Stat stat = new Stat();
        byte[] data = "testing".getBytes();
        
        StatCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        StatCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }
        
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            this.stat = stat;
            super.processResult(Code.get(rc), path, ctx);
        }
        
        public void verifySetACL() {
            stat.setAversion(1);
            new StringCB(zk).verifyCreate();

            zk.setACL(path, acl, version, this, toString());
            verify();
        }
        
        public void verifySetACLFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            stat = null;
            zk.setACL(path, acl, version, this, toString());
            verify();
        }

        public void verifySetACLFailure_BadVersion() {
            new StringCB(zk).verifyCreate();

            rc = Code.BADVERSION;
            stat = null;
            zk.setACL(path, acl, version + 1, this, toString());

            verify();
        }
        
        public void setData() {
            zk.setData(path, data, version, this, toString());
        }
        
        public void verifySetData() {
            stat.setVersion(1);
            new StringCB(zk).verifyCreate();

            setData();
            verify();
        }
        
        public void verifySetDataFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            stat = null;
            zk.setData(path, data, version, this, toString());
            verify();
        }

        public void verifySetDataFailure_BadVersion() {
            new StringCB(zk).verifyCreate();

            rc = Code.BADVERSION;
            stat = null;
            zk.setData(path, data, version + 1, this, toString());

            verify();
        }
        
        public void verifyExists() {
            new StringCB(zk).verifyCreate();

            zk.exists(path, false, this, toString());
            verify();
        }
        
        public void verifyExistsFailure_NoNode() {
            rc = KeeperException.Code.NONODE;
            stat = null;
            zk.exists(path, false, this, toString());
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + version
                + ":" + new String(data)
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                        + stat.getCversion() + ":" + stat.getEphemeralOwner()
                        + ":" + stat.getVersion()); 
        }
    }

    public static class VoidCB extends AsyncCB implements VoidCallback {
        int version = 0;
        
        VoidCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }
        
        VoidCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void processResult(int rc, String path, Object ctx) {
            super.processResult(Code.get(rc), path, ctx);
        }

        public void delete() {
            zk.delete(path, version, this, toString());
        }
        
        public void verifyDelete() {
            new StringCB(zk).verifyCreate();

            delete();
            verify();
        }
        
        public void verifyDeleteFailure_NoNode() {
            rc = Code.NONODE;
            zk.delete(path, version, this, toString());
            verify();
        }

        public void verifyDeleteFailure_BadVersion() {
            new StringCB(zk).verifyCreate();
            rc = Code.BADVERSION;
            zk.delete(path, version + 1, this, toString());
            verify();
        }

        public void verifyDeleteFailure_NotEmpty() {
            StringCB scb = new StringCB(zk);
            scb.create();
            scb.setPath(path + "/bar");
            scb.create();

            rc = Code.NOTEMPTY;
            zk.delete(path, version, this, toString());
            verify();
        }
        
        public void sync() {
            zk.sync(path, this, toString());
        }
        
        public void verifySync() {
            sync();
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + version; 
        }
    }

    public static class MultiCB implements MultiCallback {
        ZooKeeper zk;
        int rc;
        List<OpResult> opResults;
        final CountDownLatch latch = new CountDownLatch(1);

        MultiCB(ZooKeeper zk) {
            this.zk = zk;
        }

        public void processResult(int rc, String path, Object ctx,
                                  List<OpResult> opResults) {
            this.rc = rc;
            this.opResults = opResults;
            latch.countDown();
        }

        void latch_await(){
            try {
                latch.await(10000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Assert.fail("unexpected interrupt");
            }
            Assert.assertSame(0L, latch.getCount());
        }

        public void verifyMulti() {
            List<Op> ops = Arrays.asList(
                    Op.create("/multi", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", -1));
            zk.multi(ops, this, null);
            latch_await();

            Assert.assertEquals(this.rc, KeeperException.Code.OK.intValue());
            Assert.assertTrue(this.opResults.get(0) instanceof OpResult.CreateResult);
            Assert.assertTrue(this.opResults.get(1) instanceof OpResult.DeleteResult);
        }

        public void verifyMultiFailure_AllErrorResult() {
            List<Op> ops = Arrays.asList(
                    Op.create("/multi", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/nonexist1", -1),
                    Op.setData("/multi", "test".getBytes(), -1));
            zk.multi(ops, this, null);
            latch_await();

            Assert.assertTrue(this.opResults.get(0) instanceof OpResult.ErrorResult);
            Assert.assertTrue(this.opResults.get(1) instanceof OpResult.ErrorResult);
            Assert.assertTrue(this.opResults.get(2) instanceof OpResult.ErrorResult);
        }

        public void verifyMultiFailure_NoSideEffect() throws KeeperException, InterruptedException {
            List<Op> ops = Arrays.asList(
                    Op.create("/multi", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/nonexist1", -1));
            zk.multi(ops, this, null);
            latch_await();

            Assert.assertTrue(this.opResults.get(0) instanceof OpResult.ErrorResult);
            Assert.assertNull(zk.exists("/multi", false));
        }

        public void verifyMultiSequential_NoSideEffect() throws Exception{
            StringCB scb = new StringCB(zk);
            scb.verifyCreate();
            String path = scb.path + "-";
            String seqPath = path + "0000000002";

            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            Assert.assertNotNull(zk.exists(path + "0000000001", false));

            List<Op> ops = Arrays.asList(
                    Op.create(path , new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL),
                    Op.delete("/nonexist", -1));
            zk.multi(ops, this, null);
            latch_await();

            Assert.assertNull(zk.exists(seqPath, false));
            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            Assert.assertNotNull(zk.exists(seqPath, false));
        }
    }
}
