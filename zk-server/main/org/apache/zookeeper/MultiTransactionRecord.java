/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.proto.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Encodes a composite transaction.  In the wire format, each transaction
 * consists of a single MultiHeader followed by the appropriate request.
 * Each of these MultiHeaders has a type which indicates
 * the type of the following transaction or a negative number if no more transactions
 * are included.
 */
public class MultiTransactionRecord implements Record, Iterable<Op> {
    private List<Op> ops = new ArrayList<Op>();

    public MultiTransactionRecord() {
    }

    public MultiTransactionRecord(Iterable<Op> ops) {
        for (Op op : ops) {
            add(op);
        }
    }

    @Override
    public Iterator<Op> iterator() {
        return ops.iterator() ;
    }

    public void add(Op op) {
        ops.add(op);
    }

    public int size() {
        return ops.size();
    }

    @Override
    public void serialize(OutputArchive archive, String tag) throws IOException {
        archive.startRecord(this, tag);
        for (Op op : ops) {
            MultiHeader h = new MultiHeader(op.getType(), false, -1);
            h.serialize(archive, tag);
            switch (op.getType()) {
                case ZooDefs.OpCode.create:
                case ZooDefs.OpCode.create2:
                case ZooDefs.OpCode.createTTL:
                case ZooDefs.OpCode.createContainer:
                case ZooDefs.OpCode.delete:
                case ZooDefs.OpCode.setData:
                case ZooDefs.OpCode.check:
                    op.toRequestRecord().serialize(archive, tag);
                    break;
                default:
                    throw new IOException("Invalid type of op");
            }
        }
        new MultiHeader(-1, true, -1).serialize(archive, tag);
        archive.endRecord(this, tag);
    }

    @Override
    public void deserialize(InputArchive archive, String tag) throws IOException {
        archive.startRecord(tag);
        MultiHeader h = new MultiHeader();
        h.deserialize(archive, tag);

        while (!h.getDone()) {
            switch (h.getType()) {
                case ZooDefs.OpCode.create:
                case ZooDefs.OpCode.create2:
                case ZooDefs.OpCode.createContainer:
                    CreateRequest cr = new CreateRequest();
                    cr.deserialize(archive, tag);
                    add(Op.create(cr.getPath(), cr.getData(), cr.getAcl(), cr.getFlags()));
                    break;
                case ZooDefs.OpCode.createTTL:
                    CreateTTLRequest crTtl = new CreateTTLRequest();
                    crTtl.deserialize(archive, tag);
                    add(Op.create(crTtl.getPath(), crTtl.getData(), crTtl.getAcl(), crTtl.getFlags(), crTtl.getTtl()));
                    break;
                case ZooDefs.OpCode.delete:
                    DeleteRequest dr = new DeleteRequest();
                    dr.deserialize(archive, tag);
                    add(Op.delete(dr.getPath(), dr.getVersion()));
                    break;
                case ZooDefs.OpCode.setData:
                    SetDataRequest sdr = new SetDataRequest();
                    sdr.deserialize(archive, tag);
                    add(Op.setData(sdr.getPath(), sdr.getData(), sdr.getVersion()));
                    break;
                case ZooDefs.OpCode.check:
                    CheckVersionRequest cvr = new CheckVersionRequest();
                    cvr.deserialize(archive, tag);
                    add(Op.check(cvr.getPath(), cvr.getVersion()));
                    break;
                default:
                    throw new IOException("Invalid type of op");
            }
            h.deserialize(archive, tag);
        }
        archive.endRecord(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MultiTransactionRecord)) return false;

        MultiTransactionRecord that = (MultiTransactionRecord) o;

        if (ops != null) {
            Iterator<Op> other = that.ops.iterator();
            for (Op op : ops) {
                boolean hasMoreData = other.hasNext();
                if (!hasMoreData) {
                    return false;
                }
                Op otherOp = other.next();
                if (!op.equals(otherOp)) {
                    return false;
                }
            }
            return !other.hasNext();
        } else {
            return that.ops == null;
        }

    }

    @Override
    public int hashCode() {
        int h = 1023;
        for (Op op : ops) {
            h = h * 25 + op.hashCode();
        }
        return h;
    }
}
