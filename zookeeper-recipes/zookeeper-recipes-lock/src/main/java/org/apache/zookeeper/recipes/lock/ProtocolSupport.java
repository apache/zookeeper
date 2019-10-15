/*
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base class for protocol implementations which provides a number of higher
 * level helper methods for working with ZooKeeper along with retrying synchronous
 *  operations if the connection to ZooKeeper closes such as
 *  {@link #retryOperation(ZooKeeperOperation)}.
 */
class ProtocolSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolSupport.class);
    private static final int RETRY_COUNT = 10;

    protected final ZooKeeper zookeeper;
    private AtomicBoolean closed = new AtomicBoolean(false);
    private long retryDelay = 500L;
    private List<ACL> acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    public ProtocolSupport(ZooKeeper zookeeper) {
        this.zookeeper = zookeeper;
    }

    /**
     * Closes this strategy and releases any ZooKeeper resources; but keeps the
     *  ZooKeeper instance open.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            doClose();
        }
    }

    /**
     * return zookeeper client instance.
     *
     * @return zookeeper client instance
     */
    public ZooKeeper getZookeeper() {
        return zookeeper;
    }

    /**
     * return the acl its using.
     *
     * @return the acl.
     */
    public List<ACL> getAcl() {
        return acl;
    }

    /**
     * set the acl.
     *
     * @param acl the acl to set to
     */
    public void setAcl(List<ACL> acl) {
        this.acl = acl;
    }

    /**
     * get the retry delay in milliseconds.
     *
     * @return the retry delay
     */
    public long getRetryDelay() {
        return retryDelay;
    }

    /**
     * Sets the time waited between retry delays.
     *
     * @param retryDelay the retry delay
     */
    public void setRetryDelay(long retryDelay) {
        this.retryDelay = retryDelay;
    }

    /**
     * Allow derived classes to perform
     * some custom closing operations to release resources.
     */
    protected void doClose() {

    }

    /**
     * Perform the given operation, retrying if the connection fails.
     *
     * @return object. it needs to be cast to the callee's expected
     * return type.
     */
    protected Object retryOperation(ZooKeeperOperation operation)
        throws KeeperException, InterruptedException {
        KeeperException exception = null;
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                return operation.execute();
            } catch (KeeperException.SessionExpiredException e) {
                LOG.warn("Session expired {}. Reconnecting...", zookeeper, e);
                throw e;
            } catch (KeeperException.ConnectionLossException e) {
                if (exception == null) {
                    exception = e;
                }
                LOG.debug("Attempt {} failed with connection loss. Reconnecting...", i);
                retryDelay(i);
            }
        }

        throw exception;
    }

    /**
     * Ensures that the given path exists with no data, the current
     * ACL and no flags.
     *
     * @param path
     */
    protected void ensurePathExists(String path) {
        ensureExists(path, null, acl, CreateMode.PERSISTENT);
    }

    /**
     * Ensures that the given path exists with the given data, ACL and flags.
     *
     * @param path
     * @param acl
     * @param flags
     */
    protected void ensureExists(
        final String path,
        final byte[] data,
        final List<ACL> acl,
        final CreateMode flags) {
        try {
            retryOperation(() -> {
                Stat stat = zookeeper.exists(path, false);
                if (stat != null) {
                    return true;
                }
                zookeeper.create(path, data, acl, flags);
                return true;
            });
        } catch (KeeperException | InterruptedException e) {
            LOG.warn("Unexpected exception", e);
        }
    }

    /**
     * Returns true if this protocol has been closed.
     *
     * @return true if this protocol is closed
     */
    protected boolean isClosed() {
        return closed.get();
    }

    /**
     * Performs a retry delay if this is not the first attempt.
     *
     * @param attemptCount the number of the attempts performed so far
     */
    protected void retryDelay(int attemptCount) {
        if (attemptCount > 0) {
            try {
                Thread.sleep(attemptCount * retryDelay);
            } catch (InterruptedException e) {
                LOG.warn("Failed to sleep.", e);
            }
        }
    }

}
