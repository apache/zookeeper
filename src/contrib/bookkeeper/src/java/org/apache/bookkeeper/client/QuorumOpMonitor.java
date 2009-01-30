package org.apache.bookkeeper.client;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


import org.apache.bookkeeper.client.BookieHandle;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.ErrorCodes;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.ReadOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubAddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubReadOp;
import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;


/**
 * Monitors reponses from bookies to requests of a client. It implements 
 * two interfaces of the proto package that correspond to callbacks from
 * BookieClient objects.
 * 
 */
public class QuorumOpMonitor implements WriteCallback, ReadEntryCallback {
    Logger LOG = Logger.getLogger(QuorumOpMonitor.class);
    
    LedgerHandle lh;
    
    static final int MAXRETRIES = 2;
    static HashMap<Long, QuorumOpMonitor> instances = 
        new HashMap<Long, QuorumOpMonitor>();
    
    public static QuorumOpMonitor getInstance(LedgerHandle lh){
        if(instances.get(lh.getId()) == null) {
            instances.put(lh.getId(), new QuorumOpMonitor(lh));
        }
        
        return instances.get(lh.getId());
    }
    
    /**
     * Message disgest instance
     * 
     */
    MessageDigest digest = null;
    
    /** 
     * Get digest instance if there is none.
     * 
     */
    MessageDigest getDigestInstance(String alg)
    throws NoSuchAlgorithmException {
        if(digest == null){
            digest = MessageDigest.getInstance(alg);
        }
        
        return digest;
    }
    
    public static class PendingOp{
        //Operation op = null;
        HashSet<Integer> bookieIdSent;
        HashSet<Integer> bookieIdRecv;
        int retries = 0;
      
        PendingOp(){
            this.bookieIdSent = new HashSet<Integer>();
            this.bookieIdRecv = new HashSet<Integer>();
        }

        //PendingOp(Operation op){
        //    this.op = op;
        //    bookieIdSent = new HashSet<Integer>();
        //    bookieIdRecv = new HashSet<Integer>();
        //}
        
        //void setOp(Operation op){
        //    this.op = op;
        //}
        
        //Operation getOp(){
        //    return this.op;
        //}
        
    };
    
    /**
     * Objects of this type are used to keep track of the status of
     * a given write request.
     * 
     *
     */
    //public static class PendingAddOp extends PendingOp{
    //    AddOp op;  
        
    //    PendingAddOp(LedgerHandle lh, AddOp op){
    //        this.op = op;
    //    }
    //}
    
    /**
     * Objects of this type are used to keep track of the status of
     * a given read request.
     * 
     */
    
    public static class PendingReadOp extends PendingOp{
        /*
         * Values for ongoing reads
         */
        ConcurrentHashMap<Long, ArrayList<ByteBuffer>> proposedValues;
        
        /*
         * Bookies from which received a response
         */
        //ConcurrentHashMap<Long, HashSet<Integer>> received;
        
        
        PendingReadOp(LedgerHandle lh){
            this.proposedValues = 
                new ConcurrentHashMap<Long, ArrayList<ByteBuffer>>();
            //this.received = 
            //    new ConcurrentHashMap<Long, HashSet<Integer>>();
        }    
      
    }
    
    QuorumOpMonitor(LedgerHandle lh){
        this.lh = lh;
    }
    
    
    
    
    /**
     * Callback method for write operations. There is one callback for
     * each write to a server.
     * 
     */
    
    public void writeComplete(int rc, long ledgerId, long entryId, Object ctx){ 
        //PendingAddOp pOp;
        String logmsg;
        
        //synchronized(pendingAdds){
        //pOp = pendingAdds.get(entryId);
        //}
        SubAddOp sAdd = (SubAddOp) ctx;
        PendingOp pOp = sAdd.pOp;
        Integer sId = sAdd.bIndex;
        
        if(pOp == null){
            LOG.error("No such an entry ID: " + entryId + "(" + ledgerId + ")");
            return;
        }
        
        ArrayList<BookieHandle> list = lh.getBookies();
        int n = list.size();
         
        if(rc == 0){
            // Everything went ok with this op
            synchronized(pOp){ 
                //pOp.bookieIdSent.add(sId);
                pOp.bookieIdRecv.add(sId);
                if(pOp.bookieIdRecv.size() == lh.getQuorumSize()){
                    //pendingAdds.remove(entryId);
                    //sAdd.op.cb.addComplete(sAdd.op.getErrorCode(),
                    //        ledgerId, entryId, sAdd.op.ctx);
                    sAdd.op.setReady();     
                }
            }
        } else {
            LOG.error("Error sending write request: " + rc + " : " + ledgerId);
            HashSet<Integer> ids;
              
            synchronized(pOp){
                pOp.bookieIdSent.add(sId);
                ids = pOp.bookieIdSent;                
                //Check if we tried all possible bookies already
                if(ids.size() == lh.getBookies().size()){
                    if(pOp.retries++ >= MAXRETRIES){
                        //Call back with error code
                        //sAdd.op.cb.addComplete(ErrorCodes.ENUMRETRIES,
                        //        ledgerId, entryId, sAdd.op.ctx);
                        sAdd.op.setErrorCode(ErrorCodes.ENUMRETRIES);
                        sAdd.op.setReady();
                        return;
                    }
                    
                    ids.clear();
                }
                // Select another bookie that we haven't contacted yet
                for(int i = 0; i < lh.getBookies().size(); i++){
                    if(!ids.contains(Integer.valueOf(i))){
                        // and send it to new bookie
                        try{
                            list.get(i).sendAdd(new SubAddOp(sAdd.op, 
                                    pOp, 
                                    i, 
                                    this), ((AddOp) sAdd.op).entry);
                            pOp.bookieIdRecv.add(sId.intValue());
                                
                            break;
                        } catch(IOException e){
                            LOG.error(e);
                        }
                    }
                }       
            }
        }
    }
    
