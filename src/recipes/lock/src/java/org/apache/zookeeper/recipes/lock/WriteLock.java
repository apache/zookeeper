/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.recipes.lock;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import static org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A <a href="package.html">protocol to implement an exclusive
 *  write lock or to elect a leader</a>. <p/> You invoke {@link #lock()} to 
 *  start the process of grabbing the lock; you may get the lock then or it may be 
 *  some time later. <p/> You can register a listener so that you are invoked 
 *  when you get the lock; otherwise you can ask if you have the lock
 *  by calling {@link #isOwner()}
 *
 */
public class WriteLock extends ProtocolSupport {
    private static final Logger LOG = Logger.getLogger(WriteLock.class);

    private final String dir;
    private String id;
    private ZNodeName idName;
    private String ownerId;
    private String lastChildId;
    private byte[] data = {0x12, 0x34};
    private LockListener callback;
    private LockZooKeeperOperation zop;
    
    /**
     * zookeeper contructor for writelock
     * @param zookeeper zookeeper client instance
     * @param dir the parent path you want to use for locking
     * @param acls the acls that you want to use for all the paths, 
     * if null world read/write is used.
     */
    public WriteLock(ZooKeeper zookeeper, String dir, List<ACL> acl) {
        super(zookeeper);
        this.dir = dir;
        if (acl != null) {
            setAcl(acl);
        }
        this.zop = new LockZooKeeperOperation();
    }
    
    /**
     * zookeeper contructor for writelock with callback
     * @param zookeeper the zookeeper client instance
     * @param dir the parent path you want to use for locking
     * @param acl the acls that you want to use for all the paths
     * @param callback the call back instance
     */
    public WriteLock(ZooKeeper zookeeper, String dir, List<ACL> acl, 
            LockListener callback) {
        this(zookeeper, dir, acl);
        this.callback = callback;
    }

    /**
     * return the current locklistener
     * @return the locklistener
     */
    public LockListener getLockListener() {
        return this.callback;
    }
    
    /**
     * register a different call back listener
     * @param callback the call back instance
     */
    public void setLockListener(LockListener callback) {
        this.callback = callback;
    }

    /**
     * Removes the lock or associated znode if 
     * you no longer require the lock. this also 
     * removes your request in the queue for locking
     * in case you do not already hold the lock.
     * @throws RuntimeException throws a runtime exception
     * if it cannot connect to zookeeper.
     */
    public synchronized void unlock() throws RuntimeException {
        
        if (!isClosed() && id != null) {
            // we don't need to retry this operation in the case of failure
            // as ZK will remove ephemeral files and we don't wanna hang
            // this process when closing if we cannot reconnect to ZK
            try {
                
                ZooKeeperOperation zopdel = new ZooKeeperOperation() {
                    public boolean execute() throws KeeperException,
                        InterruptedException {
                        zookeeper.delete(id, -1);   
                        return Boolean.TRUE;
                    }
                };
                zopdel.execute();
            } catch (InterruptedException e) {
                LOG.warn("Caught: " + e, e);
                //set that we have been interrupted.
               Thread.currentThread().interrupt();
            } catch (KeeperException.NoNodeException e) {
                // do nothing
            } catch (KeeperException e) {
                LOG.warn("Caught: " + e, e);
                throw (RuntimeException) new RuntimeException(e.getMessage()).
                    initCause(e);
            }
            finally {
                if (callback != null) {
                    callback.lockReleased();
                }
                id = null;
            }
        }
    }
    
    /** 
     * the watcher called on  
     * getting watch while watching 
     * my predecessor
     */
    private class LockWatcher implements Watcher {
        public void process(WatchedEvent event) {
            // lets either become the leader or watch the new/updated node
            LOG.debug("Watcher fired on path: " + event.getPath() + " state: " + 
                    event.getState() + " type " + event.getType());
            try {
                lock();
            } catch (Exception e) {
                LOG.warn("Failed to acquire lock: " + e, e);
            }
        }
    }
    
    /**
     * a zoookeeper operation that is mainly responsible
     * for all the magic required for locking.
     */
    private  class LockZooKeeperOperation implements ZooKeeperOperation {
        
        /** find if we have been created earler if not create our node
         * 
         * @param prefix the prefix node
         * @param zookeeper teh zookeeper client
         * @param dir the dir paretn
         * @throws KeeperException
         * @throws InterruptedException
         */
        private void findPrefixInChildren(String prefix, ZooKeeper zookeeper, String dir) 
            throws KeeperException, InterruptedException {
            List<String> names = zookeeper.getChildren(dir, false);
            for (String name : names) {
                if (name.startsWith(prefix)) {
                    id = name;
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Found id created last time: " + id);
                    }
                    break;
                }
            }
            if (id == null) {
                id = zookeeper.create(dir + "/" + prefix, data, 
                        getAcl(), EPHEMERAL_SEQUENTIAL);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Created id: " + id);
                }
            }

        }
        
        /**
         * the command that is run and retried for actually 
         * obtaining the lock
         * @return if the command was successful or not
         */
        public boolean execute() throws KeeperException, InterruptedException {
            do {
                if (id == null) {
                    long sessionId = zookeeper.getSessionId();
                    String prefix = "x-" + sessionId + "-";
                    // lets try look up the current ID if we failed 
                    // in the middle of creating the znode
                    findPrefixInChildren(prefix, zookeeper, dir);
                    idName = new ZNodeName(id);
                }
                if (id != null) {
                    List<String> names = zookeeper.getChildren(dir, false);
                    if (names.isEmpty()) {
                        LOG.warn("No children in: " + dir + " when we've just " +
                        "created one! Lets recreate it...");
                        // lets force the recreation of the id
                        id = null;
                    } else {
                        // lets sort them explicitly (though they do seem to come back in order ususally :)
                        SortedSet<ZNodeName> sortedNames = new TreeSet<ZNodeName>();
                        for (String name : names) {
                            sortedNames.add(new ZNodeName(dir + "/" + name));
                        }
                        ownerId = sortedNames.first().getName();
                        SortedSet<ZNodeName> lessThanMe = sortedNames.headSet(idName);
                        if (!lessThanMe.isEmpty()) {
                            ZNodeName lastChildName = lessThanMe.last();
                            lastChildId = lastChildName.getName();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("watching less than me node: " + lastChildId);
                            }
                            Stat stat = zookeeper.exists(lastChildId, new LockWatcher());
                            if (stat != null) {
                                return Boolean.FALSE;
                            } else {
                                LOG.warn("Could not find the" +
                                		" stats for less than me: " + lastChildName.getName());
                            }
                        } else {
                            if (isOwner()) {
                                if (callback != null) {
                                    callback.lockAcquired();
                                }
                                return Boolean.TRUE;
                            }
                        }
                    }
                }
            }
            while (id == null);
            return Boolean.FALSE;
        }
    };

    /**
     * Attempts to acquire the exclusive write lock returning whether or not it was
     * acquired. Note that the exclusive lock may be acquired some time later after
     * this method has been invoked due to the current lock owner going away.
     */
    public synchronized boolean lock() throws KeeperException, InterruptedException {
        if (isClosed()) {
            return false;
        }
        ensurePathExists(dir);

        return (Boolean) retryOperation(zop);
    }

    /**
     * return the parent dir for lock
     * @return the parent dir used for locks.
     */
    public String getDir() {
        return dir;
    }

    /**
     * Returns true if this node is the owner of the
     *  lock (or the leader)
     */
    public boolean isOwner() {
        return id != null && ownerId != null && id.equals(ownerId);
    }

    /**
     * return the id for this lock
     * @return the id for this lock
     */
    public String getId() {
       return this.id;
    }
}

