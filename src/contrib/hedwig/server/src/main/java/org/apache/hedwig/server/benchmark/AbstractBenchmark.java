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
package org.apache.hedwig.server.benchmark;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;

import org.apache.hedwig.util.ConcurrencyUtils;

public abstract class AbstractBenchmark {

	static final Logger logger = Logger.getLogger(AbstractBenchmark.class);
    
	AtomicLong totalLatency = new AtomicLong();
    LinkedBlockingQueue<Boolean> doneSignalQueue = new LinkedBlockingQueue<Boolean>();

    abstract void doOps(int numOps) throws Exception;
	abstract void tearDown() throws Exception;
    
	protected class AbstractCallback{
		AtomicInteger numDone = new AtomicInteger(0);
		Semaphore outstanding;
		int numOps;
		boolean logging;
		
		public AbstractCallback(Semaphore outstanding, int numOps) {
			this.outstanding = outstanding;
			this.numOps = numOps;
			logging = Boolean.getBoolean("progress");
		}
    	
		public void handle(boolean success, Object ctx){
            outstanding.release();
            
            if (!success){
                ConcurrencyUtils.put(doneSignalQueue, false);
                return;
            }
            
            totalLatency.addAndGet(System.currentTimeMillis() - (Long)ctx);
            int numDoneInt = numDone.incrementAndGet();
            
            if (logging && numDoneInt % 10000 == 0){
                logger.info("Finished " + numDoneInt + " ops");
            }
            
            if (numOps == numDoneInt){
                ConcurrencyUtils.put(doneSignalQueue, true);
            }   
        }
	}
	
	public void runPhase(String phase, int numOps) throws Exception{
        long startTime = System.currentTimeMillis();
        
        doOps(numOps);
        
        if (!doneSignalQueue.take()){
        	logger.error("One or more operations failed in phase: " + phase);
        	throw new RuntimeException();
        }else{
            logger.info("Phase: " + phase + " Avg latency : " + totalLatency.get() / numOps + ", tput = " + (numOps * 1000/ (System.currentTimeMillis() - startTime)));
        }
	}
	
	
	
	

	public void run() throws Exception{
        
        int numWarmup = Integer.getInteger("nWarmup", 50000);
    	runPhase("warmup", numWarmup);
        
    	logger.info("Sleeping for 10 seconds");
    	Thread.sleep(10000);
        //reset latency
        totalLatency.set(0);
        
        int numOps = Integer.getInteger("nOps", 400000);
        runPhase("real", numOps);

        tearDown();
	}
}