    /**
     * Callback method for read operations. There is one callback for
     * each entry of a read request.
     * 
     * TODO: We might want to change the way a client application specify
     * the quorum size. It is really loose now, and it allows an application
     * to set any quorum size the client wants.
     */
    
    public void readEntryComplete(int rc, long ledgerId, long entryId, ByteBuffer bb, Object ctx){
        /*
         * Collect responses, and reply when there are sufficient 
         * answers.
         */
        LOG.debug("New response: " + rc);
        if(rc == 0){
            SubReadOp sRead = (SubReadOp) ctx;
            ReadOp rOp = (ReadOp) sRead.op;
            PendingReadOp pOp = sRead.pOp;
            if(pOp != null){
                HashSet<Integer> received = pOp.bookieIdRecv;
                //if(!received.containsKey(entryId)){
                //    received.put(entryId, new HashSet<Integer>());
                //}
                boolean result = received.add(sRead.bIndex);
                int counter = -1;
                if(result){

                    if(!pOp.proposedValues.containsKey(entryId)){
                        pOp.proposedValues.put(entryId, new ArrayList<ByteBuffer>());
                    }
                    ArrayList<ByteBuffer> list = pOp.proposedValues.get(entryId);
                    list.add(bb);
        
                    switch(lh.getQMode()){
                        case VERIFIABLE:
                            if(list.size() >= 1){
                                try{
                                    ByteBuffer voted = voteVerifiable(list);
                                    if(voted != null){
                                        LOG.debug("Voted: " + new String(voted.array()));
                                    
                                        MessageDigest md = getDigestInstance(lh.getDigestAlg());
                                        int dlength = md.getDigestLength();
                                        if(voted.capacity() - dlength > 0){
                                            byte[] data = new byte[voted.capacity() - dlength - 24];
                                            LOG.info("Digest length: " + dlength + ", " + data.length);
                                            voted.position(24);
                                            voted.get(data, 0, data.length);
                                            counter = addNewEntry(new LedgerEntry(ledgerId, entryId, data), rOp);
                                        } else {
                                            LOG.error("Short message: " + voted.capacity());
                                        }
                                    }
                                } catch(NoSuchAlgorithmException e){
                                    LOG.error("Problem with message digest: " + e);
                                } catch(BKException bke) {
                                    LOG.error(bke.toString() + "( " + ledgerId + ", " + entryId + ", " + pOp.bookieIdRecv + ")");
                                    countNacks((ReadOp) ((SubReadOp) ctx).op, (SubReadOp) ctx, ledgerId, entryId);
                                }
                            }
                            break;
                        case GENERIC:
                            if(list.size() >= ((lh.getQuorumSize() + 1)/2)){
                                ByteBuffer voted = voteGeneric(list, (lh.getQuorumSize() + 1)/2);
                                if(voted != null){
                                    LOG.debug("Voted: " + voted.array());
                                    byte[] data = new byte[voted.capacity() - 24];
                                    voted.position(24);
                                    voted.get(data, 0, data.length);
                                    counter = addNewEntry(new LedgerEntry(ledgerId, entryId, data), rOp);
                                }
                            }
                            break;
                        case FREEFORM:
                        	if(list.size() == lh.getQuorumSize()){
                        		ByteBuffer voted = voteFree(list);
                                if(voted != null){
                                    LOG.debug("Voted: " + voted.array());
                                    byte[] data = new byte[voted.capacity() - 24];
                                    voted.position(24);
                                    voted.get(data, 0, data.length);
                                    counter = addNewEntry(new LedgerEntry(ledgerId, entryId, voted.array()), rOp);
                                }
                        	}
                    }
                }    
        
                if((counter == (rOp.lastEntry - rOp.firstEntry + 1)) && 
                        !sRead.op.isReady()){
                    
                    sRead.op.setReady();
                    //sRead.op.cb.readComplete(0, ledgerId, new LedgerSequence(sRead.op.seq), sRead.op.ctx);
                    //sRead.op.complete = true;
                }
            
                LOG.debug("Counter: " + rOp.counter);
            }
        } else {
            /*
             * Have to count the number of negative responses
             */
            countNacks((ReadOp) ((SubReadOp) ctx).op, (SubReadOp) ctx, ledgerId, entryId);
            
        }
    }
    
    
    /**
     * Counts negative responses
     * 
     * @param   rOp read operation
     * @param   sRead   specific read sub-operation
     */
    
