package org.apache.bookkeeper.test;
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


import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.lang.InterruptedException;
import java.util.Arrays;

import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;


/**
 * This class tests BookieClient. It just sends the a new entry to itself.
 * 
 * 
 *
 */


class LoopbackClient implements WriteCallback {
    Logger LOG = Logger.getLogger(LoopbackClient.class);
    BookieClient client;
    static int recvTimeout = 2000;
    long begin = 0;
    int limit;
    
    
    static class Counter {
        int c;
        int limit;
        
        Counter(int limit){
            this.c = 0;
            this.limit = limit;
        }
        
        synchronized void increment(){
            if(++c == limit) 
                this.notify();
        }
    }
    
    LoopbackClient(int port, long begin, int limit)
    throws IOException {
        this.client = 
            new BookieClient(new InetSocketAddress("127.0.0.1", port), recvTimeout);
        this.begin = begin;
    }
    
    
    void write(long ledgerId, long entry, byte[] data, WriteCallback cb, Object ctx)
    throws IOException, InterruptedException {
        LOG.info("Ledger id: " + ledgerId + ", Entry: " + entry);
        byte[] passwd = new byte[20];
        Arrays.fill(passwd, (byte) 'a');
        
        client.addEntry(ledgerId, 
            passwd,
            entry, 
            ByteBuffer.wrap(data), 
            cb,
            ctx);
    }
    
    public void writeComplete(int rc, long ledgerId, long entryId, Object ctx){
        Counter counter = (Counter) ctx;
        counter.increment();
    }
    
    
    public static void main(String args[]){
        byte[] data = new byte[Integer.parseInt(args[0])];
        Integer limit = Integer.parseInt(args[1]);
        Counter c = new Counter(limit);
        long ledgerId = Long.valueOf("0").longValue();
        long begin = System.currentTimeMillis();
        
        LoopbackClient lb;
        try{
            lb = new LoopbackClient(Integer.valueOf(args[2]).intValue(), 
                    begin, 
                    limit.intValue());
        
            for(int i = 0; i < limit ; i++){
                lb.write(ledgerId, i, data, lb, c);   
            }
            
            synchronized(c){
                c.wait();
                System.out.println("Time to write all entries: " + (System.currentTimeMillis() - begin));
            }
        } catch (IOException e){
            e.printStackTrace();
        } catch (InterruptedException e){
            e.printStackTrace();
        }
    } 
    
}
