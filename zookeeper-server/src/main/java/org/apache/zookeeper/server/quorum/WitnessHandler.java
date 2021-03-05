package org.apache.zookeeper.server.quorum;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.witness.generated.ReadRequest;
import org.apache.zookeeper.server.quorum.witness.generated.ReadResponse;
import org.apache.zookeeper.server.quorum.witness.generated.WitnessGrpc;
import org.apache.zookeeper.server.quorum.witness.generated.WriteRequest;
import org.apache.zookeeper.server.quorum.witness.generated.WriteResponse;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class WitnessHandler extends ZooKeeperThread {
    /**
     * Primary Capabilities:
     * 1. Read() from witness.
     * 2. write() from witness
     * 3. Validate write() operations.
     * 5. Convert Proposals into write ops and make writes
     * 6. Convert responses returned by witness into metadata and use them as ACKs.
     * What does it need:
     * 0. A ping thread that should start when the Witness handler is started, so that is asynchronously pings..thw witness
     * 1. A sendQueue - the leader thread will add Proposals to this queue.. - Proposals have to be wrapped as
     * WitnessProposal as we need the context of whether a witness is active or not decide on how to handle the response.
     * 2. A recieveQueue. - Update the prposal with the recieved response...so that the response can be processed appropriately.
     * 3. WintessService Synch and async stubs.
     * 4.
     * */
    private static final Logger LOG = LoggerFactory.getLogger(WitnessHandler.class);
    ManagedChannel managedChannel;
    WitnessGrpc.WitnessBlockingStub stub;
    WitnessGrpc.WitnessStub asyncStub;
    InetSocketAddress address;

    final LearnerMaster learnerMaster;
    final QuorumPeer self = null;
    final Leader.WitnessHandlerManager witnessHandlerManager;
    final AtomicBoolean isActive = new AtomicBoolean(false);

    boolean makeActive() {
        return isActive.compareAndSet(false, true);
    }

    boolean makePassive() {
        return isActive.compareAndSet(true, false);
    }

    public boolean isActive() {
        return isActive.get();
    }

    /** Deadline for receiving the next ack. If we are bootstrapping then
     * it's based on the initLimit, if we are done bootstrapping it's based
     * on the syncLimit. Once the deadline is past this learner should
     * be considered no longer "sync'd" with the leader. */
    volatile long tickOfNextAckDeadline;

    /**
     * ZooKeeper server identifier of this witness
     */
    protected long sid = 0;

    public long getSid() {
        return sid;
    }

    String getRemoteAddress() {
        //TODO: Return appropriate information from the service object that would have been created.
        return "<null>";
    }

    public WitnessHandler(long sid, InetSocketAddress address, LearnerMaster learnerMaster, Leader.WitnessHandlerManager witnessHandlerManager) {
        //TODO: pass the exact witnessIp+grpcPort
        super("WitnessHandler-");
        this.sid = sid;
        this.address = address;
        this.learnerMaster = learnerMaster;
        this.witnessHandlerManager = witnessHandlerManager;
    }

    private void createStubs() {
        managedChannel = ManagedChannelBuilder.forAddress(address.getHostString(), address.getPort()).usePlaintext().build();
        stub = WitnessGrpc.newBlockingStub(managedChannel);
        asyncStub = WitnessGrpc.newStub(managedChannel);
    }

    private void destroyStubs() {
        stub = null;
        asyncStub = null;
        if(managedChannel!=null) {
            managedChannel.shutdownNow();
            managedChannel = null;
        }
    }

    final WitnessRequest proposalOfDeath = new WitnessRequest();

    public static class WitnessRequest {
        public long zxid = -1;
        public long batchStartZxid = -1;
        public boolean isActive = false;
        public Type type;

        public enum Type {
            READ,
            WRITE
        }

        //proposal of death
        public WitnessRequest() {
        }

        public WitnessRequest(long zxid, boolean isActive) {
            this.zxid = zxid;
            this.isActive = isActive;
            this.type = Type.WRITE;
        }

        public WitnessRequest(long zxid, long batchStartZxid, boolean isActive) {
            this.zxid = zxid;
            this.batchStartZxid = batchStartZxid;
            this.isActive = isActive;
            this.type = Type.WRITE;
        }

        public WitnessRequest(Type type) {
            this.type = type;
        }

        public long getZxid() {
            return zxid;
        }

        public long getBatchStartZxid() {
            return batchStartZxid;
        }

        public boolean isActive() {
            return isActive;
        }

    }
    /**
     * The requests to be sent to the Witness
     */
    final LinkedBlockingQueue<WitnessRequest> witnessRequests = new LinkedBlockingQueue<>();

    /**
     * Holds requests which are successfully written to the witness.
     * */
    final LinkedBlockingQueue<WitnessRequest> witnessAcks = new LinkedBlockingQueue<>();

    /**
     * These two witness metadata fields will be updated and used for cross referencing when ever
     * we read or write from a witness.
     * */
    protected long latestMetadataVersion = -1;
    protected WitnessMetadata latestMetadata = new WitnessMetadata(-1, -1, -1);

    /**
     * Keep track of whether we have started send packets thread
     */
    private volatile boolean sendingThreadStarted = false;

    /**
     * This class controls the time that the Leader has been
     * waiting for acknowledgement of a proposal from this Learner.
     * If the time is above syncLimit, the connection will be closed.
     * It keeps track of only one proposal at a time, when the ACK for
     * that proposal arrives, it switches to the last proposal received
     * or clears the value if there is no pending proposal.
     */
    private class SyncLimitCheck {

        private boolean started = false;
        private long currentZxid = 0;
        private long currentTime = 0;
        private long nextZxid = 0;
        private long nextTime = 0;

        public synchronized void start() {
            started = true;
        }

        public synchronized void updateProposal(long zxid, long time) {
            if (!started) {
                return;
            }
            if (currentTime == 0) {
                currentTime = time;
                currentZxid = zxid;
            } else {
                nextTime = time;
                nextZxid = zxid;
            }
        }
        //currentTime and currentZxid will become 0 when no other zxid is proposed after the currentZxid
        public synchronized void updateAck(long zxid) {
            if (currentZxid == zxid) {
                currentTime = nextTime;
                currentZxid = nextZxid;
                nextTime = 0;
                nextZxid = 0;
            } else if (nextZxid == zxid) {
                LOG.warn(
                        "ACK for 0x{} received before ACK for 0x{}",
                        Long.toHexString(zxid),
                        Long.toHexString(currentZxid));
                nextTime = 0;
                nextZxid = 0;
            }
        }

        //This will always return true, when the LearnerHandler thread is not waiting for any ACK..i.e currentTime == 0
        public synchronized boolean check(long time) {
            if (currentTime == 0) {
                return true;
            } else {
                long msDelay = (time - currentTime) / 1000000;
                return (msDelay < learnerMaster.syncTimeout());
            }
        }

    }

    private SyncLimitCheck syncLimitCheck = new SyncLimitCheck();

    @Override
    public void run() {
        try {
            //1. add this witness handler object to a leader's data structure
            //learnerMaster.addLearnerHandler(this);
            witnessHandlerManager.witnessHandlers.put(getSid(), this);
            witnessHandlerManager.startInProgress.remove(getSid());

            //2. Any stub initialization logic goes here
            tickOfNextAckDeadline = learnerMaster.getTickOfInitialAckDeadline();
            createStubs();

            //3. Discovery phase
            performDiscovery();

            /*4. synchronize witness
            TODO: Address the problem, where the witness could get ahead of the leader and other servers..
            Refer to the comments in my notes.*/
            synchronizeWitness();

            //prepare for taking part in the broadcast phase
            startSendingPackets();
            syncLimitCheck.start();
            /*
             * Wait until learnerMaster starts up
             */
            learnerMaster.waitForStartup();


            //5. Process responses returned by witness.
            while(true) {
                WitnessRequest ackedRequest = witnessAcks.take();
                if(ackedRequest == proposalOfDeath) {
                    //stop processing..you are done
                    break;
                }
                /*
                tickOfNextAckDeadline can also be updated when we are adding a response to the
                witnessACKs queue
                */
                tickOfNextAckDeadline = learnerMaster.getTickOfNextAckDeadline();

                if(ackedRequest.type.equals(WitnessRequest.Type.WRITE)) {
                    syncLimitCheck.updateAck(ackedRequest.getZxid());
                    if(ackedRequest.isActive()) {
                        //help them reach quorum
                        //TODO: For now just passing null for localSocketAddress param. Its just being used for logging.
                        /**
                         * Send only the last request in the batch to the witness and use the ACK sent by witness for the last request as an indirect ACK for all the requests
                         * in that batch.
                         * Op2 Impl Approach1: Augment WitnessRequestObject with batchStartZxid field. So when we create WitnessRequest, populate both batchStartZxid and Zxid of last request.
                         * Once ACK is received from witness for the last request, WH will invoke processACK() on request from batchStartZxid to Zxid.
                         * */
                        if(ackedRequest.getBatchStartZxid() != -1) {
                            long batchStartZxid = ackedRequest.getBatchStartZxid();
                            long batchEndZxid = ackedRequest.getZxid();
                            if(batchStartZxid != batchEndZxid) {
                                LOG.info("Processing ACKs returned by witness {} for the request batch {} to {}", getSid(), Long.toHexString(batchStartZxid), Long.toHexString(batchEndZxid));
                            }
                            else
                            {
                                LOG.info("Processing ACK returned by witness {} for request {} ", getSid(), Long.toHexString(batchStartZxid));
                            }
                            //I am assuming that there will not be any gaps in zxids
                            while (batchStartZxid <= batchEndZxid) {
                                //processACK is a non blocking call
                                learnerMaster.processAck(this.getSid(), batchStartZxid++, null);
                            }
                        }
                        else {
                            LOG.info("Processing ACK returned by witness {} for request {} ", getSid(), Long.toHexString(ackedRequest.getZxid()));
                            learnerMaster.processAck(this.getSid(), ackedRequest.getZxid(), null);
                        }
                    } else {
                        //else just ignore the ACK.
                        LOG.info("Witness {} was passive at the time this request {}(zxid) was queued, hence ignoring the ACK ", getSid(), Long.toHexString(ackedRequest.getZxid()));
                    }
                }
            }


        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            if(e instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException)e;
                if (sre.getStatus() == Status.UNAVAILABLE) {
                    LOG.warn("Witness {} is unavailable. So shutting down its witness handler", getSid());
                } else {
                    LOG.warn("Witness {} returned {} status. So shutting down its witness handler", getSid(), sre.getStatus().toString());
                }
            }
            else {
                LOG.error("Runtime exception occurred: ", e);
            }
        }
        finally {
            shutdown();
        }
    }

    void performDiscovery() throws IOException, ClassNotFoundException, InterruptedException {
        //Read current contents of witness.
        WitnessResponseWrapper readResp = syncReadFromWitness();
        if(readResp.getVersion() == -1) {
            //The witness is not in following state, so shutting down the witness handler
            shutdown();
            return;
        }
        //Begin Discovery
        LOG.info("Begin Discovery phase");
        //4. Read the witness's current metadata - this is equivalent to a LearnerHandler receiving FOLLOWER-INFO
        latestMetadataVersion = readResp.getVersion();
        latestMetadata = readResp.getMetadata();
        LOG.info("Witness's current info : \n version = {} \n {}", latestMetadataVersion, latestMetadata.toString());
        //Use the acceptedEpoch returned by the witness to generate new epoch.
        long newEpoch = learnerMaster.getEpochToPropose(this.getSid(), latestMetadata.getAcceptedEpoch());

        WitnessMetadata discoveryMetadata = new WitnessMetadata(newEpoch, latestMetadata.getCurrentEpoch(), latestMetadata.getZxid());
        WitnessResponseWrapper writeResponse = WitnessResponseWrapper.buildFromWriteResponse(writeMetadata(discoveryMetadata, latestMetadataVersion+1));
        if(writeResponse.getVersion() != latestMetadataVersion+1) {
            //TODO: Make a more comprehensive write success check.
            //Write was unsuccessful.
            //TODO: Findout why the write failed and shutdown the witness handler accordingly and return from here.
            LOG.info("Discovery: Writing newEpoch : {} to witness : {} failed. \n Expected Version: {} , Returned Version : {}",
                    newEpoch, getSid(), latestMetadataVersion+1, writeResponse.getVersion());
            shutdown();
            return;
        }

        LOG.info("Received ACKEPOCH from witness : {}, acceptedEpoch is {}", getSid(), newEpoch);
        latestMetadataVersion++;
        latestMetadata.setAcceptedEpoch(newEpoch);
        StateSummary ss = new StateSummary(latestMetadata.getCurrentEpoch(), latestMetadata.getZxid());
        learnerMaster.waitForEpochAck(this.getSid(), ss);
        LOG.info("END discovery phase. Its acceptedEpoch = {}", latestMetadata.getAcceptedEpoch());
        }

    void synchronizeWitness() throws IOException, InterruptedException {
        LOG.info("SYNC Begin");
        ZKDatabase db = learnerMaster.getZKDatabase();
        ReentrantReadWriteLock lock = db.getLogLock();
        ReentrantReadWriteLock.ReadLock rl = lock.readLock();
        try {
            rl.lock();
            long maxCommittedLog = db.getmaxCommittedLog();
            long lastProcessedZxid = db.getDataTreeLastProcessedZxid();
            if(db.getCommittedLog().isEmpty()) {
                maxCommittedLog = lastProcessedZxid;
            }
            LOG.info("Witness's current info : \n version = {} \n {}", latestMetadataVersion, latestMetadata.toString());
            WitnessMetadata syncMetadata = new WitnessMetadata(latestMetadata.getAcceptedEpoch()
                    , latestMetadata.getAcceptedEpoch()
                    , maxCommittedLog);
            LOG.info("Sync info : \n version = {} \n {}", latestMetadataVersion+1, syncMetadata.toString());
            WitnessResponseWrapper writeResponse = WitnessResponseWrapper.buildFromWriteResponse(writeMetadata(syncMetadata, latestMetadataVersion+1));
            if (writeResponse.getVersion() != latestMetadataVersion+1) {
                //Write was unsuccessful
                //TODO: Determine why the write has failed. LOG the reason and shutdown the WitnessHandler thread and return
                LOG.info("Synch: Writing metadata to witness : {} failed. \n Expected Version: {} , Returned Version : {}",
                        getSid(), latestMetadataVersion+1, writeResponse.getVersion());
                shutdown();
            }
            latestMetadataVersion++;
            latestMetadata.setCurrentEpoch(syncMetadata.getCurrentEpoch());
            latestMetadata.setZxid(maxCommittedLog);
            learnerMaster.waitForNewLeaderAck(getSid(), ZxidUtils.makeZxid(latestMetadata.getCurrentEpoch(), 0));
        }
        finally {
            rl.unlock();
        }
        LOG.info("SYNC END");
        LOG.info("Post SYNC: Latest Metadata info : version = {}, \n {}", latestMetadataVersion, latestMetadata.toString());
    }

    WitnessResponseWrapper syncReadFromWitness() throws IOException, ClassNotFoundException {
        ReadResponse readResponse = stub.read(ReadRequest.newBuilder().build());
        return WitnessResponseWrapper.buildFromReadResponse(readResponse);
    }

    public static class WitnessResponseWrapper {
        long version;
        //Currently metadata is null for write response, because it returns only version
        WitnessMetadata metadata;
        WitnessRequest.Type type;

        public static WitnessResponseWrapper buildFromWriteResponse(WriteResponse wResponse) {
            return new WitnessResponseWrapper(wResponse.getVersion(), WitnessRequest.Type.WRITE);
        }

        public static WitnessResponseWrapper buildFromReadResponse(ReadResponse readResponse) throws IOException, ClassNotFoundException {
            WitnessMetadata returnedMetadata = createMetadata(readResponse.getMetadata().toByteArray());
            return new WitnessResponseWrapper(readResponse.getVersion(), returnedMetadata, WitnessRequest.Type.READ);
        }

        public WitnessResponseWrapper(long version, WitnessRequest.Type type) {
            this.version = version;
            this.type = type;
        }

        public WitnessResponseWrapper(long version, WitnessMetadata metadata, WitnessRequest.Type type) {
            this.version = version;
            this.metadata = metadata;
            this.type = type;
        }

        public long getVersion() {
            return version;
        }

        public WitnessMetadata getMetadata() {
            return metadata;
        }

        public WitnessRequest.Type getType() {
            return type;
        }
    }

    AtomicLong lastQueuedZxid = new AtomicLong(-1);
    public void queueRequest(long zxid, boolean isWitnessActive) {
        WitnessRequest witnessRequest = new WitnessRequest(zxid, isWitnessActive);
        lastQueuedZxid.set(zxid);
        witnessRequests.add(witnessRequest);
    }

    public void queueRequest(WitnessRequest witnessRequest) {
        lastQueuedZxid.set(witnessRequest.zxid);
        witnessRequests.add(witnessRequest);
    }

    /**
     * ping calls from the learnerMaster to the peers
     */
    public void ping() {
        // If learner hasn't sync properly yet, don't send ping packet
        // otherwise, the learner will crash
        if (!sendingThreadStarted) {
            return;
        }
        /*  SynclimitCheck may not be required for witness because,
            writes to witness happen synchronously
         */
        if (syncLimitCheck.check(System.nanoTime())) {
            witnessRequests.add(new WitnessRequest(WitnessRequest.Type.READ));
        } else {
            LOG.warn("Closing connection to witness due to transaction timeout.");
            shutdown();
        }
    }

    /**
     * Start thread that will forward any packet in the queue to the follower
     */
    protected void startSendingPackets() {
        if (!sendingThreadStarted) {
            // Start sending packets
            new Thread() {
                public void run() {
                    //TODO: Replace getSid() with the ip+grpcPort string of the witness.
                    Thread.currentThread().setName("Sender-" + getSid());
                    try {
                        sendRequests();
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected interruption", e);
                    }
                }
            }.start();
            sendingThreadStarted = true;
        } else {
            LOG.error("Attempting to start sending thread after it already started");
        }
    }

    private void sendRequests() throws InterruptedException {
        WitnessMetadata metadata = new WitnessMetadata(latestMetadata.getAcceptedEpoch(), latestMetadata.getCurrentEpoch(), latestMetadata.getZxid());
        while (true) {
            try {
                WitnessRequest request = witnessRequests.take();
                if(request == proposalOfDeath) {
                    //stop sending requests to the witness
                    break;
                }
                switch (request.type) {
                    case WRITE:
                        /**
                         * 3. Call writeMetadat() function
                         * 4. In the write response check,
                         *          *          if the sentVersion == returnedVersion,
                         *          *              write is succesfull. Add the associated WitnessRequest to the response queue.
                         *          *          else
                         *          *              //could be because 2 reasons.
                         *          *              1.returnedVersion = -1 (witness no longer following)
                         *          *              2. Witness has a higher version, this means the witness has moved on to following another server
                         *          *             In both these scenario, we consider that the leader has lost the support of witness and shutdown the
                         *          *             witness handler
                         *          *          else (Some error occurred while invoking the RPC)
                         *          *              Based on error, if its retryable, invoke the rpc again.
                         *          *              Else, we shutdown the witness handler.
                         * */
                        syncLimitCheck.updateProposal(request.getZxid(), System.nanoTime());

                        long newVersion = latestMetadataVersion + 1;
                        //metadata.updateMetadata(self.getAcceptedEpoch(), self.getCurrentEpoch(), request.zxid);
                        metadata.setZxid(request.zxid);
                        metadata.setAcceptedEpoch(latestMetadata.getAcceptedEpoch());
                        metadata.setCurrentEpoch(latestMetadata.getCurrentEpoch());
                        WriteResponse response = writeMetadata(metadata, newVersion);
                        if(newVersion == response.getVersion()) {
                            //The write is successful.
                            //TODO: Simple equals check on version, would not suffice, we may have to check the content as well. Refer to the comment
                            //on WitnessService.write() function implementation.
                            latestMetadata.readWriteLock.writeLock().lock();
                            latestMetadataVersion = newVersion;
                            latestMetadata.setZxid(metadata.getZxid());
                            latestMetadata.setAcceptedEpoch(metadata.getAcceptedEpoch());
                            latestMetadata.setCurrentEpoch(metadata.getCurrentEpoch());
                            latestMetadata.readWriteLock.writeLock().unlock();
                            witnessAcks.add(request);
                        }
                        else {
                            //Shutdown the witness handler.
                            shutdown();
                        }
                        break;
                    case READ:
                        //TODO: Reads can be performed asynchronously.
                        LOG.info("Pinging the witness");
                        ReadResponse readResponse = stub.read(ReadRequest.newBuilder().build());
                        WitnessMetadata returnedMetadata = createMetadata(readResponse.getMetadata().toByteArray());
                        if(latestMetadataVersion == readResponse.getVersion() && latestMetadata.equals(returnedMetadata)) {
                            witnessAcks.add(request);
                        }
                        else {
                            //Shutdown the witness handler, witness is not in synch with the leader.
                            LOG.info("Comparing Read response: localMetadataVersion = {} , returnedVersion = {} \n localMetadata : {} \n , returnedMetadata : {}",
                                    latestMetadataVersion, readResponse.getVersion(), latestMetadata.toString(), returnedMetadata.toString());
                            LOG.info("Shutdown the witness handler, witness is not in synch with the leader");
                            shutdown();
                        }
                        break;
                }

            }
            catch (IOException | ClassNotFoundException e) {

            }
            catch (RuntimeException exception) {
                if (exception instanceof StatusRuntimeException) {
                    StatusRuntimeException sre = (StatusRuntimeException) exception;
                    if (sre.getStatus() == Status.UNAVAILABLE) {
                        LOG.warn("Witness {} is unavailable. So shutting down its witness handler", getSid());
                    } else {
                        LOG.warn("Witness {} returned {} status. So shutting down its witness handler", getSid(), sre.getStatus().toString());
                    }
                    //currently shutting down if the witness returns any sort of exception
                }
                else {
                    LOG.error("Run time exception occurred :" + exception);
                }
                shutdown();
                break;
            }
        }
    }

    /**
     * Constructs a WriteRequest from the given metadata and version.
     * Perform a write operation on the witness and returns the response.
     * */
    WriteResponse writeMetadata(WitnessMetadata metadata, long version) throws IOException {
        /**
         * 1. Construct the WriteRequest.
         * 2. Then perform the write opeartion and get the writeResponse
         * */
        byte[] metadataByteArr = null;
        try {
            metadataByteArr = convertToByteArray(metadata);
        }
        catch (IOException ioe) {
            LOG.warn("Error while converting Metadata to byte array", ioe);
            throw ioe;
        }

        ByteString metadataBS = ByteString.copyFrom(metadataByteArr);
        WriteRequest writeRequest = WriteRequest.newBuilder()
                .setMetadata(metadataBS)
                .setVersion(version)
                .build();
        WriteResponse writeResponse = stub.write(writeRequest);
        return writeResponse;
    }

    /**
     * Note: Duplicate Method: The same method exists in witness as well
     * */
    public byte[] convertToByteArray(WitnessMetadata metadata) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(metadata);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            //TODO: Handle Exception
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * This method takes in a metadata byte array and returns an NEW metadata object
     * TODO: Future: Accept, a metadata object as an argument, read the metadatabytearray and populate the passed object
     * with information in the array, instead of creating a new object. This reduces the stress on garbage collection.
     * */
    public static WitnessMetadata createMetadata(byte[] metadataByteArray) throws IOException, ClassNotFoundException {
        try {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(metadataByteArray));
            return (WitnessMetadata) ois.readObject();
        } catch (Exception e) {
            //TODO: handle execption
            e.printStackTrace();
            throw e;
        }
    }

    public void shutdown() {
        //Send packet of death
        try {
            witnessRequests.clear();
            witnessRequests.put(proposalOfDeath);
            witnessAcks.clear();
            witnessAcks.put(proposalOfDeath);
        } catch (InterruptedException e) {
            LOG.warn("Ignoring unexpected exception", e);
        }
        //Just interrupting would suffice, but also queuing proposal of death to the witnessAcksQueue just in case
        this.interrupt();
        //TODO: Close any channel or stub related stuff..
        destroyStubs();
        witnessHandlerManager.witnessHandlers.remove(getSid());
    }

    public boolean synced() {
        return isAlive() && learnerMaster.getCurrentTick() <= tickOfNextAckDeadline;
    }
}
