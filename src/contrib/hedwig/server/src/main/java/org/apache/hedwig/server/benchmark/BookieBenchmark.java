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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.log4j.Logger;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

public class BookieBenchmark extends AbstractBenchmark{
    
    static final Logger logger = Logger.getLogger(BookkeeperBenchmark.class);
    
    BookieClient bkc;
    InetSocketAddress addr;
    ClientSocketChannelFactory channelFactory;
    OrderedSafeExecutor executor = new OrderedSafeExecutor(1);
    
    
    public BookieBenchmark(String bookieHostPort)  throws Exception{
    	channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
    	bkc = new BookieClient(channelFactory, executor);
    	String[] hostPort = bookieHostPort.split(":");
        addr = new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]));
        
    }
    
    
    @Override
	void doOps(final int numOps) throws Exception{
    	int numOutstanding = Integer.getInteger("nPars",1000);
        final Semaphore outstanding = new Semaphore(numOutstanding);
        
        
        WriteCallback callback = new WriteCallback() {
        	AbstractCallback handler = new AbstractCallback(outstanding, numOps);
        	
        	@Override
            public void writeComplete(int rc, long ledgerId, long entryId,
            		InetSocketAddress addr, Object ctx) {
                handler.handle(rc == BKException.Code.OK, ctx);   
            }
        };
        
    	byte[] passwd = new byte[20];
    	int size = Integer.getInteger("size", 1024);
        byte[] data = new byte[size];
    	
    	for (int i=0; i<numOps; i++){
        	outstanding.acquire();
            
        	ByteBuffer buffer = ByteBuffer.allocate(44);
        	long ledgerId = 1000;
        	buffer.putLong(ledgerId);
            buffer.putLong(i);
        	buffer.putLong(0);
        	buffer.put(passwd);
            buffer.rewind();
            ChannelBuffer toSend = ChannelBuffers.wrappedBuffer(ChannelBuffers.wrappedBuffer(buffer.slice()), ChannelBuffers.wrappedBuffer(data));
            bkc.addEntry(addr, ledgerId, passwd, i, toSend, callback, System.currentTimeMillis());            
        }

    }
    
    @Override
	public void tearDown(){        
        bkc.close();
        channelFactory.releaseExternalResources();
        executor.shutdown();
    }
    
    
    public static void main(String[] args) throws Exception{
        BookieBenchmark benchmark = new BookieBenchmark(args[0]);
        benchmark.run();
    }


}
