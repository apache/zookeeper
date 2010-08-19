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

import java.util.Random;
import java.util.concurrent.Semaphore;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.log4j.Logger;

public class BookkeeperBenchmark extends AbstractBenchmark{
    
    static final Logger logger = Logger.getLogger(BookkeeperBenchmark.class);
    
    BookKeeper bk;
    LedgerHandle[] lh;
    
    public BookkeeperBenchmark(String zkHostPort) throws Exception{
        bk = new BookKeeper(zkHostPort);
        int numLedgers = Integer.getInteger("nLedgers",5);
        lh = new LedgerHandle[numLedgers];
        int quorumSize = Integer.getInteger("quorum", 2);
        int ensembleSize = Integer.getInteger("ensemble", 4);
        DigestType digestType = DigestType.valueOf(System.getProperty("digestType", "CRC32"));
        for (int i=0; i< numLedgers; i++){
            lh[i] = bk.createLedger(ensembleSize, quorumSize, digestType, "blah".getBytes());
        }
        
    }
    
    
    @Override
    void doOps(final int numOps) throws Exception {
    	int size = Integer.getInteger("size", 1024);
    	byte[] msg = new byte[size];
        
    	int numOutstanding = Integer.getInteger("nPars",1000);
        final Semaphore outstanding = new Semaphore(numOutstanding);

        AddCallback callback = new AddCallback() {
            	AbstractCallback handler = new AbstractCallback(outstanding, numOps);
            	

        	@Override
            public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                handler.handle(rc == BKException.Code.OK, ctx);   
        	}
        				
		};

        
        
        Random rand = new Random();
    	
    	for (int i=0; i<numOps; i++){
            outstanding.acquire();
            lh[rand.nextInt(lh.length)].asyncAddEntry(msg, callback, System.currentTimeMillis());
        }
        
    	
    }
 
    @Override
	public void tearDown() throws Exception{
        bk.halt();
    }
    
    
    public static void main(String[] args) throws Exception{
        BookkeeperBenchmark benchmark = new BookkeeperBenchmark(args[0]);
        benchmark.run();
    }
}
