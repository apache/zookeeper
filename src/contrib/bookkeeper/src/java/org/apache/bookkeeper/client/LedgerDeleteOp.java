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

import org.apache.bookkeeper.client.AsyncCallback.DeleteCallback;
import org.apache.bookkeeper.util.StringUtils;
import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback.VoidCallback;

/**
 * Encapsulates asynchronous ledger delete operation
 * 
 */
class LedgerDeleteOp implements VoidCallback {

    static final Logger LOG = Logger.getLogger(LedgerDeleteOp.class);

    BookKeeper bk;
    long ledgerId;
    DeleteCallback cb;
    Object ctx;

    /**
     * Constructor
     * 
     * @param bk
     *            BookKeeper object
     * @param ledgerId
     *            ledger Id
     * @param cb
     *            callback implementation
     * @param ctx
     *            optional control object
     */
    LedgerDeleteOp(BookKeeper bk, long ledgerId, DeleteCallback cb, Object ctx) {
        this.bk = bk;
        this.ledgerId = ledgerId;
        this.cb = cb;
        this.ctx = ctx;
    }

    /**
     * Initiates the operation
     */
    public void initiate() {
        // Asynchronously delete the ledger node in ZK.
        // When this completes, it will invoke the callback method below.
        bk.getZkHandle().delete(StringUtils.getLedgerNodePath(ledgerId), -1, this, null);
    }

    /**
     * Implements ZooKeeper Void Callback.
     * 
     * @see org.apache.zookeeper.AsyncCallback.VoidCallback#processResult(int,
     *      java.lang.String, java.lang.Object)
     */
    public void processResult(int rc, String path, Object ctx) {
        cb.deleteComplete(rc, this.ctx);
    }

}
