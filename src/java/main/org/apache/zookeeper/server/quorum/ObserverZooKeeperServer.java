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
package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * A ZooKeeperServer for the Observer node type. Not much is different, but
 * we anticipate specializing the request processors in the future. 
 *
 */
public class ObserverZooKeeperServer extends LearnerZooKeeperServer {
    private static final Logger LOG =
        Logger.getLogger(ObserverZooKeeperServer.class);        
    
    /*
     * Request processors
     */
    private CommitProcessor commitProcessor;
    private SyncRequestProcessor syncProcessor;
    
    /*
     * Pending sync requests
     */
    ConcurrentLinkedQueue<Request> pendingSyncs = 
        new ConcurrentLinkedQueue<Request>();
        
    ObserverZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self,
            DataTreeBuilder treeBuilder, ZKDatabase zkDb) throws IOException {
        super(logFactory, self.tickTime, self.minSessionTimeout,
                self.maxSessionTimeout, treeBuilder, zkDb, self);
    }
    
    public Observer getObserver() {
        return self.observer;
    }
    
    @Override
    public Learner getLearner() {
        return self.observer;
    }       
    
    /**
     * Unlike a Follower, which sees a full request only during the PROPOSAL
     * phase, Observers get all the data required with the INFORM packet. 
     * This method commits a request that has been unpacked by from an INFORM
     * received from the Leader. 
     *      
     * @param request
     */
    public void commitRequest(Request request) {     
        commitProcessor.commit(request);        
    }
    
    /**
     * Set up the request processors for an Observer:
     * firstProcesor->commitProcessor->finalProcessor
     */
    @Override
    protected void setupRequestProcessors() {      
        // We might consider changing the processor behaviour of 
        // Observers to, for example, remove the disk sync requirements.
        // Currently, they behave almost exactly the same as followers.
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        commitProcessor = new CommitProcessor(finalProcessor,
                Long.toString(getServerId()), true);
        commitProcessor.start();
        firstProcessor = new ObserverRequestProcessor(this, commitProcessor);
        ((ObserverRequestProcessor) firstProcessor).start();
        syncProcessor = new SyncRequestProcessor(this,
                new SendAckRequestProcessor(getObserver()));
        syncProcessor.start();
    }
    
    /*
     * Process a sync request
     */
    synchronized public void sync(){
        if(pendingSyncs.size() ==0){
            LOG.warn("Not expecting a sync.");
            return;
        }
                
        Request r = pendingSyncs.remove();
        commitProcessor.commit(r);
    }
    
    @Override
    public String getState() {
        return "observer";
    };    
}