    synchronized void countNacks(ReadOp rOp, SubReadOp sRead, long ledgerId, long entryId){
        
        if(!rOp.nacks.containsKey(entryId)){
            rOp.nacks.put(entryId, new AtomicInteger(0));
        }
        
        if(rOp.nacks.get(entryId).incrementAndGet() >= lh.getThreshold()){
            int counter = -1;
            counter = addNewEntry(new LedgerEntry(ledgerId, entryId, null), rOp);
            
            if((counter == (rOp.lastEntry - rOp.firstEntry + 1)) && 
                    !sRead.op.isReady()){
                
                sRead.op.setReady();
            }
        }
    }
    
    /**
     * Verify if the set of votes in the list can produce a correct answer
     * for verifiable data.
     * 
     * @param list
     * @return
     */
    
    private ByteBuffer voteVerifiable(ArrayList<ByteBuffer> list) 
    throws NoSuchAlgorithmException, BKException{
        /*
         * Check if checksum matches
         */
        ByteBuffer bb = list.get(0);
        list.remove(0);
        
        MessageDigest md = getDigestInstance(lh.getDigestAlg());
        int dlength = md.getDigestLength();
       
        /*
         * TODO: The if check below is legitimate, but in reality it should never happen,
         * bt it showed up a few times in experiments. Have to check why it is happening.
         */
        if(bb.capacity() <= dlength){
        	LOG.warn("Something wrong with this entry, length smaller than digest length");
        	return null;
        }
        
        byte[] data = new byte[bb.capacity() - dlength];
        bb.get(data, 0, bb.capacity() - dlength);
        
        byte[] sig = new byte[dlength];
        bb.position(bb.capacity() - dlength);
        bb.get(sig, 0, dlength);
        
        bb.rewind();
        
        //LOG.warn("Data: " + data.toString() + ", Signature: " + sig.toString());
        md.update(lh.getPasswdHash());
        md.update(data);
        if(MessageDigest.isEqual(md.digest(), sig)){
            return bb;
        } else {
            throw BKException.create(Code.DigestMatchException);
        }
    }
    
    /**
     * Verify if the set of votes in the list can produce a correct answer
     * for generic data.
     * 
     * @param list
     * @return
     */
        
    private ByteBuffer voteGeneric(ArrayList<ByteBuffer> list, int threshold){  
        HashMap<ByteBuffer, Integer> map = new HashMap<ByteBuffer, Integer>();
        for(ByteBuffer bb : list){
            if(!map.containsKey(bb)){
                map.put(bb, Integer.valueOf(0));
            }
            
            map.put(bb, map.get(bb) + 1);
            
            if(map.get(bb) >= threshold)
                return bb;
        }
        
        return null;   
    }

    /**
     * Verify if the set of votes in the list can produce a correct answer
     * for generic data.
     * 
     * @param list
     * @return
     */
        
    private ByteBuffer voteFree(ArrayList<ByteBuffer> list){  
        HashMap<ByteBuffer, Integer> map = new HashMap<ByteBuffer, Integer>();
        for(ByteBuffer bb : list){
            if(!map.containsKey(bb)){
                map.put(bb, Integer.valueOf(0));
            }
            map.put(bb, map.get(bb) + 1);
            
            if(map.get(bb) == list.size())
                return bb;
        }
        
        return null;   
    }
    
    /**
     * Add new entry to the list of received. 
     * 
     * @param le	ledger entry to add to list
     * @param op	read operation metadata
     */
    
    private int addNewEntry(LedgerEntry le, ReadOp op){
        long index = le.getEntryId() % (op.lastEntry - op.firstEntry + 1);
        if(op.seq[(int) index] == null){
            op.seq[(int) index] = le;
            
            if(le.getEntry() != null)
                LOG.debug("Adding entry: " + le.getEntryId() + ", " + le.getEntry().length);
            else
                LOG.debug("Entry is null: " + le.getEntryId());
            
            return op.counter.incrementAndGet();
        }
        
        return -1;
    }
}
