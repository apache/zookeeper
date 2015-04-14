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
import org.apache.zookeeper.proto.Create2Response;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.MultiHeader;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.ErrorResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Handles the response from a multi request.  Such a response consists of
 * a sequence of responses each prefixed by a MultiResponse that indicates
 * the type of the response.  The end of the list is indicated by a MultiHeader
 * with a negative type.  Each individual response is in the same format as
 * with the corresponding operation in the original request list.
 */
public class MultiResponse implements Record, Iterable<OpResult> {
    private List<OpResult> results = new ArrayList<OpResult>();

    public void add(OpResult x) {
        results.add(x);
    }

    @Override
    public Iterator<OpResult> iterator() {
        return results.iterator();
    }

    public int size() {
        return results.size();
    }

    @Override
    public void serialize(OutputArchive archive, String tag) throws IOException {
        archive.startRecord(this, tag);

        for (OpResult result : results) {
            int err = result.getType() == ZooDefs.OpCode.error ? ((OpResult.ErrorResult)result).getErr() : 0;

            new MultiHeader(result.getType(), false, err).serialize(archive, tag);

            switch (result.getType()) {
                case ZooDefs.OpCode.create:
                    new CreateResponse(((OpResult.CreateResult) result).getPath()).serialize(archive, tag);
                    break;
                case ZooDefs.OpCode.create2:
                	OpResult.CreateResult createResult = (OpResult.CreateResult) result;
                    new Create2Response(createResult.getPath(),
                    		createResult.getStat()).serialize(archive, tag);
                    break;
                case ZooDefs.OpCode.delete:
                case ZooDefs.OpCode.check:
                    break;
                case ZooDefs.OpCode.setData:
                    new SetDataResponse(((OpResult.SetDataResult) result).getStat()).serialize(archive, tag);
                    break;
                case ZooDefs.OpCode.error:
                    new ErrorResponse(((OpResult.ErrorResult) result).getErr()).serialize(archive, tag);
                    break;
                default:
                    throw new IOException("Invalid type " + result.getType() + " in MultiResponse");
            }
        }
        new MultiHeader(-1, true, -1).serialize(archive, tag);
        archive.endRecord(this, tag);
    }

    @Override
    public void deserialize(InputArchive archive, String tag) throws IOException {
        results = new ArrayList<OpResult>();

        archive.startRecord(tag);
        MultiHeader h = new MultiHeader();
        h.deserialize(archive, tag);
        while (!h.getDone()) {
            switch (h.getType()) {
                case ZooDefs.OpCode.create:
                    CreateResponse cr = new CreateResponse();
                    cr.deserialize(archive, tag);
                    results.add(new OpResult.CreateResult(cr.getPath()));
                    break;

                case ZooDefs.OpCode.create2:
                    Create2Response cr2 = new Create2Response();
                    cr2.deserialize(archive, tag);
                    results.add(new OpResult.CreateResult(cr2.getPath(), cr2.getStat()));
                    break;

                case ZooDefs.OpCode.delete:
                    results.add(new OpResult.DeleteResult());
                    break;

                case ZooDefs.OpCode.setData:
                    SetDataResponse sdr = new SetDataResponse();
                    sdr.deserialize(archive, tag);
                    results.add(new OpResult.SetDataResult(sdr.getStat()));
                    break;

                case ZooDefs.OpCode.check:
                    results.add(new OpResult.CheckResult());
                    break;

                case ZooDefs.OpCode.error:
                    //FIXME: need way to more cleanly serialize/deserialize exceptions
                    ErrorResponse er = new ErrorResponse();
                    er.deserialize(archive, tag);
                    results.add(new OpResult.ErrorResult(er.getErr()));
                    break;

                default:
                    throw new IOException("Invalid type " + h.getType() + " in MultiResponse");
            }
            h.deserialize(archive, tag);
        }
        archive.endRecord(tag);
    }

    public List<OpResult> getResultList() {
        return results;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MultiResponse)) return false;

        MultiResponse other = (MultiResponse) o;

        if (results != null) {
            Iterator<OpResult> i = other.results.iterator();
            for (OpResult result : results) {
                if (i.hasNext()) {
                    if (!result.equals(i.next())) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return !i.hasNext();
        }
        else return other.results == null;
    }

    @Override
    public int hashCode() {
        int hash = results.size();
        for (OpResult result : results) {
            hash = (hash * 35) + result.hashCode();
        }
        return hash;
    }
}
