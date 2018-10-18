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

/**
 * This is a simple test to check the integrity of ZooKeeper servers. The client
 * simply cycles through blasting changes to ZooKeeper and the checking what it
 * gets back.
 *
 * The check is very simple. The value of the last successful read or write is
 * stored in lastValue. When we issue a request, that value becomes a possible
 * value. The difficulty is that when a communication error happens, the client
 * doesn't know if the set actually went through. So, our invariant that we
 * check for is that we always read a value that is greater than or equal to
 * a value that we have previously read or set. (Each time we set a value, the
 * value will be one more than the previous set.)
 */
import java.util.Date;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.data.Stat;

public class IntegrityCheck implements StatCallback, DataCallback {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrityCheck.class);

    ZooKeeper zk;

    HashMap<String, byte[]> lastValue = new HashMap<String, byte[]>();

    int count;

    String path;

    int iteration;

    int outstanding;

    int errorCount;

    synchronized void incOutstanding() {
        outstanding++;
    }

    synchronized void decOutstanding() {
        outstanding--;
        notifyAll();
    }

    synchronized void waitOutstanding() throws InterruptedException {
        while (outstanding > 0) {
            wait();
        }
    }

    IntegrityCheck(String hostPort, String path, int count) throws
            Exception {
        zk = ClientBase.createZKClient(hostPort);
        this.path = path;
        this.count = count;
    }

    public void run() throws InterruptedException, KeeperException {
        try{
            LOG.warn("Creating znodes for "+path);
            doCreate();
            LOG.warn("Staring the test loop for "+path);
            while (true) {
                LOG.warn("Staring write cycle for "+path);
                doPopulate();
                waitOutstanding();
                LOG.warn("Staring read cycle for "+path);
                readAll();
                waitOutstanding();
            }
        }finally{
            LOG.warn("Test loop terminated for "+path);
        }
    }

    void readAll() {
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            zk.getData(cpath, false, this, null);
            incOutstanding();
        }

    }

    void doCreate() throws InterruptedException, KeeperException {
        // create top level znode
        try{
            zk.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }catch(KeeperException.NodeExistsException e){
            // ignore duplicate create
        }
        iteration++;
        byte v[] = ("" + iteration).getBytes();
        // create child znodes
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            try{
                if(i%10==0)
                    LOG.warn("Creating znode "+cpath);
                zk.create(cpath, v, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }catch(KeeperException.NodeExistsException e){
                // ignore duplicate create
            }
            lastValue.put(cpath, v);
        }
    }

    void doPopulate() {
        iteration++;
        byte v[] = ("" + iteration).getBytes();
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            zk.setData(cpath, v, -1, this, v);
            incOutstanding();
        }
    }

    synchronized void ensureConnected(){
        while(zk.getState()!=ZooKeeper.States.CONNECTED){
            try {
                wait();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("USAGE: IntegrityCheck zookeeperHostPort znode #children");
            return;
        }
        int childrenCount=0;
        try {
            childrenCount=Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            System.exit(1);
        }

        try{
            final IntegrityCheck ctest = new IntegrityCheck(args[0], args[1],childrenCount);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    System.out.println(new Date().toString()+": Error count = " + ctest.errorCount);
                }
            });
            while(true){
                try{
                    ctest.ensureConnected();
                    ctest.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    public void processResult(int rc, String path, Object ctx, Stat stat) {
        if (rc == 0) {
            lastValue.put(path, (byte[]) ctx);
        }
        decOutstanding();
    }

    public void processResult(int rc, String path, Object ctx, byte[] data,
            Stat stat) {
        if (rc == 0) {
            String string = new String(data);
            String lastString = null;
            byte[] v = lastValue.get(path);
            if (v != null) {
                lastString = new String(v);
            }
            if (lastString != null
                    && Integer.parseInt(string) < Integer.parseInt(lastString)) {
                LOG.error("ERROR: Got " + string + " expected >= "
                        + lastString);
                errorCount++;
            }
            lastValue.put(path, (byte[]) ctx);
        }
        decOutstanding();
    }
}
