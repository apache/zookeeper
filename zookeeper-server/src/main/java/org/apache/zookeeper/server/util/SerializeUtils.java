/*
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

package org.apache.zookeeper.server.util;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.txn.CloseSessionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.CreateTxnV0;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializeUtils {

    private static final Logger LOG = LoggerFactory.getLogger(SerializeUtils.class);

    public static TxnLogEntry deserializeTxn(byte[] txnBytes) throws IOException {
        TxnHeader hdr = new TxnHeader();
        final ByteArrayInputStream bais = new ByteArrayInputStream(txnBytes);
        InputArchive ia = BinaryInputArchive.getArchive(bais);

        hdr.deserialize(ia, "hdr");
        bais.mark(bais.available());
        Record txn = null;
        switch (hdr.getType()) {
        case OpCode.createSession:
            // This isn't really an error txn; it just has the same
            // format. The error represents the timeout
            txn = new CreateSessionTxn();
            break;
        case OpCode.closeSession:
            txn = ZooKeeperServer.isCloseSessionTxnEnabled()
                    ?  new CloseSessionTxn() : null;
            break;
        case OpCode.create:
        case OpCode.create2:
            txn = new CreateTxn();
            break;
        case OpCode.createTTL:
            txn = new CreateTTLTxn();
            break;
        case OpCode.createContainer:
            txn = new CreateContainerTxn();
            break;
        case OpCode.delete:
        case OpCode.deleteContainer:
            txn = new DeleteTxn();
            break;
        case OpCode.reconfig:
        case OpCode.setData:
            txn = new SetDataTxn();
            break;
        case OpCode.setACL:
            txn = new SetACLTxn();
            break;
        case OpCode.error:
            txn = new ErrorTxn();
            break;
        case OpCode.multi:
            txn = new MultiTxn();
            break;
        default:
            throw new IOException("Unsupported Txn with type=%d" + hdr.getType());
        }
        if (txn != null) {
            try {
                txn.deserialize(ia, "txn");
            } catch (EOFException e) {
                // perhaps this is a V0 Create
                if (hdr.getType() == OpCode.create) {
                    CreateTxn create = (CreateTxn) txn;
                    bais.reset();
                    CreateTxnV0 createv0 = new CreateTxnV0();
                    createv0.deserialize(ia, "txn");
                    // cool now make it V1. a -1 parentCVersion will
                    // trigger fixup processing in processTxn
                    create.setPath(createv0.getPath());
                    create.setData(createv0.getData());
                    create.setAcl(createv0.getAcl());
                    create.setEphemeral(createv0.getEphemeral());
                    create.setParentCVersion(-1);
                } else if (hdr.getType() == OpCode.closeSession) {
                    // perhaps this is before CloseSessionTxn was added,
                    // ignore it and reset txn to null
                    txn = null;
                } else {
                    throw e;
                }
            }
        }
        TxnDigest digest = null;

        if (ZooKeeperServer.isDigestEnabled()) {
            digest = new TxnDigest();
            try {
                digest.deserialize(ia, "digest");
            } catch (EOFException exception) {
                // may not have digest in the txn
                digest = null;
            }
        }

        return new TxnLogEntry(txn, hdr, digest);
    }

    public static void deserializeSnapshot(DataTree dt, InputArchive ia, Map<Long, Integer> sessions) throws IOException {
        int count = ia.readInt("count");
        while (count > 0) {
            long id = ia.readLong("id");
            int to = ia.readInt("timeout");
            sessions.put(id, to);
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(
                    LOG,
                    ZooTrace.SESSION_TRACE_MASK,
                    "loadData --- session in archive: " + id + " with timeout: " + to);
            }
            count--;
        }
        dt.deserialize(ia, "tree");
    }

    public static void serializeSnapshot(DataTree dt, OutputArchive oa, Map<Long, Integer> sessions) throws IOException {
        HashMap<Long, Integer> sessSnap = new HashMap<Long, Integer>(sessions);
        oa.writeInt(sessSnap.size(), "count");
        for (Entry<Long, Integer> entry : sessSnap.entrySet()) {
            oa.writeLong(entry.getKey().longValue(), "id");
            oa.writeInt(entry.getValue().intValue(), "timeout");
        }
        dt.serialize(oa, "tree");
    }

    public static byte[] serializeRequest(Request request) {
        if (request == null || request.getHdr() == null) {
            return null;
        }
        byte[] data = new byte[32];
        try {
            data = Util.marshallTxnEntry(request.getHdr(), request.getTxn(), request.getTxnDigest());
        } catch (IOException e) {
            LOG.error("This really should be impossible", e);
        }
        return data;
    }

}
