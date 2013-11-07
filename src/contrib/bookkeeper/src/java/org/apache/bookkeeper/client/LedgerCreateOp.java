/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

package org.apache.bookkeeper.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.util.StringUtils;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

/**
 * Encapsulates asynchronous ledger create operation
 * 
 */
class LedgerCreateOp implements StringCallback, StatCallback {

    static final Logger LOG = Logger.getLogger(LedgerCreateOp.class);

    CreateCallback cb;
    LedgerMetadata metadata;
    LedgerHandle lh;
    Object ctx;
    byte[] passwd;
    BookKeeper bk;
    DigestType digestType;

   /**
    * Constructor
    * 
    * @param bk
    *       BookKeeper object
    * @param ensembleSize
    *       ensemble size
    * @param quorumSize
    *       quorum size
    * @param digestType
    *       digest type, either MAC or CRC32
    * @param passwd
    *       passowrd
    * @param cb
    *       callback implementation
    * @param ctx
    *       optional control object
    */

    LedgerCreateOp(BookKeeper bk, int ensembleSize, int quorumSize, DigestType digestType, byte[] passwd, CreateCallback cb, Object ctx) {
        this.bk = bk;
        this.metadata = new LedgerMetadata(ensembleSize, quorumSize);
        this.digestType = digestType;
        this.passwd = passwd;
        this.cb = cb;
        this.ctx = ctx;
    }

    /**
     * Initiates the operation
     */
    public void initiate() {
        /*
         * Create ledger node on ZK. We get the id from the sequence number on
         * the node.
         */

        bk.getZkHandle().create(StringUtils.prefix, new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL, this, null);

        // calls the children callback method below
    }


    /**
     * Implements ZooKeeper string callback.
     * 
     * @see org.apache.zookeeper.AsyncCallback.StringCallback#processResult(int, java.lang.String, java.lang.Object, java.lang.String)
     */
    public void processResult(int rc, String path, Object ctx, String name) {

        if (rc != KeeperException.Code.OK.intValue()) {
            LOG.error("Could not create node for ledger", KeeperException.create(KeeperException.Code.get(rc), path));
            cb.createComplete(BKException.Code.ZKException, null, this.ctx);
            return;
        }

        /*
         * Extract ledger id.
         */
        long ledgerId;
        try {
            ledgerId = StringUtils.getLedgerId(name);
        } catch (IOException e) {
            LOG.error("Could not extract ledger-id from path:" + path, e);
            cb.createComplete(BKException.Code.ZKException, null, this.ctx);
            return;
        }

        /*
         * Adding bookies to ledger handle
         */

        ArrayList<InetSocketAddress> ensemble;
        try {
            ensemble = bk.bookieWatcher.getNewBookies(metadata.ensembleSize);
        } catch (BKNotEnoughBookiesException e) {
            LOG.error("Not enough bookies to create ledger" + ledgerId);
            cb.createComplete(e.getCode(), null, this.ctx);
            return;
        }

        /*
         * Add ensemble to the configuration
         */
        metadata.addEnsemble(new Long(0), ensemble);
        try {
            lh = new LedgerHandle(bk, ledgerId, metadata, digestType, passwd);
        } catch (GeneralSecurityException e) {
            LOG.error("Security exception while creating ledger: " + ledgerId, e);
            cb.createComplete(BKException.Code.DigestNotInitializedException, null, this.ctx);
            return;
        }

        lh.writeLedgerConfig(this, null);

    }

    /**
     * Implements ZooKeeper stat callback.
     * 
     * @see org.apache.zookeeper.AsyncCallback.StatCallback#processResult(int, String, Object, Stat)
     */
    public void processResult(int rc, String path, Object ctx, Stat stat) {
        cb.createComplete(rc, lh, this.ctx);
    }

}
