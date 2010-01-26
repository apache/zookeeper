package org.apache.bookkeeper.proto;

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
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

/**
 * Implements the client-side part of the BookKeeper protocol.
 * 
 */
public class BookieClient {
    static final Logger LOG = Logger.getLogger(BookieClient.class);

    // This is global state that should be across all BookieClients
    AtomicLong totalBytesOutstanding = new AtomicLong();

    OrderedSafeExecutor executor;
    ClientSocketChannelFactory channelFactory;
    ConcurrentHashMap<InetSocketAddress, PerChannelBookieClient> channels = new ConcurrentHashMap<InetSocketAddress, PerChannelBookieClient>();

    public BookieClient(ClientSocketChannelFactory channelFactory, OrderedSafeExecutor executor) {
        this.channelFactory = channelFactory;
        this.executor = executor;
    }

    public PerChannelBookieClient lookupClient(InetSocketAddress addr) {
        PerChannelBookieClient channel = channels.get(addr);

        if (channel == null) {
            channel = new PerChannelBookieClient(executor, channelFactory, addr, totalBytesOutstanding);
            PerChannelBookieClient prevChannel = channels.putIfAbsent(addr, channel);
            if (prevChannel != null) {
                channel = prevChannel;
            }
        }

        return channel;
    }

    public void addEntry(final InetSocketAddress addr, final long ledgerId, final byte[] masterKey, final long entryId,
            final ChannelBuffer toSend, final WriteCallback cb, final Object ctx) {

        final PerChannelBookieClient client = lookupClient(addr);

        client.connectIfNeededAndDoOp(new GenericCallback<Void>() {
            @Override
            public void operationComplete(int rc, Void result) {
                if (rc != BKException.Code.OK) {
                    cb.writeComplete(rc, ledgerId, entryId, addr, ctx);
                    return;
                }
                client.addEntry(ledgerId, masterKey, entryId, toSend, cb, ctx);
            }
        });
    }

    public void readEntry(final InetSocketAddress addr, final long ledgerId, final long entryId,
            final ReadEntryCallback cb, final Object ctx) {

        final PerChannelBookieClient client = lookupClient(addr);

        client.connectIfNeededAndDoOp(new GenericCallback<Void>() {
            @Override
            public void operationComplete(int rc, Void result) {

                if (rc != BKException.Code.OK) {
                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                    return;
                }
                client.readEntry(ledgerId, entryId, cb, ctx);
            }
        });
    }

    public void close(){
        for (PerChannelBookieClient channel: channels.values()){
            channel.close();
        }
    }

    private static class Counter {
        int i;
        int total;

        synchronized void inc() {
            i++;
            total++;
        }

        synchronized void dec() {
            i--;
            notifyAll();
        }

        synchronized void wait(int limit) throws InterruptedException {
            while (i > limit) {
                wait();
            }
        }

        synchronized int total() {
            return total;
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws NumberFormatException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("USAGE: BookieClient bookieHost port ledger#");
            return;
        }
        WriteCallback cb = new WriteCallback() {

            public void writeComplete(int rc, long ledger, long entry, InetSocketAddress addr, Object ctx) {
                Counter counter = (Counter) ctx;
                counter.dec();
                if (rc != 0) {
                    System.out.println("rc = " + rc + " for " + entry + "@" + ledger);
                }
            }
        };
        Counter counter = new Counter();
        byte hello[] = "hello".getBytes();
        long ledger = Long.parseLong(args[2]);
        ClientSocketChannelFactory channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors
                .newCachedThreadPool());
        OrderedSafeExecutor executor = new OrderedSafeExecutor(1);
        BookieClient bc = new BookieClient(channelFactory, executor);
        InetSocketAddress addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));

        for (int i = 0; i < 100000; i++) {
            counter.inc();
            bc.addEntry(addr, ledger, new byte[0], i, ChannelBuffers.wrappedBuffer(hello), cb, counter);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
        channelFactory.releaseExternalResources();
        executor.shutdown();
    }
}
