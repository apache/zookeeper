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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.log4j.Logger;

import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.ExistsRequest;
import org.apache.zookeeper.proto.ExistsResponse;
import org.apache.zookeeper.proto.GetACLRequest;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.GetChildrenRequest;
import org.apache.zookeeper.proto.GetChildrenResponse;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.proto.GetDataResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.SetACLResponse;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.proto.SyncRequest;
import org.apache.zookeeper.proto.SyncResponse;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.NIOServerCnxn.Factory;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.ErrorTxn;

/**
 * This Request processor actually applies any transaction associated with a
 * request and services any queries. It is always at the end of a
 * RequestProcessor chain (hence the name), so it does not have a nextProcessor
 * member.
 *
 * This RequestProcessor counts on ZooKeeperServer to populate the
 * outstandingRequests member of ZooKeeperServer.
 */
public class FinalRequestProcessor implements RequestProcessor {
    private static final Logger LOG = Logger.getLogger(FinalRequestProcessor.class);

    ZooKeeperServer zks;

    public FinalRequestProcessor(ZooKeeperServer zks) {
        this.zks = zks;
    }

    public void processRequest(Request request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing request:: " + request);
        }
        // request.addRQRec(">final");
        long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
        if (request.type == OpCode.ping) {
            traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
        }
        ZooTrace.logRequest(LOG, traceMask, 'E', request, "");
        ProcessTxnResult rc = null;
        synchronized (zks.outstandingChanges) {
            while (!zks.outstandingChanges.isEmpty()
                    && zks.outstandingChanges.get(0).zxid <= request.zxid) {
                if (zks.outstandingChanges.get(0).zxid < request.zxid) {
                    LOG.warn("Zxid outstanding "
                            + zks.outstandingChanges.get(0).zxid
                            + " is less than current " + request.zxid);
                }
                zks.outstandingChanges.remove(0);
            }
            if (request.hdr != null) {
                rc = zks.dataTree.processTxn(request.hdr, request.txn);
                if (request.type == OpCode.createSession) {
                    if (request.txn instanceof CreateSessionTxn) {
                        CreateSessionTxn cst = (CreateSessionTxn) request.txn;
                        zks.sessionTracker.addSession(request.sessionId, cst
                                .getTimeOut());
                    } else {
                        LOG.warn("*****>>>>> Got "
                                + request.txn.getClass() + " "
                                + request.txn.toString());
                    }
                } else if (request.type == OpCode.closeSession) {
                    zks.sessionTracker.removeSession(request.sessionId);
                }
            }
            // do not add non quorum packets to the queue.
            if (Request.isQuorum(request.type)) {
                zks.addCommittedProposal(request);
            }
        }

        if (request.hdr != null && request.hdr.getType() == OpCode.closeSession) {
            Factory scxn = zks.getServerCnxnFactory();
            // this might be possible since
            // we might just be playing diffs from the leader
            if (scxn != null) {
                scxn.closeSession(request.sessionId);
            }
        }

