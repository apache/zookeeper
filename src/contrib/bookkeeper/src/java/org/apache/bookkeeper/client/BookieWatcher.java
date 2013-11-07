package org.apache.bookkeeper.client;

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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.util.SafeRunnable;
import org.apache.bookkeeper.util.StringUtils;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.KeeperException.Code;

/**
 * This class is responsible for maintaining a consistent view of what bookies
 * are available by reading Zookeeper (and setting watches on the bookie nodes).
 * When a bookie fails, the other parts of the code turn to this class to find a
 * replacement
 * 
 */
class BookieWatcher implements Watcher, ChildrenCallback {
    static final Logger logger = Logger.getLogger(BookieWatcher.class);
    
    public static final String BOOKIE_REGISTRATION_PATH = "/ledgers/available";
    static final Set<InetSocketAddress> EMPTY_SET = new HashSet<InetSocketAddress>();
    public static int ZK_CONNECT_BACKOFF_SEC = 1;

    BookKeeper bk;
    ScheduledExecutorService scheduler;

    Set<InetSocketAddress> knownBookies = new HashSet<InetSocketAddress>();

    SafeRunnable reReadTask = new SafeRunnable() {
        @Override
        public void safeRun() {
            readBookies();
        }
    };

    public BookieWatcher(BookKeeper bk) {
        this.bk = bk;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }
    
    public void halt(){
        scheduler.shutdown();
    }

    public void readBookies() {
        readBookies(this);
    }

    public void readBookies(ChildrenCallback callback) {
        bk.getZkHandle().getChildren( BOOKIE_REGISTRATION_PATH, this, callback, null);
    }

    @Override
    public void process(WatchedEvent event) {
        readBookies();
    }

    @Override
    public void processResult(int rc, String path, Object ctx, List<String> children) {

        if (rc != KeeperException.Code.OK.intValue()) {
            //logger.error("Error while reading bookies", KeeperException.create(Code.get(rc), path));
            // try the read after a second again
            scheduler.schedule(reReadTask, ZK_CONNECT_BACKOFF_SEC, TimeUnit.SECONDS);
            return;
        }

        // Read the bookie addresses into a set for efficient lookup
        Set<InetSocketAddress> newBookieAddrs = new HashSet<InetSocketAddress>();
        for (String bookieAddrString : children) {
            InetSocketAddress bookieAddr;
            try {
                bookieAddr = StringUtils.parseAddr(bookieAddrString);
            } catch (IOException e) {
                logger.error("Could not parse bookie address: " + bookieAddrString + ", ignoring this bookie");
                continue;
            }
            newBookieAddrs.add(bookieAddr);
        }

        synchronized (this) {
            knownBookies = newBookieAddrs;
        }
    }

    /**
     * Blocks until bookies are read from zookeeper, used in the {@link BookKeeper} constructor.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void readBookiesBlocking() throws InterruptedException, KeeperException {
        final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();
        readBookies(new ChildrenCallback() {
            public void processResult(int rc, String path, Object ctx, List<String> children) {
                try {
                    BookieWatcher.this.processResult(rc, path, ctx, children);
                    queue.put(rc);
                } catch (InterruptedException e) {
                    logger.error("Interruped when trying to read bookies in a blocking fashion");
                    throw new RuntimeException(e);
                }
            }
        });
        int rc = queue.take();

        if (rc != KeeperException.Code.OK.intValue()) {
            throw KeeperException.create(Code.get(rc));
        }
    }

    /**
     * Wrapper over the {@link #getAdditionalBookies(Set, int)} method when there is no exclusion list (or exisiting bookies)
     * @param numBookiesNeeded
     * @return
     * @throws BKNotEnoughBookiesException
     */
    public ArrayList<InetSocketAddress> getNewBookies(int numBookiesNeeded) throws BKNotEnoughBookiesException {
        return getAdditionalBookies(EMPTY_SET, numBookiesNeeded);
    }

    /**
     * Wrapper over the {@link #getAdditionalBookies(Set, int)} method when you just need 1 extra bookie
     * @param existingBookies
     * @return
     * @throws BKNotEnoughBookiesException
     */
    public InetSocketAddress getAdditionalBookie(List<InetSocketAddress> existingBookies)
            throws BKNotEnoughBookiesException {
        return getAdditionalBookies(new HashSet<InetSocketAddress>(existingBookies), 1).get(0);
    }

    /**
     * Returns additional bookies given an exclusion list and how many are needed
     * @param existingBookies
     * @param numAdditionalBookiesNeeded
     * @return
     * @throws BKNotEnoughBookiesException
     */
    public ArrayList<InetSocketAddress> getAdditionalBookies(Set<InetSocketAddress> existingBookies,
            int numAdditionalBookiesNeeded) throws BKNotEnoughBookiesException {

        ArrayList<InetSocketAddress> newBookies = new ArrayList<InetSocketAddress>();

        if (numAdditionalBookiesNeeded <= 0) {
            return newBookies;
        }

        List<InetSocketAddress> allBookies;

        synchronized (this) {
            allBookies = new ArrayList<InetSocketAddress>(knownBookies);
        }

        Collections.shuffle(allBookies);

        for (InetSocketAddress bookie : allBookies) {
            if (existingBookies.contains(bookie)) {
                continue;
            }

            newBookies.add(bookie);
            numAdditionalBookiesNeeded--;

            if (numAdditionalBookiesNeeded == 0) {
                return newBookies;
            }
        }

        throw new BKNotEnoughBookiesException();
    }

}
