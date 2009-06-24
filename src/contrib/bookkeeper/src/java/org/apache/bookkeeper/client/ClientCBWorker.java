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


import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.ReadOp;
import org.apache.log4j.Logger;

/**
 * Thread responsible for delivering results to clients. This thread
 * basically isolates the application from the remainder of the
 * BookKeeper client. 
 * 
 */

class ClientCBWorker extends Thread{
    static Logger LOG = Logger.getLogger(ClientCBWorker.class);
    static ClientCBWorker instance = null;
    
    private volatile boolean stop;
    private static int instanceCounter= 0;
    
    ArrayBlockingQueue<Operation> pendingOps;
    QuorumOpMonitor monitor;
    
    
    static ClientCBWorker getInstance(){
        if(instance == null){
            instance = new ClientCBWorker();
        }
        instanceCounter++;
        
        return instance;
    }
    
    /**
     * Constructor initiates queue of pending operations and
     * runs thread.
     * 
     */
    ClientCBWorker(){
       pendingOps = new ArrayBlockingQueue<Operation>(6000);  
       stop = false;
       start();
       LOG.info("Have started cbWorker");
    }
    
    
    /**
     * Adds operation to queue of pending.
     * 
     * @param   op  operation to add to queue
     */
    
    void addOperation(Operation op) 
    throws InterruptedException {
        pendingOps.put(op);
    }
    
    /**
     * Gets thread out of its main loop.
     * 
     */
    void shutdown(){
        if((--instanceCounter) == 0){
            stop = true;
            instance = null;
            LOG.info("Shutting down CBWorker");
        }
    }
    
    
    /**
     * Main thread loop.
     * 
     */
    
    public void run(){
        try{
            while(!stop){
                Operation op = pendingOps.poll(1000, TimeUnit.MILLISECONDS);
                if(op != null){
                    synchronized(op){
                        while(!op.isReady()){
                            op.wait(1000);
                        }
                    }
                    
                    switch(op.type){
                    case Operation.ADD:
                        AddOp aOp = (AddOp) op;
                       
                        aOp.getLedger().setAddConfirmed(aOp.entry);
                        aOp.cb.addComplete(aOp.getErrorCode(),
                                aOp.getLedger(),
                                aOp.entry, 
                                aOp.ctx);
                        
                        break;
                    case Operation.READ:
                        ReadOp rOp = (ReadOp) op;
                        rOp.cb.readComplete(rOp.getErrorCode(), 
                                rOp.getLedger(),
                                new LedgerSequence(rOp.seq), 
                                rOp.ctx);
                        break;
                    }
                } 
            }
        } catch (InterruptedException e){
           LOG.error("Exception while waiting on queue or operation"); 
        }
    }
}