        if (request.cnxn == null) {
            return;
        }
        zks.decInProcess();
        int err = 0;
        Record rsp = null;
        try {
            if (request.hdr != null && request.hdr.getType() == OpCode.error) {
                throw KeeperException.create(((ErrorTxn) request.txn).getErr());
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug(request);
            }
            switch (request.type) {
            case OpCode.ping:
                request.cnxn.sendResponse(new ReplyHeader(-2,
                        zks.dataTree.lastProcessedZxid, 0), null, "response");
                return;
            case OpCode.createSession:
                request.cnxn.finishSessionInit(true);
                return;
            case OpCode.create:
                rsp = new CreateResponse(rc.path);
                err = rc.err;
                break;
            case OpCode.delete:
                err = rc.err;
                break;
            case OpCode.setData:
                rsp = new SetDataResponse(rc.stat);
                err = rc.err;
                break;
            case OpCode.setACL:
                rsp = new SetACLResponse(rc.stat);
                err = rc.err;
                break;
            case OpCode.closeSession:
                err = rc.err;
                break;
            case OpCode.sync:
                SyncRequest syncRequest = new SyncRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        syncRequest);
                rsp = new SyncResponse(syncRequest.getPath());
                break;
            case OpCode.exists:
                // TODO we need to figure out the security requirement for this!
                ExistsRequest existsRequest = new ExistsRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        existsRequest);
                String path = existsRequest.getPath();
                if (path.indexOf('\0') != -1) {
                    throw new KeeperException.BadArgumentsException();
                }
                Stat stat = zks.dataTree.statNode(path, existsRequest
                        .getWatch() ? request.cnxn : null);
                rsp = new ExistsResponse(stat);
                break;
            case OpCode.getData:
                GetDataRequest getDataRequest = new GetDataRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        getDataRequest);
                DataNode n = zks.dataTree.getNode(getDataRequest.getPath());
                if (n == null) {
                    throw new KeeperException.NoNodeException();
                }
                PrepRequestProcessor.checkACL(zks, zks.dataTree.convertLong(n.acl),
                        ZooDefs.Perms.READ,
                        request.authInfo);
                stat = new Stat();
                byte b[] = zks.dataTree.getData(getDataRequest.getPath(), stat,
                        getDataRequest.getWatch() ? request.cnxn : null);
                rsp = new GetDataResponse(b, stat);
                break;
            case OpCode.setWatches:
                SetWatches setWatches = new SetWatches();
                // XXX We really should NOT need this!!!!
                request.request.rewind();
                ZooKeeperServer.byteBuffer2Record(request.request, setWatches);
                long relativeZxid = setWatches.getRelativeZxid();
                zks.dataTree.setWatches(relativeZxid, 
                        setWatches.getDataWatches(), 
                        setWatches.getExistWatches(),
                        setWatches.getChildWatches(), request.cnxn);
                break;
            case OpCode.getACL:
                GetACLRequest getACLRequest = new GetACLRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        getACLRequest);
                stat = new Stat();
                List<ACL> acl = 
                    zks.dataTree.getACL(getACLRequest.getPath(), stat);
                rsp = new GetACLResponse(acl, stat);
                break;
            case OpCode.getChildren:
                GetChildrenRequest getChildrenRequest = new GetChildrenRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        getChildrenRequest);
                stat = new Stat();
                n = zks.dataTree.getNode(getChildrenRequest.getPath());
                if (n == null) {
                    throw new KeeperException.NoNodeException();
                }
                PrepRequestProcessor.checkACL(zks, zks.dataTree.convertLong(n.acl), 
                        ZooDefs.Perms.READ,
                        request.authInfo);
                List<String> children = zks.dataTree.getChildren(
                        getChildrenRequest.getPath(), stat, getChildrenRequest
                                .getWatch() ? request.cnxn : null);
                rsp = new GetChildrenResponse(children);
                break;
            }
        } catch (KeeperException e) {
            err = e.getCode();
        } catch (Exception e) {
            LOG.warn("****************************** " + request);
            StringBuffer sb = new StringBuffer();
            ByteBuffer bb = request.request;
            bb.rewind();
            while (bb.hasRemaining()) {
                sb.append(Integer.toHexString(bb.get() & 0xff));
            }
            LOG.warn(sb.toString());
            LOG.error("FIXMSG",e);
            err = Code.MarshallingError;
        }
        ReplyHeader hdr = new ReplyHeader(request.cxid, request.zxid, err);
        ServerStats.getInstance().updateLatency(request.createTime);
        try {
            request.cnxn.sendResponse(hdr, rsp, "response");
        } catch (IOException e) {
            LOG.error("FIXMSG",e);
        }
    }

    public void shutdown() {
        // we are the final link in the chain
        LOG.info("shutdown of request processor complete");
    }

}
