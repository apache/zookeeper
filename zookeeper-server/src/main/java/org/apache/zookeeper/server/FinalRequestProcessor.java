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

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.jute.Record;
import org.apache.zookeeper.ClientCnxn;
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
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.audit.AuditHelper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.AddWatchRequest;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.proto.CheckWatchesRequest;
import org.apache.zookeeper.proto.Create2Response;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.ErrorResponse;
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
import org.apache.zookeeper.proto.SetACLResponse;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.proto.SetWatches2;
import org.apache.zookeeper.proto.SyncRequest;
import org.apache.zookeeper.proto.SyncResponse;
import org.apache.zookeeper.proto.WhoAmIResponse;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.quorum.QuorumZooKeeperServer;
import org.apache.zookeeper.server.util.AuthUtil;
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

    private ProcessTxnResult applyRequest(Request request) {
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
                return rc;
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

        return rc;
    }

    public void processRequest(Request request) {
        LOG.debug("Processing request:: {}", request);

        if (LOG.isTraceEnabled()) {
            long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
            if (request.type == OpCode.ping) {
                traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
            }
            ZooTrace.logRequest(LOG, traceMask, 'E', request, "");
        }
        ProcessTxnResult rc = null;
        if (!request.isThrottled()) {
          rc = applyRequest(request);
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
        int responseSize = 0;
        try {
            if (request.getHdr() != null && request.getHdr().getType() == OpCode.error) {
                AuditHelper.addAuditLog(request, rc, true);
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

            if (request.isThrottled()) {
              throw KeeperException.create(Code.THROTTLEDOP);
            }

            AuditHelper.addAuditLog(request, rc);

            switch (request.type) {
            case OpCode.ping: {
                lastOp = "PING";
                updateStats(request, lastOp, lastZxid);

                responseSize = cnxn.sendResponse(new ReplyHeader(ClientCnxn.PING_XID, lastZxid, 0), null, "response");
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

                for (ProcessTxnResult subTxnResult : rc.multiResult) {

                    OpResult subResult;

                    switch (subTxnResult.type) {
                    case OpCode.check:
                        subResult = new CheckResult();
                        break;
                    case OpCode.create:
                        subResult = new CreateResult(subTxnResult.path);
                        break;
                    case OpCode.create2:
                    case OpCode.createTTL:
                    case OpCode.createContainer:
                        subResult = new CreateResult(subTxnResult.path, subTxnResult.stat);
                        break;
                    case OpCode.delete:
                    case OpCode.deleteContainer:
                        subResult = new DeleteResult();
                        break;
                    case OpCode.setData:
                        subResult = new SetDataResult(subTxnResult.stat);
                        break;
                    case OpCode.error:
                        subResult = new ErrorResult(subTxnResult.err);
                        if (subTxnResult.err == Code.SESSIONMOVED.intValue()) {
                            throw new SessionMovedException();
                        }
                        break;
                    default:
                        throw new IOException("Invalid type of op");
                    }

                    ((MultiResponse) rsp).add(subResult);
                }

                break;
            }
            case OpCode.multiRead: {
                lastOp = "MLTR";
                MultiOperationRecord multiReadRecord = request.readRequestRecord(MultiOperationRecord::new);
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
                break;
            }
            case OpCode.create2:
            case OpCode.createTTL:
            case OpCode.createContainer: {
                lastOp = "CREA";
                rsp = new Create2Response(rc.path, rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                break;
            }
            case OpCode.delete:
            case OpCode.deleteContainer: {
                lastOp = "DELE";
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                break;
            }
            case OpCode.setData: {
                lastOp = "SETD";
                rsp = new SetDataResponse(rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                break;
            }
            case OpCode.reconfig: {
                lastOp = "RECO";
                rsp = new GetDataResponse(
                    ((QuorumZooKeeperServer) zks).self.getQuorumVerifier().toString().getBytes(UTF_8),
                    rc.stat);
                err = Code.get(rc.err);
                break;
            }
            case OpCode.setACL: {
                lastOp = "SETA";
                rsp = new SetACLResponse(rc.stat);
                err = Code.get(rc.err);
                requestPathMetricsCollector.registerRequest(request.type, rc.path);
                break;
            }
            case OpCode.closeSession: {
                lastOp = "CLOS";
                err = Code.get(rc.err);
                break;
            }
            case OpCode.sync: {
                lastOp = "SYNC";
                SyncRequest syncRequest = request.readRequestRecord(SyncRequest::new);
                rsp = new SyncResponse(syncRequest.getPath());
                requestPathMetricsCollector.registerRequest(request.type, syncRequest.getPath());
                break;
            }
            case OpCode.check: {
                lastOp = "CHEC";
                CheckVersionRequest checkVersionRequest = request.readRequestRecord(CheckVersionRequest::new);
                path = checkVersionRequest.getPath();
                handleCheckVersionRequest(checkVersionRequest, cnxn, request.authInfo);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.exists: {
                lastOp = "EXIS";
                ExistsRequest existsRequest = request.readRequestRecord(ExistsRequest::new);
                path = existsRequest.getPath();
                if (path.indexOf('\0') != -1) {
                    throw new KeeperException.BadArgumentsException();
                }
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n != null) {
                    zks.checkACL(
                        request.cnxn,
                        zks.getZKDatabase().aclForNode(n),
                        ZooDefs.Perms.READ,
                        request.authInfo,
                        path,
                        null);
                }
                Stat stat = zks.getZKDatabase().statNode(path, existsRequest.getWatch() ? cnxn : null);
                rsp = new ExistsResponse(stat);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.getData: {
                lastOp = "GETD";
                GetDataRequest getDataRequest = request.readRequestRecord(GetDataRequest::new);
                path = getDataRequest.getPath();
                rsp = handleGetDataRequest(getDataRequest, cnxn, request.authInfo);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.setWatches: {
                lastOp = "SETW";
                SetWatches setWatches = request.readRequestRecord(SetWatches::new);
                long relativeZxid = setWatches.getRelativeZxid();
                zks.getZKDatabase()
                   .setWatches(
                       relativeZxid,
                       setWatches.getDataWatches(),
                       setWatches.getExistWatches(),
                       setWatches.getChildWatches(),
                       Collections.emptyList(),
                       Collections.emptyList(),
                       cnxn);
                break;
            }
            case OpCode.setWatches2: {
                lastOp = "STW2";
                SetWatches2 setWatches = request.readRequestRecord(SetWatches2::new);
                long relativeZxid = setWatches.getRelativeZxid();
                zks.getZKDatabase().setWatches(relativeZxid,
                        setWatches.getDataWatches(),
                        setWatches.getExistWatches(),
                        setWatches.getChildWatches(),
                        setWatches.getPersistentWatches(),
                        setWatches.getPersistentRecursiveWatches(),
                        cnxn);
                break;
            }
            case OpCode.addWatch: {
                lastOp = "ADDW";
                AddWatchRequest addWatcherRequest = request.readRequestRecord(AddWatchRequest::new);
                zks.getZKDatabase().addWatch(addWatcherRequest.getPath(), cnxn, addWatcherRequest.getMode());
                rsp = new ErrorResponse(0);
                break;
            }
            case OpCode.getACL: {
                lastOp = "GETA";
                GetACLRequest getACLRequest = request.readRequestRecord(GetACLRequest::new);
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
                    List<ACL> acl1 = new ArrayList<>(acl.size());
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
                GetChildrenRequest getChildrenRequest = request.readRequestRecord(GetChildrenRequest::new);
                path = getChildrenRequest.getPath();
                rsp = handleGetChildrenRequest(getChildrenRequest, cnxn, request.authInfo);
                requestPathMetricsCollector.registerRequest(request.type, path);
                break;
            }
            case OpCode.getAllChildrenNumber: {
                lastOp = "GETACN";
                GetAllChildrenNumberRequest getAllChildrenNumberRequest = request.readRequestRecord(GetAllChildrenNumberRequest::new);
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
                GetChildren2Request getChildren2Request = request.readRequestRecord(GetChildren2Request::new);
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
                CheckWatchesRequest checkWatches = request.readRequestRecord(CheckWatchesRequest::new);
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
                RemoveWatchesRequest removeWatches = request.readRequestRecord(RemoveWatchesRequest::new);
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
            case OpCode.whoAmI: {
                lastOp = "HOMI";
                rsp = new WhoAmIResponse(AuthUtil.getClientInfos(request.authInfo));
                break;
             }
            case OpCode.getEphemerals: {
                lastOp = "GETE";
                GetEphemeralsRequest getEphemerals = request.readRequestRecord(GetEphemeralsRequest::new);
                String prefixPath = getEphemerals.getPrefixPath();
                Set<String> allEphems = zks.getZKDatabase().getDataTree().getEphemerals(request.sessionId);
                List<String> ephemerals = new ArrayList<>();
                if (prefixPath == null || prefixPath.trim().isEmpty() || "/".equals(prefixPath.trim())) {
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
            String digest = request.requestDigest();
            LOG.error("Dumping request buffer for request type {}: 0x{}", Request.op2String(request.type), digest);
            err = Code.MARSHALLINGERROR;
        }

        ReplyHeader hdr = new ReplyHeader(request.cxid, lastZxid, err.intValue());

        updateStats(request, lastOp, lastZxid);

        try {
            if (path == null || rsp == null) {
                responseSize = cnxn.sendResponse(hdr, rsp, "response");
            } else {
                int opCode = request.type;
                Stat stat = null;
                // Serialized read and get children responses could be cached by the connection
                // object. Cache entries are identified by their path and last modified zxid,
                // so these values are passed along with the response.
                switch (opCode) {
                    case OpCode.getData : {
                        GetDataResponse getDataResponse = (GetDataResponse) rsp;
                        stat = getDataResponse.getStat();
                        responseSize = cnxn.sendResponse(hdr, rsp, "response", path, stat, opCode);
                        break;
                    }
                    case OpCode.getChildren2 : {
                        GetChildren2Response getChildren2Response = (GetChildren2Response) rsp;
                        stat = getChildren2Response.getStat();
                        responseSize = cnxn.sendResponse(hdr, rsp, "response", path, stat, opCode);
                        break;
                    }
                    default:
                        responseSize = cnxn.sendResponse(hdr, rsp, "response");
                }
            }

            if (request.type == OpCode.closeSession) {
                cnxn.sendCloseSession();
            }
        } catch (IOException e) {
            LOG.error("FIXMSG", e);
        } finally {
            ServerMetrics.getMetrics().RESPONSE_BYTES.add(responseSize);
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

    private void handleCheckVersionRequest(CheckVersionRequest request, ServerCnxn cnxn, List<Id> authInfo) throws KeeperException {
        String path = request.getPath();
        DataNode n = zks.getZKDatabase().getNode(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        zks.checkACL(cnxn, zks.getZKDatabase().aclForNode(n), ZooDefs.Perms.READ, authInfo, path, null);
        int version = request.getVersion();
        if (version != -1 && version != n.stat.getVersion()) {
            throw new KeeperException.BadVersionException(path);
        }
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

}
