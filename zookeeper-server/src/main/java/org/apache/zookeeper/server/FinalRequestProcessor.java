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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.SessionMovedException;
import org.apache.zookeeper.MultiOperationRecord;
import org.apache.zookeeper.MultiResponse;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.OpResult.CheckResult;
import org.apache.zookeeper.OpResult.CreateResult;
import org.apache.zookeeper.OpResult.DeleteResult;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.OpResult.GetChildrenResult;
import org.apache.zookeeper.OpResult.GetDataResult;
import org.apache.zookeeper.OpResult.SetDataResult;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.ZKAuditLogger;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CheckWatchesRequest;
import org.apache.zookeeper.proto.Create2Response;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.ExistsRequest;
import org.apache.zookeeper.proto.ExistsResponse;
import org.apache.zookeeper.proto.GetACLRequest;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.GetAllChildrenNumberRequest;
import org.apache.zookeeper.proto.GetAllChildrenNumberResponse;
import org.apache.zookeeper.proto.GetChildren2Request;
import org.apache.zookeeper.proto.GetChildren2Response;
import org.apache.zookeeper.proto.GetChildrenRequest;
import org.apache.zookeeper.proto.GetChildrenResponse;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.proto.GetDataResponse;
import org.apache.zookeeper.proto.GetEphemeralsRequest;
import org.apache.zookeeper.proto.GetEphemeralsResponse;
import org.apache.zookeeper.proto.RemoveWatchesRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetACLResponse;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.proto.SyncRequest;
import org.apache.zookeeper.proto.SyncResponse;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.quorum.QuorumZooKeeperServer;
import org.apache.zookeeper.server.util.RequestPathMetricsCollector;
import org.apache.zookeeper.txn.ErrorTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(FinalRequestProcessor.class);

    private final RequestPathMetricsCollector requestPathMetricsCollector;

    ZooKeeperServer zks;

    public FinalRequestProcessor(ZooKeeperServer zks) {
        this.zks = zks;
        this.requestPathMetricsCollector = zks.getRequestPathMetricsCollector();
    }

    public void processRequest(Request request) {
        LOG.debug("Processing request:: {}", request);

        // request.addRQRec(">final");
        long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
        if (request.type == OpCode.ping) {
            traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
        }
        if (LOG.isTraceEnabled()) {
            ZooTrace.logRequest(LOG, traceMask, 'E', request, "");
        }

        ProcessTxnResult rc = zks.processTxn(request);

        // ZOOKEEPER-558:
        // In some cases the server does not close the connection (e.g., closeconn buffer
        // was not being queued — ZOOKEEPER-558) properly. This happens, for example,
        // when the client closes the connection. The server should still close the session, though.
        // Calling closeSession() after losing the cnxn, results in the client close session response being dropped.
        if (request.type == OpCode.closeSession && connClosedByClient(request)) {
            // We need to check if we can close the session id.
            // Sometimes the corresponding ServerCnxnFactory could be null because
            // we are just playing diffs from the leader.
            if (closeSession(zks.serverCnxnFactory, request.sessionId)
                || closeSession(zks.secureServerCnxnFactory, request.sessionId)) {
                return;
            }
        }

        if (request.getHdr() != null) {
            /*
             * Request header is created only by the leader, so this must be
             * a quorum request. Since we're comparing timestamps across hosts,
             * this metric may be incorrect. However, it's still a very useful
             * metric to track in the happy case. If there is clock drift,
             * the latency can go negative. Note: headers use wall time, not
             * CLOCK_MONOTONIC.
             */
            long propagationLatency = Time.currentWallTime() - request.getHdr().getTime();
            if (propagationLatency >= 0) {
                ServerMetrics.getMetrics().PROPAGATION_LATENCY.add(propagationLatency);
            }
        }

        if (request.cnxn == null) {
            return;
        }
        ServerCnxn cnxn = request.cnxn;

        long lastZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();

        String lastOp = "NA";
        // Notify ZooKeeperServer that the request has finished so that it can
        // update any request accounting/throttling limits
        zks.decInProcess();
        zks.requestFinished(request);
        Code err = Code.OK;
        Record rsp = null;
        String path = null;
        try {
            if (request.getHdr() != null && request.getHdr().getType() == OpCode.error) {
                addFailedTxnAuditLog(request);
                /*
                 * When local session upgrading is disabled, leader will
                 * reject the ephemeral node creation due to session expire.
                 * However, if this is the follower that issue the request,
                 * it will have the correct error code, so we should use that
                 * and report to user
                 */
                if (request.getException() != null) {
                    throw request.getException();
                } else {
                    throw KeeperException.create(KeeperException.Code.get(((ErrorTxn) request.getTxn()).getErr()));
                }
            }

            KeeperException ke = request.getException();
            if (ke instanceof SessionMovedException) {
                throw ke;
            }
            if (ke != null && request.type != OpCode.multi) {
                throw ke;
            }

            LOG.debug("{}", request);

            if (request.isStale()) {
                ServerMetrics.getMetrics().STALE_REPLIES.add(1);
            }

            switch (request.type) {
            case OpCode.ping: {
                lastOp = "PING";
                updateStats(request, lastOp, lastZxid);

                cnxn.sendResponse(new ReplyHeader(-2, lastZxid, 0), null, "response");
                return;
            }
            case OpCode.createSession: {
                lastOp = "SESS";
                updateStats(request, lastOp, lastZxid);

                zks.finishSessionInit(request.cnxn, true);
                return;
            }
            case OpCode.multi: {
                lastOp = "MULT";
                rsp = new MultiResponse();
                Map<String, String> createModes = getCreateModes(request);
                boolean multiFailed = false;

                for (ProcessTxnResult subTxnResult : rc.multiResult) {

                    OpResult subResult;

                    switch (subTxnResult.type) {
                    case OpCode.check:
                        subResult = new CheckResult();
                        break;
                    case OpCode.create:
                        subResult = new CreateResult(subTxnResult.path);
                        addSuccessAudit(request, cnxn, AuditConstants.OP_CREATE, subTxnResult.path,
                                null, createModes.get(subTxnResult.path));
                        break;
                    case OpCode.create2:
                    case OpCode.createTTL:
                    case OpCode.createContainer:
                        subResult = new CreateResult(subTxnResult.path, subTxnResult.stat);
                        addSuccessAudit(request, cnxn, AuditConstants.OP_CREATE, subTxnResult.path,
                                null, createModes.get(subTxnResult.path));
                        break;
                    case OpCode.delete:
                    case OpCode.deleteContainer:
                        subResult = new DeleteResult();
                        addSuccessAudit(request, cnxn, AuditConstants.OP_DELETE, subTxnResult.path);
                        break;
                    case OpCode.setData:
                        subResult = new SetDataResult(subTxnResult.stat);
                        addSuccessAudit(request, cnxn, AuditConstants.OP_SETDATA, subTxnResult.path);
                        break;
                    case OpCode.error:
                        subResult = new ErrorResult(subTxnResult.err);
                        multiFailed = true;
                        if (subTxnResult.err == Code.SESSIONMOVED.intValue()) {
                            addFailureAudit(request, cnxn, AuditConstants.OP_MULTI_OP, null);
                            throw new SessionMovedException();
                        }
                        break;
                    default:
                        addFailureAudit(request, cnxn, AuditConstants.OP_MULTI_OP, null);
                        throw new IOException("Invalid type of op");
                    }

                    ((MultiResponse) rsp).add(subResult);
                }

                if (multiFailed) {
                    addFailureAudit(request, cnxn, AuditConstants.OP_MULTI_OP, null);
                }

                break;
            }
            case OpCode.multiRead: {
                lastOp = "MLTR";
                MultiOperationRecord multiReadRecord = new MultiOperationRecord();
                ByteBufferInputStream.byteBuffer2Record(request.request, multiReadRecord);
                rsp = new MultiResponse();
                OpResult subResult;
                for (Op readOp : multiReadRecord) {
                    try {
                        Record rec;
                        switch (readOp.getType()) {
                        case OpCode.getChildren:
                            rec = handleGetChildrenRequest(readOp.toRequestRecord(), cnxn, request.authInfo);
                            subResult = new GetChildrenResult(((GetChildrenResponse) rec).getChildren());
                            break;
                        case OpCode.getData:
                            rec = handleGetDataRequest(readOp.toRequestRecord(), cnxn, request.authInfo);
                            GetDataResponse gdr = (GetDataResponse) rec;
                            subResult = new GetDataResult(gdr.getData(), gdr.getStat());
                            break;
                        default:
                            throw new IOException("Invalid type of readOp");
                        }
                    } catch (KeeperException e) {
                        subResult = new ErrorResult(e.code().intValue());
                    }
                    ((MultiResponse) rsp).add(subResult);
                }
                break;
            }
            case OpCode.create: {
                lastOp = "CREA";
                rsp = new CreateResponse(rc.path);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                String createMode = getCreateMode(request);
                addAuditLog(request, cnxn, AuditConstants.OP_CREATE, rc.path, null, createMode, err);
                break;
            }
            case OpCode.create2:
            case OpCode.createTTL:
            case OpCode.createContainer: {
                lastOp = "CREA";
                rsp = new Create2Response(rc.path, rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                String createMode = getCreateMode(request);
                addAuditLog(request, cnxn, AuditConstants.OP_CREATE, rc.path, null, createMode, err);
                break;
            }
            case OpCode.delete:
            case OpCode.deleteContainer: {
                lastOp = "DELE";
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                addAuditLog(request, cnxn, AuditConstants.OP_DELETE, rc.path, null, null, err);
                break;
            }
            case OpCode.setData: {
                lastOp = "SETD";
                rsp = new SetDataResponse(rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                addAuditLog(request, cnxn, AuditConstants.OP_SETDATA, rc.path, null, null, err);
                break;
            }
            case OpCode.reconfig: {
                lastOp = "RECO";
                rsp = new GetDataResponse(
                    ((QuorumZooKeeperServer) zks).self.getQuorumVerifier().toString().getBytes(),
                    rc.stat);
                err = Code.get(rc.err);
                addAuditLog(request, cnxn, AuditConstants.OP_RECONFIG, rc.path, null, null, err);
                break;
            }
            case OpCode.setACL: {
                lastOp = "SETA";
                rsp = new SetACLResponse(rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                /** Here audit enable check is done to avoid getACLs() call in case audit is disabled. */
                if (ZKAuditLogger.isAuditEnabled()) {
                    addAuditLog(request, cnxn, AuditConstants.OP_SETACL, rc.path, getACLs(request),
                            null, err);
                }
                break;
            }
            case OpCode.closeSession: {
                lastOp = "CLOS";
                err = Code.get(rc.err);
                break;
            }
            case OpCode.sync: {
                lastOp = "SYNC";
                SyncRequest syncRequest = new SyncRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, syncRequest);
                rsp = new SyncResponse(syncRequest.getPath());
                requestPathMetricsCollector.registerRequest(request.type, syncRequest.getPath());
                break;
            }
            case OpCode.check: {
                lastOp = "CHEC";
                rsp = new SetDataResponse(rc.stat);
                err = Code.get(rc.err);
                break;
            }
            case OpCode.exists: {
                lastOp = "EXIS";
                // TODO we need to figure out the security requirement for this!
                ExistsRequest existsRequest = new ExistsRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, existsRequest);
                path = existsRequest.getPath();
                if (path.indexOf('\0') != -1) {
                    throw new KeeperException.BadArgumentsException();
                }
                Stat stat = zks.getZKDatabase().statNode(path, existsRequest.getWatch() ? cnxn : null);
                rsp = new ExistsResponse(stat);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.getData: {
                lastOp = "GETD";
                GetDataRequest getDataRequest = new GetDataRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, getDataRequest);
                path = getDataRequest.getPath();
                rsp = handleGetDataRequest(getDataRequest, cnxn, request.authInfo);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.setWatches: {
                lastOp = "SETW";
                SetWatches setWatches = new SetWatches();
                // TODO We really should NOT need this!!!!
                request.request.rewind();
                ByteBufferInputStream.byteBuffer2Record(request.request, setWatches);
                long relativeZxid = setWatches.getRelativeZxid();
                zks.getZKDatabase()
                   .setWatches(
                       relativeZxid,
                       setWatches.getDataWatches(),
                       setWatches.getExistWatches(),
                       setWatches.getChildWatches(),
                       cnxn);
                break;
            }
            case OpCode.getACL: {
                lastOp = "GETA";
                GetACLRequest getACLRequest = new GetACLRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, getACLRequest);
                path = getACLRequest.getPath();
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n == null) {
                    throw new KeeperException.NoNodeException();
                }
                zks.checkACL(
                    request.cnxn,
                    zks.getZKDatabase().aclForNode(n),
                    ZooDefs.Perms.READ | ZooDefs.Perms.ADMIN, request.authInfo, path,
                    null);

                Stat stat = new Stat();
                List<ACL> acl = zks.getZKDatabase().getACL(path, stat);
                requestPathMetricsCollector.registerRequest(request.type, getACLRequest.getPath());

                try {
                    zks.checkACL(
                        request.cnxn,
                        zks.getZKDatabase().aclForNode(n),
                        ZooDefs.Perms.ADMIN,
                        request.authInfo,
                        path,
                        null);
                    rsp = new GetACLResponse(acl, stat);
                } catch (KeeperException.NoAuthException e) {
                    List<ACL> acl1 = new ArrayList<ACL>(acl.size());
                    for (ACL a : acl) {
                        if ("digest".equals(a.getId().getScheme())) {
                            Id id = a.getId();
                            Id id1 = new Id(id.getScheme(), id.getId().replaceAll(":.*", ":x"));
                            acl1.add(new ACL(a.getPerms(), id1));
                        } else {
                            acl1.add(a);
                        }
                    }
                    rsp = new GetACLResponse(acl1, stat);
                }
                break;
            }
            case OpCode.getChildren: {
                lastOp = "GETC";
                GetChildrenRequest getChildrenRequest = new GetChildrenRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, getChildrenRequest);
                path = getChildrenRequest.getPath();
                rsp = handleGetChildrenRequest(getChildrenRequest, cnxn, request.authInfo);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.getAllChildrenNumber: {
                lastOp = "GETACN";
                GetAllChildrenNumberRequest getAllChildrenNumberRequest = new GetAllChildrenNumberRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, getAllChildrenNumberRequest);
                path = getAllChildrenNumberRequest.getPath();
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n == null) {
                    throw new KeeperException.NoNodeException();
                }
                zks.checkACL(
                    request.cnxn,
                    zks.getZKDatabase().aclForNode(n),
                    ZooDefs.Perms.READ,
                    request.authInfo,
                    path,
                    null);
                int number = zks.getZKDatabase().getAllChildrenNumber(path);
                rsp = new GetAllChildrenNumberResponse(number);
                break;
            }
            case OpCode.getChildren2: {
                lastOp = "GETC";
                GetChildren2Request getChildren2Request = new GetChildren2Request();
                ByteBufferInputStream.byteBuffer2Record(request.request, getChildren2Request);
                Stat stat = new Stat();
                path = getChildren2Request.getPath();
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n == null) {
                    throw new KeeperException.NoNodeException();
                }
                zks.checkACL(
                    request.cnxn,
                    zks.getZKDatabase().aclForNode(n),
                    ZooDefs.Perms.READ,
                    request.authInfo, path,
                    null);
                List<String> children = zks.getZKDatabase()
                                           .getChildren(path, stat, getChildren2Request.getWatch() ? cnxn : null);
                rsp = new GetChildren2Response(children, stat);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.checkWatches: {
                lastOp = "CHKW";
                CheckWatchesRequest checkWatches = new CheckWatchesRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, checkWatches);
                WatcherType type = WatcherType.fromInt(checkWatches.getType());
                path = checkWatches.getPath();
                boolean containsWatcher = zks.getZKDatabase().containsWatcher(path, type, cnxn);
                if (!containsWatcher) {
                    String msg = String.format(Locale.ENGLISH, "%s (type: %s)", path, type);
                    throw new KeeperException.NoWatcherException(msg);
                }
                requestPathMetricsCollector.registerRequest(request.type, checkWatches.getPath());
                break;
            }
            case OpCode.removeWatches: {
                lastOp = "REMW";
                RemoveWatchesRequest removeWatches = new RemoveWatchesRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, removeWatches);
                WatcherType type = WatcherType.fromInt(removeWatches.getType());
                path = removeWatches.getPath();
                boolean removed = zks.getZKDatabase().removeWatch(path, type, cnxn);
                if (!removed) {
                    String msg = String.format(Locale.ENGLISH, "%s (type: %s)", path, type);
                    throw new KeeperException.NoWatcherException(msg);
                }
                requestPathMetricsCollector.registerRequest(request.type, removeWatches.getPath());
                break;
            }
            case OpCode.getEphemerals: {
                lastOp = "GETE";
                GetEphemeralsRequest getEphemerals = new GetEphemeralsRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, getEphemerals);
                String prefixPath = getEphemerals.getPrefixPath();
                Set<String> allEphems = zks.getZKDatabase().getDataTree().getEphemerals(request.sessionId);
                List<String> ephemerals = new ArrayList<>();
                if (StringUtils.isBlank(prefixPath) || "/".equals(prefixPath.trim())) {
                    ephemerals.addAll(allEphems);
                } else {
                    for (String p : allEphems) {
                        if (p.startsWith(prefixPath)) {
                            ephemerals.add(p);
                        }
                    }
                }
                rsp = new GetEphemeralsResponse(ephemerals);
                break;
            }
            }
        } catch (SessionMovedException e) {
            // session moved is a connection level error, we need to tear
            // down the connection otw ZOOKEEPER-710 might happen
            // ie client on slow follower starts to renew session, fails
            // before this completes, then tries the fast follower (leader)
            // and is successful, however the initial renew is then
            // successfully fwd/processed by the leader and as a result
            // the client and leader disagree on where the client is most
            // recently attached (and therefore invalid SESSION MOVED generated)
            cnxn.sendCloseSession();
            return;
        } catch (KeeperException e) {
            err = e.code();
        } catch (Exception e) {
            // log at error level as we are returning a marshalling
            // error to the user
            LOG.error("Failed to process {}", request, e);
            StringBuilder sb = new StringBuilder();
            ByteBuffer bb = request.request;
            bb.rewind();
            while (bb.hasRemaining()) {
                sb.append(Integer.toHexString(bb.get() & 0xff));
            }
            LOG.error("Dumping request buffer: 0x{}", sb.toString());
            err = Code.MARSHALLINGERROR;
        }

        ReplyHeader hdr = new ReplyHeader(request.cxid, lastZxid, err.intValue());

        updateStats(request, lastOp, lastZxid);

        try {
            if (request.type == OpCode.getData && path != null && rsp != null) {
                // Serialized read responses could be cached by the connection object.
                // Cache entries are identified by their path and last modified zxid,
                // so these values are passed along with the response.
                GetDataResponse getDataResponse = (GetDataResponse) rsp;
                Stat stat = null;
                if (getDataResponse.getStat() != null) {
                    stat = getDataResponse.getStat();
                }
                cnxn.sendResponse(hdr, rsp, "response", path, stat);
            } else {
                cnxn.sendResponse(hdr, rsp, "response");
            }
            if (request.type == OpCode.closeSession) {
                cnxn.sendCloseSession();
            }
        } catch (IOException e) {
            LOG.error("FIXMSG", e);
        }
    }

    private Record handleGetChildrenRequest(Record request, ServerCnxn cnxn, List<Id> authInfo) throws KeeperException, IOException {
        GetChildrenRequest getChildrenRequest = (GetChildrenRequest) request;
        String path = getChildrenRequest.getPath();
        DataNode n = zks.getZKDatabase().getNode(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        zks.checkACL(cnxn, zks.getZKDatabase().aclForNode(n), ZooDefs.Perms.READ, authInfo, path, null);
        List<String> children = zks.getZKDatabase()
                                   .getChildren(path, null, getChildrenRequest.getWatch() ? cnxn : null);
        return new GetChildrenResponse(children);
    }

    private Record handleGetDataRequest(Record request, ServerCnxn cnxn, List<Id> authInfo) throws KeeperException, IOException {
        GetDataRequest getDataRequest = (GetDataRequest) request;
        String path = getDataRequest.getPath();
        DataNode n = zks.getZKDatabase().getNode(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        zks.checkACL(cnxn, zks.getZKDatabase().aclForNode(n), ZooDefs.Perms.READ, authInfo, path, null);
        Stat stat = new Stat();
        byte[] b = zks.getZKDatabase().getData(path, stat, getDataRequest.getWatch() ? cnxn : null);
        return new GetDataResponse(b, stat);
    }

    private boolean closeSession(ServerCnxnFactory serverCnxnFactory, long sessionId) {
        if (serverCnxnFactory == null) {
            return false;
        }
        return serverCnxnFactory.closeSession(sessionId, ServerCnxn.DisconnectReason.CLIENT_CLOSED_SESSION);
    }

    private boolean connClosedByClient(Request request) {
        return request.cnxn == null;
    }

    public void shutdown() {
        // we are the final link in the chain
        LOG.info("shutdown of request processor complete");
    }

    private void updateStats(Request request, String lastOp, long lastZxid) {
        if (request.cnxn == null) {
            return;
        }
        long currentTime = Time.currentElapsedTime();
        zks.serverStats().updateLatency(request, currentTime);
        request.cnxn.updateStatsForResponse(request.cxid, lastZxid, lastOp, request.createTime, currentTime);
    }

    private void addSuccessAudit(Request request, ServerCnxn cnxn, String op, String path) {
        addSuccessAudit(request, cnxn, op, path, null, null);
    }

    private void addSuccessAudit(Request request, ServerCnxn cnxn, String op, String path,
                                 String acl, String createMode) {
        if (!ZKAuditLogger.isAuditEnabled()) {
            return;
        }
        ZKAuditLogger
                .logSuccess(request.getUsers(), op, path, acl, createMode, cnxn.getSessionIdHex(),
                        cnxn.getHostAddress());
    }

    private void addFailureAudit(Request request, ServerCnxn cnxn, String op, String path) {
        addFailureAudit(request, cnxn, op, path, null, null);
    }

    private void addFailureAudit(Request request, ServerCnxn cnxn, String op, String path,
                                 String acl, String createMode) {
        if (!ZKAuditLogger.isAuditEnabled()) {
            return;
        }
        ZKAuditLogger
                .logFailure(request.getUsers(), op, path, acl, createMode, cnxn.getSessionIdHex(),
                        cnxn.getHostAddress());
    }

    private void addAuditLog(Request request, ServerCnxn cnxn, String op, String path, String acl,
                             String createMode, Code err) {
        if (!ZKAuditLogger.isAuditEnabled()) {
            return;
        }
        if (err == Code.OK) {
            ZKAuditLogger
                    .logSuccess(request.getUsers(), op, path, acl, createMode, cnxn.getSessionIdHex(),
                            cnxn.getHostAddress());
        } else {
            ZKAuditLogger
                    .logFailure(request.getUsers(), op, path, acl, createMode, cnxn.getSessionIdHex(),
                            cnxn.getHostAddress());
        }
    }

    private String getACLs(Request request) {
        ByteBuffer reqData = request.request.slice();
        reqData.rewind();
        SetACLRequest setACLRequest = new SetACLRequest();
        try {
            ByteBufferInputStream.byteBuffer2Record(reqData, setACLRequest);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ZKUtil.aclToString(setACLRequest.getAcl());
    }

    private void addFailedTxnAuditLog(Request request) {
        if (!ZKAuditLogger.isAuditEnabled()) {
            return;
        }
        String op = AuditConstants.OP_CREATE;
        String path = null;
        long sessionId = -1;
        String address = null;
        String acls = null;
        String createMode = null;
        ByteBuffer reqData = request.request.slice();
        reqData.rewind();
        try {
            sessionId = request.cnxn.getSessionId();
            switch (request.type) {
                case OpCode.create:
                case OpCode.create2:
                case OpCode.createContainer:
                    op = AuditConstants.OP_CREATE;
                    CreateRequest createRequest = new CreateRequest();
                    ByteBufferInputStream.byteBuffer2Record(reqData, createRequest);
                    path = createRequest.getPath();
                    createMode =
                            CreateMode.fromFlag(createRequest.getFlags()).toString().toLowerCase();
                    break;
                case OpCode.delete:
                case OpCode.deleteContainer:
                    op = AuditConstants.OP_DELETE;
                    DeleteRequest deleteRequest = new DeleteRequest();
                    ByteBufferInputStream.byteBuffer2Record(reqData, deleteRequest);
                    path = deleteRequest.getPath();
                    break;
                case OpCode.setData:
                    op = AuditConstants.OP_SETDATA;
                    SetDataRequest setDataRequest = new SetDataRequest();
                    ByteBufferInputStream.byteBuffer2Record(reqData, setDataRequest);
                    path = setDataRequest.getPath();
                    break;
                case OpCode.setACL:
                    op = AuditConstants.OP_SETACL;
                    SetACLRequest setACLRequest = new SetACLRequest();
                    ByteBufferInputStream.byteBuffer2Record(reqData, setACLRequest);
                    path = setACLRequest.getPath();
                    acls = ZKUtil.aclToString(setACLRequest.getAcl());
                    break;
                case OpCode.multi:
                    op = AuditConstants.OP_MULTI_OP;
                    break;
                case OpCode.reconfig:
                    op = AuditConstants.OP_RECONFIG;
                    break;
                default:
                    // This should not happen as audit is logged only from known
                    // operations. This is added to fix find bug issue.
                    op = "Unknown";
                    break;
            }

            if (request.cnxn.getRemoteSocketAddress() != null
                    && request.cnxn.getRemoteSocketAddress().getAddress() != null) {
                address = request.cnxn.getRemoteSocketAddress().getAddress().getHostAddress();
            }
            ZKAuditLogger.logFailure(request.getUsers(), op, path, acls, createMode,
                    "0x" + Long.toHexString(sessionId), address);
        } catch (Throwable e) {
            LOG.error("Failed to audit log request {} failure", request.type, e);
        }
    }

    private String getCreateMode(Request request) throws IOException, KeeperException {
        request.request.rewind();
        CreateRequest createRequest = new CreateRequest();
        ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
        request.request.rewind();
        String lowerCase = CreateMode.fromFlag(createRequest.getFlags()).toString().toLowerCase();
        return lowerCase;
    }

    private Map<String, String> getCreateModes(Request request)
            throws IOException, KeeperException {
        Map<String, String> createModes = new HashMap<String, String>();
        // set the buffer position to 0.
        request.request.rewind();
        MultiOperationRecord multiRequest = new MultiOperationRecord();
        ByteBufferInputStream.byteBuffer2Record(request.request, multiRequest);
        // reset the buffer position to zero
        request.request.rewind();
        for (Op op : multiRequest) {
            if (op.getType() == ZooDefs.OpCode.create || op.getType() == ZooDefs.OpCode.create2
                    || op.getType() == ZooDefs.OpCode.createContainer) {
                CreateRequest requestRecord = (CreateRequest) op.toRequestRecord();
                createModes.put(requestRecord.getPath(),
                        CreateMode.fromFlag(requestRecord.getFlags()).toString().toLowerCase());
            }
        }
        return createModes;
    }
}
