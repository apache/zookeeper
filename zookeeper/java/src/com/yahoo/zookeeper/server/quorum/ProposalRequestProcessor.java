/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.quorum;

import com.yahoo.zookeeper.ZooDefs;
import com.yahoo.zookeeper.server.Request;
import com.yahoo.zookeeper.server.RequestProcessor;
import com.yahoo.zookeeper.server.SyncRequestProcessor;

/**
 * This RequestProcessor simply forwards requests to an AckRequestProcessor and
 * SyncRequestProcessor.
 */
public class ProposalRequestProcessor implements RequestProcessor {
    LeaderZooKeeperServer zks;

    RequestProcessor nextProcessor;

    SyncRequestProcessor syncProcessor;

    public ProposalRequestProcessor(LeaderZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        AckRequestProcessor ackProcessor = new AckRequestProcessor(zks.leader);
        syncProcessor = new SyncRequestProcessor(zks, ackProcessor);
    }

    public void processRequest(Request request) {
        // ZooLog.logWarn("Ack>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = " + request.sessionId);
        // request.addRQRec(">prop");
    	    	
    	
    	if(request.type == ZooDefs.OpCode.sync){
    		if(zks.leader.syncHandler.containsKey(request.sessionId)){
    			zks.leader.processSync(request);
    		}
    		else{
    			nextProcessor.processRequest(request);
    			zks.commitProcessor.commit(request);
    		}
    	}
    	else{
    		nextProcessor.processRequest(request);
    		if (request.hdr != null) {
    			// We need to sync and get consensus on any transactions
    			zks.leader.propose(request);
    			syncProcessor.processRequest(request);
    		}
    	}
    }

    public void shutdown() {
        nextProcessor.shutdown();
        syncProcessor.shutdown();
    }

}
