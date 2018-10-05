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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.Record;
import org.apache.jute.BinaryOutputArchive;

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.BadArgumentsException;
import org.apache.zookeeper.MultiTransactionRecord;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.server.ZooKeeperServer.ChangeRecord;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.quorum.Leader.XidRolloverException;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This request processor is generally at the start of a RequestProcessor
 * change. It sets up any transactions associated with requests that change the
 * state of the system. It counts on ZooKeeperServer to update
 * outstandingRequests, so that it can take into account transactions that are
 * in the queue to be applied when generating a transaction.
 */
public class PrepRequestProcessor extends ZooKeeperCriticalThread implements
        RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(PrepRequestProcessor.class);

    static boolean skipACL;
    static {
        skipACL = System.getProperty("zookeeper.skipACL", "no").equals("yes");
        if (skipACL) {
            LOG.info("zookeeper.skipACL==\"yes\", ACL checks will be skipped");
        }
    }

    /**
     * this is only for testing purposes.
     * should never be useed otherwise
     */
    private static  boolean failCreate = false;

    LinkedBlockingQueue<Request> submittedRequests = new LinkedBlockingQueue<Request>();

    RequestProcessor nextProcessor;

    ZooKeeperServer zks;

    public PrepRequestProcessor(ZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        super("ProcessThread(sid:" + zks.getServerId() + " cport:"
                + zks.getClientPort() + "):", zks.getZooKeeperServerListener());
        this.nextProcessor = nextProcessor;
        this.zks = zks;
    }

    /**
     * method for tests to set failCreate
     * @param b
     */
    public static void setFailCreate(boolean b) {
        failCreate = b;
    }
    @Override
    public void run() {
        try {
            while (true) {
                Request request = submittedRequests.take();
                long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
                if (request.type == OpCode.ping) {
                    traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;
                }
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logRequest(LOG, traceMask, 'P', request, "");
                }
                if (Request.requestOfDeath == request) {
                    break;
                }
                pRequest(request);
            }
        } catch (RequestProcessorException e) {
            if (e.getCause() instanceof XidRolloverException) {
                LOG.info(e.getCause().getMessage());
            }
            handleException(this.getName(), e);
        } catch (Exception e) {
            handleException(this.getName(), e);
        }
        LOG.info("PrepRequestProcessor exited loop!");
    }

    ChangeRecord getRecordForPath(String path) throws KeeperException.NoNodeException {
        ChangeRecord lastChange = null;
        synchronized (zks.outstandingChanges) {
            lastChange = zks.outstandingChangesForPath.get(path);
            if (lastChange == null) {
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n != null) {
                    Set<String> children;
                    synchronized(n) {
                        children = n.getChildren();
                    }
                    lastChange = new ChangeRecord(-1, path, n.stat, children.size(),
                            zks.getZKDatabase().aclForNode(n));
                }
            }
        }
        if (lastChange == null || lastChange.stat == null) {
            throw new KeeperException.NoNodeException(path);
        }
        return lastChange;
    }

    private ChangeRecord getOutstandingChange(String path) {
        synchronized (zks.outstandingChanges) {
            return zks.outstandingChangesForPath.get(path);
        }
    }

    void addChangeRecord(ChangeRecord c) {
        synchronized (zks.outstandingChanges) {
            zks.outstandingChanges.add(c);
            zks.outstandingChangesForPath.put(c.path, c);
        }
    }

    /**
     * Grab current pending change records for each op in a multi-op.
     * 
     * This is used inside MultiOp error code path to rollback in the event
     * of a failed multi-op.
     *
     * @param multiRequest
     * @return a map that contains previously existed records that probably need to be
     *         rolled back in any failure.
     */
    HashMap<String, ChangeRecord> getPendingChanges(MultiTransactionRecord multiRequest) {
        HashMap<String, ChangeRecord> pendingChangeRecords = new HashMap<String, ChangeRecord>();

        for (Op op : multiRequest) {
            String path = op.getPath();
            ChangeRecord cr = getOutstandingChange(path);
            // only previously existing records need to be rolled back.
            if (cr != null) {
                pendingChangeRecords.put(path, cr);
            }

            /*
             * ZOOKEEPER-1624 - We need to store for parent's ChangeRecord
             * of the parent node of a request. So that if this is a
             * sequential node creation request, rollbackPendingChanges()
             * can restore previous parent's ChangeRecord correctly.
             *
             * Otherwise, sequential node name generation will be incorrect
             * for a subsequent request.
             */
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1 || path.indexOf('\0') != -1) {
                continue;
            }
            String parentPath = path.substring(0, lastSlash);
            ChangeRecord parentCr = getOutstandingChange(parentPath);
            if (parentCr != null) {
                pendingChangeRecords.put(parentPath, parentCr);
            }
        }

        return pendingChangeRecords;
    }

    /**
     * Rollback pending changes records from a failed multi-op.
     *
     * If a multi-op fails, we can't leave any invalid change records we created
     * around. We also need to restore their prior value (if any) if their prior
     * value is still valid.
     *
     * @param zxid
     * @param pendingChangeRecords
     */
    void rollbackPendingChanges(long zxid, HashMap<String, ChangeRecord>pendingChangeRecords) {
        synchronized (zks.outstandingChanges) {
            // Grab a list iterator starting at the END of the list so we can iterate in reverse
            ListIterator<ChangeRecord> iter = zks.outstandingChanges.listIterator(zks.outstandingChanges.size());
            while (iter.hasPrevious()) {
                ChangeRecord c = iter.previous();
                if (c.zxid == zxid) {
                    iter.remove();
                    // Remove all outstanding changes for paths of this multi.
                    // Previous records will be added back later.
                    zks.outstandingChangesForPath.remove(c.path);
                } else {
                    break;
                }
            }

            // we don't need to roll back any records because there is nothing left.
            if (zks.outstandingChanges.isEmpty()) {
                return;
            }

            long firstZxid = zks.outstandingChanges.get(0).zxid;

            for (ChangeRecord c : pendingChangeRecords.values()) {
                // Don't apply any prior change records less than firstZxid.
                // Note that previous outstanding requests might have been removed
                // once they are completed.
                if (c.zxid < firstZxid) {
                    continue;
                }

                // add previously existing records back.
                zks.outstandingChangesForPath.put(c.path, c);
            }
        }
    }

    static void checkACL(ZooKeeperServer zks, List<ACL> acl, int perm,
            List<Id> ids) throws KeeperException.NoAuthException {
        if (skipACL) {
            return;
        }
        if (acl == null || acl.size() == 0) {
            return;
        }
        for (Id authId : ids) {
            if (authId.getScheme().equals("super")) {
                return;
            }
        }
        for (ACL a : acl) {
            Id id = a.getId();
            if ((a.getPerms() & perm) != 0) {
                if (id.getScheme().equals("world")
                        && id.getId().equals("anyone")) {
                    return;
                }
                AuthenticationProvider ap = ProviderRegistry.getProvider(id
                        .getScheme());
                if (ap != null) {
                    for (Id authId : ids) {                        
                        if (authId.getScheme().equals(id.getScheme())
                                && ap.matches(authId.getId(), id.getId())) {
                            return;
                        }
                    }
                }
            }
        }
        throw new KeeperException.NoAuthException();
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param type
     * @param zxid
     * @param request
     * @param record
     */
    @SuppressWarnings("unchecked")
    protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize)
        throws KeeperException, IOException, RequestProcessorException
    {
        request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid,
                                    Time.currentWallTime(), type);

        switch (type) {
            case OpCode.create:                
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                CreateRequest createRequest = (CreateRequest)record;   
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                String path = createRequest.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1 || failCreate) {
                    LOG.info("Invalid path " + path + " with session 0x" +
                            Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }
                List<ACL> listACL = removeDuplicates(createRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE,
                        request.authInfo);
                int parentCVersion = parentRecord.stat.getCversion();
                CreateMode createMode =
                    CreateMode.fromFlag(createRequest.getFlags());
                if (createMode.isSequential()) {
                    path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
                }
                validatePath(path, request.sessionId);
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException(path);
                    }
                } catch (KeeperException.NoNodeException e) {
                    // ignore this one
                }
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException(path);
                }
                int newCversion = parentRecord.stat.getCversion()+1;
                request.txn = new CreateTxn(path, createRequest.getData(),
                        listACL,
                        createMode.isEphemeral(), newCversion);
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }
                parentRecord = parentRecord.duplicate(request.hdr.getZxid());
                parentRecord.childCount++;
                parentRecord.stat.setCversion(newCversion);
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.hdr.getZxid(), path, s,
                        0, listACL));
                break;
            case OpCode.delete:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                DeleteRequest deleteRequest = (DeleteRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, deleteRequest);
                path = deleteRequest.getPath();
                lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1
                        || zks.getZKDatabase().isSpecialPath(path)) {
                    throw new KeeperException.BadArgumentsException(path);
                }
                parentPath = path.substring(0, lastSlash);
                parentRecord = getRecordForPath(parentPath);
                ChangeRecord nodeRecord = getRecordForPath(path);
                checkACL(zks, parentRecord.acl, ZooDefs.Perms.DELETE,
                        request.authInfo);
                int version = deleteRequest.getVersion();
                if (version != -1 && nodeRecord.stat.getVersion() != version) {
                    throw new KeeperException.BadVersionException(path);
                }
                if (nodeRecord.childCount > 0) {
                    throw new KeeperException.NotEmptyException(path);
                }
                request.txn = new DeleteTxn(path);
                parentRecord = parentRecord.duplicate(request.hdr.getZxid());
                parentRecord.childCount--;
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.hdr.getZxid(), path,
                        null, -1, null));
                break;
            case OpCode.setData:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                SetDataRequest setDataRequest = (SetDataRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, setDataRequest);
                path = setDataRequest.getPath();
                validatePath(path, request.sessionId);
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.WRITE,
                        request.authInfo);
                version = setDataRequest.getVersion();
                int currentVersion = nodeRecord.stat.getVersion();
                if (version != -1 && version != currentVersion) {
                    throw new KeeperException.BadVersionException(path);
                }
                version = currentVersion + 1;
                request.txn = new SetDataTxn(path, setDataRequest.getData(), version);
                nodeRecord = nodeRecord.duplicate(request.hdr.getZxid());
                nodeRecord.stat.setVersion(version);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.setACL:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                SetACLRequest setAclRequest = (SetACLRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, setAclRequest);
                path = setAclRequest.getPath();
                validatePath(path, request.sessionId);
                listACL = removeDuplicates(setAclRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.ADMIN,
                        request.authInfo);
                version = setAclRequest.getVersion();
                currentVersion = nodeRecord.stat.getAversion();
                if (version != -1 && version != currentVersion) {
                    throw new KeeperException.BadVersionException(path);
                }
                version = currentVersion + 1;
                request.txn = new SetACLTxn(path, listACL, version);
                nodeRecord = nodeRecord.duplicate(request.hdr.getZxid());
                nodeRecord.stat.setAversion(version);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.createSession:
                request.request.rewind();
                int to = request.request.getInt();
                request.txn = new CreateSessionTxn(to);
                request.request.rewind();
                zks.sessionTracker.addSession(request.sessionId, to);
                zks.setOwner(request.sessionId, request.getOwner());
                break;
            case OpCode.closeSession:
                // We don't want to do this check since the session expiration thread
                // queues up this operation without being the session owner.
                // this request is the last of the session so it should be ok
                //zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                HashSet<String> es = zks.getZKDatabase()
                        .getEphemerals(request.sessionId);
                synchronized (zks.outstandingChanges) {
                    for (ChangeRecord c : zks.outstandingChanges) {
                        if (c.stat == null) {
                            // Doing a delete
                            es.remove(c.path);
                        } else if (c.stat.getEphemeralOwner() == request.sessionId) {
                            es.add(c.path);
                        }
                    }
                    for (String path2Delete : es) {
                        addChangeRecord(new ChangeRecord(request.hdr.getZxid(),
                                path2Delete, null, 0, null));
                    }

                    zks.sessionTracker.setSessionClosing(request.sessionId);
                }

                LOG.info("Processed session termination for sessionid: 0x"
                        + Long.toHexString(request.sessionId));
                break;
            case OpCode.check:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                CheckVersionRequest checkVersionRequest = (CheckVersionRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, checkVersionRequest);
                path = checkVersionRequest.getPath();
                validatePath(path, request.sessionId);
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.READ,
                        request.authInfo);
                version = checkVersionRequest.getVersion();
                currentVersion = nodeRecord.stat.getVersion();
                if (version != -1 && version != currentVersion) {
                    throw new KeeperException.BadVersionException(path);
                }
                version = currentVersion + 1;
                request.txn = new CheckVersionTxn(path, version);
                break;
            default:
                LOG.error("Invalid OpCode: {} received by PrepRequestProcessor", type);
        }
    }

    private void validatePath(String path, long sessionId) throws BadArgumentsException {
        try {
            PathUtils.validatePath(path);
        } catch(IllegalArgumentException ie) {
            LOG.info("Invalid path " +  path + " with session 0x" + Long.toHexString(sessionId) +
                    ", reason: " + ie.getMessage());
            throw new BadArgumentsException(path);
        }
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param request
     */
    @SuppressWarnings("unchecked")
    protected void pRequest(Request request) throws RequestProcessorException {
        // LOG.info("Prep>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = 0x" + Long.toHexString(request.sessionId));
        request.hdr = null;
        request.txn = null;
        
        try {
            switch (request.type) {
                case OpCode.create:
                CreateRequest createRequest = new CreateRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, createRequest, true);
                break;
            case OpCode.delete:
                DeleteRequest deleteRequest = new DeleteRequest();               
                pRequest2Txn(request.type, zks.getNextZxid(), request, deleteRequest, true);
                break;
            case OpCode.setData:
                SetDataRequest setDataRequest = new SetDataRequest();                
                pRequest2Txn(request.type, zks.getNextZxid(), request, setDataRequest, true);
                break;
            case OpCode.setACL:
                SetACLRequest setAclRequest = new SetACLRequest();                
                pRequest2Txn(request.type, zks.getNextZxid(), request, setAclRequest, true);
                break;
            case OpCode.check:
                CheckVersionRequest checkRequest = new CheckVersionRequest();              
                pRequest2Txn(request.type, zks.getNextZxid(), request, checkRequest, true);
                break;
            case OpCode.multi:
                MultiTransactionRecord multiRequest = new MultiTransactionRecord();
                try {
                    ByteBufferInputStream.byteBuffer2Record(request.request, multiRequest);
                } catch(IOException e) {
                    request.hdr =  new TxnHeader(request.sessionId, request.cxid, zks.getNextZxid(),
                            Time.currentWallTime(), OpCode.multi);
                    throw e;
                }
                List<Txn> txns = new ArrayList<Txn>();
                //Each op in a multi-op must have the same zxid!
                long zxid = zks.getNextZxid();
                KeeperException ke = null;

                //Store off current pending change records in case we need to rollback
                HashMap<String, ChangeRecord> pendingChanges = getPendingChanges(multiRequest);

                int index = 0;
                for(Op op: multiRequest) {
                    Record subrequest = op.toRequestRecord() ;

                    /* If we've already failed one of the ops, don't bother
                     * trying the rest as we know it's going to fail and it
                     * would be confusing in the logfiles.
                     */
                    if (ke != null) {
                        request.hdr.setType(OpCode.error);
                        request.txn = new ErrorTxn(Code.RUNTIMEINCONSISTENCY.intValue());
                    } 
                    
                    /* Prep the request and convert to a Txn */
                    else {
                        try {
                            pRequest2Txn(op.getType(), zxid, request, subrequest, false);
                        } catch (KeeperException e) {
                            ke = e;
                            request.hdr.setType(OpCode.error);
                            request.txn = new ErrorTxn(e.code().intValue());
                            LOG.info("Got user-level KeeperException when processing "
                            		+ request.toString() + " aborting remaining multi ops."
                            		+ " Error Path:" + e.getPath()
                            		+ " Error:" + e.getMessage());

                            request.setException(e);

                            /* Rollback change records from failed multi-op */
                            rollbackPendingChanges(zxid, pendingChanges);
                        }
                    }

                    //FIXME: I don't want to have to serialize it here and then
                    //       immediately deserialize in next processor. But I'm 
                    //       not sure how else to get the txn stored into our list.
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
                    request.txn.serialize(boa, "request") ;
                    ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

                    txns.add(new Txn(request.hdr.getType(), bb.array()));
                    index++;
                }

                request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid,
                        Time.currentWallTime(), request.type);
                request.txn = new MultiTxn(txns);
                
                break;

            //create/close session don't require request record
            case OpCode.createSession:
            case OpCode.closeSession:
                pRequest2Txn(request.type, zks.getNextZxid(), request, null, true);
                break;
 
            //All the rest don't need to create a Txn - just verify session
            case OpCode.sync:
            case OpCode.exists:
            case OpCode.getData:
            case OpCode.getACL:
            case OpCode.getChildren:
            case OpCode.getChildren2:
            case OpCode.ping:
            case OpCode.setWatches:
                zks.sessionTracker.checkSession(request.sessionId,
                        request.getOwner());
                break;
            default:
                LOG.warn("unknown type " + request.type);
                break;
            }
        } catch (KeeperException e) {
            if (request.hdr != null) {
                request.hdr.setType(OpCode.error);
                request.txn = new ErrorTxn(e.code().intValue());
            }
            LOG.info("Got user-level KeeperException when processing "
                    + request.toString()
                    + " Error Path:" + e.getPath()
                    + " Error:" + e.getMessage());
            request.setException(e);
        } catch (Exception e) {
            // log at error level as we are returning a marshalling
            // error to the user
            LOG.error("Failed to process " + request, e);

            StringBuilder sb = new StringBuilder();
            ByteBuffer bb = request.request;
            if(bb != null){
                bb.rewind();
                while (bb.hasRemaining()) {
                    sb.append(Integer.toHexString(bb.get() & 0xff));
                }
            } else {
                sb.append("request buffer is null");
            }

            LOG.error("Dumping request buffer: 0x" + sb.toString());
            if (request.hdr != null) {
                request.hdr.setType(OpCode.error);
                request.txn = new ErrorTxn(Code.MARSHALLINGERROR.intValue());
            }
        }
        request.zxid = zks.getZxid();
        nextProcessor.processRequest(request);
    }

    private List<ACL> removeDuplicates(List<ACL> acl) {

        ArrayList<ACL> retval = new ArrayList<ACL>();
        Iterator<ACL> it = acl.iterator();
        while (it.hasNext()) {
            ACL a = it.next();
            if (retval.contains(a) == false) {
                retval.add(a);
            }
        }
        return retval;
    }


    /**
     * This method checks out the acl making sure it isn't null or empty,
     * it has valid schemes and ids, and expanding any relative ids that
     * depend on the requestor's authentication information.
     *
     * @param authInfo list of ACL IDs associated with the client connection
     * @param acl list of ACLs being assigned to the node (create or setACL operation)
     * @return
     */
    private boolean fixupACL(List<Id> authInfo, List<ACL> acl) {
        if (skipACL) {
            return true;
        }
        if (acl == null || acl.size() == 0) {
            return false;
        }

        Iterator<ACL> it = acl.iterator();
        LinkedList<ACL> toAdd = null;
        while (it.hasNext()) {
            ACL a = it.next();
            Id id = a.getId();
            if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                // wide open
            } else if (id.getScheme().equals("auth")) {
                // This is the "auth" id, so we have to expand it to the
                // authenticated ids of the requestor
                it.remove();
                if (toAdd == null) {
                    toAdd = new LinkedList<ACL>();
                }
                boolean authIdValid = false;
                for (Id cid : authInfo) {
                    AuthenticationProvider ap =
                        ProviderRegistry.getProvider(cid.getScheme());
                    if (ap == null) {
                        LOG.error("Missing AuthenticationProvider for "
                                + cid.getScheme());
                    } else if (ap.isAuthenticated()) {
                        authIdValid = true;
                        toAdd.add(new ACL(a.getPerms(), cid));
                    }
                }
                if (!authIdValid) {
                    return false;
                }
            } else {
                AuthenticationProvider ap = ProviderRegistry.getProvider(id
                        .getScheme());
                if (ap == null) {
                    return false;
                }
                if (!ap.isValid(id.getId())) {
                    return false;
                }
            }
        }
        if (toAdd != null) {
            for (ACL a : toAdd) {
                acl.add(a);
            }
        }
        return acl.size() > 0;
    }

    public void processRequest(Request request) {
        // request.addRQRec(">prep="+zks.outstandingChanges.size());
        submittedRequests.add(request);
    }

    public void shutdown() {
        LOG.info("Shutting down");
        submittedRequests.clear();
        submittedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }
}
