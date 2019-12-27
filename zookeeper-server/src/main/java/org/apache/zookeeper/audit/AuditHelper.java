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
package org.apache.zookeeper.audit;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.MultiOperationRecord;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to decouple audit log code.
 */
public final class AuditHelper {
    private static final Logger LOG = LoggerFactory.getLogger(AuditHelper.class);

    public static void addAuditLog(Request request, ProcessTxnResult rc) {
        addAuditLog(request, rc, false);
    }

    /**
     * Add audit log if audit log is enabled and operation is of type which to be audit logged.
     *
     * @param request   user request
     * @param txnResult ProcessTxnResult
     * @param failedTxn whether audit is being done failed transaction for normal transaction
     */
    public static void addAuditLog(Request request, ProcessTxnResult txnResult, boolean failedTxn) {
        if (!ZKAuditProvider.isAuditEnabled()) {
            return;
        }
        String op = null;
        //For failed transaction rc.path is null
        String path = txnResult.path;
        String acls = null;
        String createMode = null;
        try {
            switch (request.type) {
                case ZooDefs.OpCode.create:
                case ZooDefs.OpCode.create2:
                case ZooDefs.OpCode.createContainer:
                    op = AuditConstants.OP_CREATE;
                    if (failedTxn) {
                        CreateRequest createRequest = new CreateRequest();
                        deserialize(request, createRequest);
                        path = createRequest.getPath();
                        createMode =
                                getCreateMode(createRequest);
                    } else {
                        createMode = getCreateMode(request);
                    }
                    break;
                case ZooDefs.OpCode.delete:
                case ZooDefs.OpCode.deleteContainer:
                    op = AuditConstants.OP_DELETE;
                    if (failedTxn) {
                        DeleteRequest deleteRequest = new DeleteRequest();
                        deserialize(request, deleteRequest);
                        path = deleteRequest.getPath();
                    }
                    break;
                case ZooDefs.OpCode.setData:
                    op = AuditConstants.OP_SETDATA;
                    if (failedTxn) {
                        SetDataRequest setDataRequest = new SetDataRequest();
                        deserialize(request, setDataRequest);
                        path = setDataRequest.getPath();
                    }
                    break;
                case ZooDefs.OpCode.setACL:
                    op = AuditConstants.OP_SETACL;
                    if (failedTxn) {
                        SetACLRequest setACLRequest = new SetACLRequest();
                        deserialize(request, setACLRequest);
                        path = setACLRequest.getPath();
                        acls = ZKUtil.aclToString(setACLRequest.getAcl());
                    } else {
                        acls = getACLs(request);
                    }
                    break;
                case ZooDefs.OpCode.multi:
                    if (failedTxn) {
                        op = AuditConstants.OP_MULTI_OP;
                    } else {
                        logMultiOperation(request, txnResult);
                        //operation si already logged
                        return;
                    }
                    break;
                case ZooDefs.OpCode.reconfig:
                    op = AuditConstants.OP_RECONFIG;
                    break;
                default:
                    //Not an audit log operation
                    return;
            }
            Result result = getResult(txnResult, failedTxn);
            log(request, path, op, acls, createMode, result);
        } catch (Throwable e) {
            LOG.error("Failed to audit log request {}", request.type, e);
        }
    }

    private static void deserialize(Request request, Record record) throws IOException {
        request.request.rewind();
        ByteBufferInputStream.byteBuffer2Record(request.request.slice(), record);
    }

    private static Result getResult(ProcessTxnResult rc, boolean failedTxn) {
        if (failedTxn) {
            return Result.FAILURE;
        } else {
            return rc.err == KeeperException.Code.OK.intValue() ? Result.SUCCESS : Result.FAILURE;
        }
    }

    private static void logMultiOperation(Request request, ProcessTxnResult rc) throws IOException, KeeperException {
        Map<String, String> createModes = AuditHelper.getCreateModes(request);
        boolean multiFailed = false;
        for (ProcessTxnResult subTxnResult : rc.multiResult) {
            switch (subTxnResult.type) {
                case ZooDefs.OpCode.create:
                case ZooDefs.OpCode.create2:
                case ZooDefs.OpCode.createTTL:
                case ZooDefs.OpCode.createContainer:
                    log(request, subTxnResult.path, AuditConstants.OP_CREATE, null,
                            createModes.get(subTxnResult.path), Result.SUCCESS);
                    break;
                case ZooDefs.OpCode.delete:
                case ZooDefs.OpCode.deleteContainer:
                    log(request, subTxnResult.path, AuditConstants.OP_DELETE, null,
                            null, Result.SUCCESS);
                    break;
                case ZooDefs.OpCode.setData:
                    log(request, subTxnResult.path, AuditConstants.OP_SETDATA, null,
                            null, Result.SUCCESS);
                    break;
                case ZooDefs.OpCode.error:
                    multiFailed = true;
                    break;
                default:
                    // Do nothing, it ok, we do not log all multi operations
            }
        }
        if (multiFailed) {
            log(request, rc.path, AuditConstants.OP_MULTI_OP, null,
                    null, Result.FAILURE);
        }
    }

    private static void log(Request request, String path, String op, String acls, String createMode, Result result) {
        log(request.getUsers(), op, path, acls, createMode,
                request.cnxn.getSessionIdHex(), request.cnxn.getHostAddress(), result);
    }

    private static void log(String user, String operation, String znode, String acl,
                            String createMode, String session, String ip, Result result) {
        ZKAuditProvider.log(user, operation, znode, acl, createMode, session, ip, result);
    }

    private static String getACLs(Request request) throws IOException {
        SetACLRequest setACLRequest = new SetACLRequest();
        deserialize(request, setACLRequest);
        return ZKUtil.aclToString(setACLRequest.getAcl());
    }

    private static String getCreateMode(Request request) throws IOException, KeeperException {
        CreateRequest createRequest = new CreateRequest();
        deserialize(request, createRequest);
        return getCreateMode(createRequest);
    }

    private static String getCreateMode(CreateRequest createRequest) throws KeeperException {
        return CreateMode.fromFlag(createRequest.getFlags()).toString().toLowerCase();
    }

    private static Map<String, String> getCreateModes(Request request)
            throws IOException, KeeperException {
        Map<String, String> createModes = new HashMap<>();
        if (!ZKAuditProvider.isAuditEnabled()) {
            return createModes;
        }
        MultiOperationRecord multiRequest = new MultiOperationRecord();
        deserialize(request, multiRequest);
        for (Op op : multiRequest) {
            if (op.getType() == ZooDefs.OpCode.create || op.getType() == ZooDefs.OpCode.create2
                    || op.getType() == ZooDefs.OpCode.createContainer) {
                CreateRequest requestRecord = (CreateRequest) op.toRequestRecord();
                createModes.put(requestRecord.getPath(),
                        getCreateMode(requestRecord));
            }
        }
        return createModes;
    }

}
