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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooDefs.OpCode;


/**
 * Basic Server Statistics
 */
public class ServerStats {
	
	private static final Logger LOG = Logger.getLogger(ServerStats.class);
	
    private long packetsSent;
    private long packetsReceived;
    private long maxLatency;
    private long minLatency = Long.MAX_VALUE;
    private long totalLatency = 0;
    private long count = 0;

    public final static String SERVER_PROCESS_STATS = "server_process_stats"; 
    
    //Add by nileader@gmail.com @see https://issues.apache.org/jira/browse/ZOOKEEPER-1804
    final public static ConcurrentHashMap< Integer/**OpCode,request type*/, ServerProcessStats > real_time_sps = new ConcurrentHashMap< Integer, ServerProcessStats >();
    
    private final Provider provider;

    public interface Provider {
        public long getOutstandingRequests();
        public long getLastProcessedZxid();
        public String getState();
    }
    
    
    static class ServerProcessStats{
    
    	private int type;
    	private AtomicLong req_total = new AtomicLong();
    	private double req_rwps = 0;
    	
    	public ServerProcessStats( int type ){
    		this.type = type;
    	}
    	
    	/**
    	 * increment rwps to req_total
    	 */
    	public void incrementRWps(){
    		req_total.incrementAndGet();
    	}
    	
    	/**
    	 * real time rwps.(req_rwps)
    	 * @return
    	 */
    	public double getRWps(){
    		return req_rwps;
    	}
    	/**
    	 * Calculate rwps from req_total to req_rwps
    	 * @param time s
    	 */
    	public void calculateRWps( double time ){
    		req_rwps = req_total.getAndSet( 0 ) / ( time );
    	}
    }
    
	static {

		if ( "true".equalsIgnoreCase( System.getProperty( SERVER_PROCESS_STATS, "false" ) ) ) {
			// init the real_time_sps.
			real_time_sps.put( OpCode.create, new ServerProcessStats( OpCode.create ) );
			real_time_sps.put( OpCode.setWatches, new ServerProcessStats( OpCode.setWatches ) );
			real_time_sps.put( OpCode.delete, new ServerProcessStats( OpCode.delete ) );
			real_time_sps.put( OpCode.exists, new ServerProcessStats( OpCode.exists ) );
			real_time_sps.put( OpCode.getData, new ServerProcessStats( OpCode.getData ) );
			real_time_sps.put( OpCode.setData, new ServerProcessStats( OpCode.setData ) );
			real_time_sps.put( OpCode.getChildren, new ServerProcessStats( OpCode.getChildren ) );
			real_time_sps.put( OpCode.getChildren2, new ServerProcessStats( OpCode.getChildren2 ) );
			real_time_sps.put( OpCode.createSession, new ServerProcessStats( OpCode.createSession ) );
			real_time_sps.put( OpCode.closeSession, new ServerProcessStats( OpCode.closeSession ) );

			new Thread( new Runnable() {
				public void run() {
					LOG.info( "[ServerProcessStats]ServerProcessStats started." );
					while ( true ) {
						long start = System.currentTimeMillis();
						try {
							Thread.sleep( 1000 * 10 );
						} catch ( InterruptedException e ) {
						}
						try {
							long time = System.currentTimeMillis() - start;
							for ( int type : real_time_sps.keySet() ) {
								real_time_sps.get( type ).calculateRWps( time / 1000.0 );
							}
						} catch ( Throwable e ) {
							LOG.error( "[ServerProcessStats]Error when ServerProcessStats due to " + e.getMessage(), e );
						}
					}
				}
			} ).start();
		}
	}
    
    public ServerStats(Provider provider) {
        this.provider = provider;
    }
    
    // getters
    synchronized public long getMinLatency() {
        return minLatency == Long.MAX_VALUE ? 0 : minLatency;
    }

    synchronized public long getAvgLatency() {
        if (count != 0) {
            return totalLatency / count;
        }
        return 0;
    }

    synchronized public long getMaxLatency() {
        return maxLatency;
    }

    public long getOutstandingRequests() {
        return provider.getOutstandingRequests();
    }
    
    public long getLastProcessedZxid(){
        return provider.getLastProcessedZxid();
    }
    
    synchronized public long getPacketsReceived() {
        return packetsReceived;
    }

    synchronized public long getPacketsSent() {
        return packetsSent;
    }

    public String getServerState() {
        return provider.getState();
    }
    
    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder();
        sb.append("Latency min/avg/max: " + getMinLatency() + "/"
                + getAvgLatency() + "/" + getMaxLatency() + "\n");
        sb.append("Received: " + getPacketsReceived() + "\n");
        sb.append("Sent: " + getPacketsSent() + "\n");
        if (provider != null) {
            sb.append("Outstanding: " + getOutstandingRequests() + "\n");
            sb.append("Zxid: 0x"+ Long.toHexString(getLastProcessedZxid())+ "\n");
        }
        sb.append("Mode: " + getServerState() + "\n");
        return sb.toString();
    }
    // mutators
    synchronized void updateLatency(long requestCreateTime) {
        long latency = System.currentTimeMillis() - requestCreateTime;
        totalLatency += latency;
        count++;
        if (latency < minLatency) {
            minLatency = latency;
        }
        if (latency > maxLatency) {
            maxLatency = latency;
        }
    }
    synchronized public void resetLatency(){
        totalLatency = 0;
        count = 0;
        maxLatency = 0;
        minLatency = Long.MAX_VALUE;
    }
    synchronized public void resetMaxLatency(){
        maxLatency = getMinLatency();
    }
    synchronized public void incrementPacketsReceived() {
        packetsReceived++;
    }
    synchronized public void incrementPacketsSent() {
        packetsSent++;
    }
    synchronized public void resetRequestCounters(){
        packetsReceived = 0;
        packetsSent = 0;
    }
    
    synchronized public void reset() {
        resetLatency();
        resetRequestCounters();
    }
}
