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

package org.apache.zookeeper.server;

import org.apache.jute.Record;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * A helper class to represent the txn entry.
 */
public final class TxnLogEntry {
    private final Record txn;
    private final TxnHeader header;
    private final TxnDigest digest;

    public TxnLogEntry(Record txn, TxnHeader header, TxnDigest digest) {
        this.txn = txn;
        this.header = header;
        this.digest = digest;
    }

    public Record getTxn() {
        return txn;
    }

    public TxnHeader getHeader() {
        return header;
    }

    public TxnDigest getDigest() {
        return digest;
    }
}
