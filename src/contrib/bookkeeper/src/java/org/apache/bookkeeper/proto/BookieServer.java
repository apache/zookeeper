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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.apache.bookkeeper.bookie.Bookie;
import org.apache.bookkeeper.bookie.BookieException;
import org.apache.bookkeeper.proto.NIOServerFactory.Cnxn;
import org.apache.log4j.Logger;

/**
 * Implements the server-side part of the BookKeeper protocol.
 * 
 */
public class BookieServer implements NIOServerFactory.PacketProcessor, BookkeeperInternalCallbacks.WriteCallback {
    int port;
    NIOServerFactory nioServerFactory;
    private volatile boolean running = false;
    Bookie bookie;
    static Logger LOG = Logger.getLogger(BookieServer.class);

    public BookieServer(int port, String zkServers, File journalDirectory, File ledgerDirectories[]) throws IOException {
        this.port = port;
        this.bookie = new Bookie(port, zkServers, journalDirectory, ledgerDirectories);
    }

    public void start() throws IOException {
        nioServerFactory = new NIOServerFactory(port, this);
        running = true;
    }

    public void shutdown() throws InterruptedException {
        running = false;
        nioServerFactory.shutdown();
        bookie.shutdown();
    }

    public boolean isRunning(){
        return bookie.isRunning() && nioServerFactory.isRunning() && running;
    }

    public void join() throws InterruptedException {
        nioServerFactory.join();
    }

    /**
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 4) {
            System.err.println("USAGE: BookieServer port zkServers journalDirectory ledgerDirectory [ledgerDirectory]*");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String zkServers = args[1];
        File journalDirectory = new File(args[2]);
        File ledgerDirectory[] = new File[args.length - 3];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ledgerDirectory.length; i++) {
            ledgerDirectory[i] = new File(args[i + 3]);
            if (i != 0) {
                sb.append(',');
            }
            sb.append(ledgerDirectory[i]);
        }
        String hello = String.format(
                "Hello, I'm your bookie, listening on port %1$s. ZKServers are on %2$s. Journals are in %3$s. Ledgers are stored in %4$s.",
                port, zkServers, journalDirectory, sb);
        LOG.info(hello);
        BookieServer bs = new BookieServer(port, zkServers, journalDirectory, ledgerDirectory);
        bs.start();
        bs.join();
    }

    public void processPacket(ByteBuffer packet, Cnxn src) {
        int type = packet.getInt();
        switch (type) {
        case BookieProtocol.ADDENTRY:
            try {
                byte[] masterKey = new byte[20];
                packet.get(masterKey, 0, 20);
                // LOG.debug("Master key: " + new String(masterKey));
                bookie.addEntry(packet.slice(), this, src, masterKey);
            } catch (IOException e) {
                ByteBuffer bb = packet.duplicate();

                long ledgerId = bb.getLong();
                long entryId = bb.getLong();
                LOG.error("Error writing " + entryId + "@" + ledgerId, e);
                ByteBuffer eio = ByteBuffer.allocate(8 + 16);
                eio.putInt(type);
                eio.putInt(BookieProtocol.EIO);
                eio.putLong(ledgerId);
                eio.putLong(entryId);
                eio.flip();
                src.sendResponse(new ByteBuffer[] { eio });
            } catch (BookieException e) {
                ByteBuffer bb = packet.duplicate();
                long ledgerId = bb.getLong();
                long entryId = bb.getLong();

                LOG.error("Unauthorized access to ledger " + ledgerId);

                ByteBuffer eio = ByteBuffer.allocate(8 + 16);
                eio.putInt(type);
                eio.putInt(BookieProtocol.EUA);
                eio.putLong(ledgerId);
                eio.putLong(entryId);
                eio.flip();
                src.sendResponse(new ByteBuffer[] { eio });
            }
            break;
        case BookieProtocol.READENTRY:
            ByteBuffer[] rsp = new ByteBuffer[2];
            ByteBuffer rc = ByteBuffer.allocate(8 + 8 + 8);
            rsp[0] = rc;
            rc.putInt(type);

            long ledgerId = packet.getLong();
            long entryId = packet.getLong();
            LOG.debug("Received new read request: " + ledgerId + ", " + entryId);
            try {
                rsp[1] = bookie.readEntry(ledgerId, entryId);
                LOG.debug("##### Read entry ##### " + rsp[1].remaining());
                rc.putInt(BookieProtocol.EOK);
            } catch (Bookie.NoLedgerException e) {
                if (LOG.isTraceEnabled()) {
                    LOG.error("Error reading " + entryId + "@" + ledgerId, e);
                }
                rc.putInt(BookieProtocol.ENOLEDGER);
            } catch (Bookie.NoEntryException e) {
                if (LOG.isTraceEnabled()) {
                    LOG.error("Error reading " + entryId + "@" + ledgerId, e);
                }
                rc.putInt(BookieProtocol.ENOENTRY);
            } catch (IOException e) {
                if (LOG.isTraceEnabled()) {
                    LOG.error("Error reading " + entryId + "@" + ledgerId, e);
                }
                rc.putInt(BookieProtocol.EIO);
            }
            rc.putLong(ledgerId);
            rc.putLong(entryId);
            rc.flip();
            if (LOG.isTraceEnabled()) {
                int rcCode = rc.getInt();
                rc.rewind();
                LOG.trace("Read entry rc = " + rcCode + " for " + entryId + "@" + ledgerId);
            }
            if (rsp[1] == null) {
                // We haven't filled in entry data, so we have to send back
                // the ledger and entry ids here
                rsp[1] = ByteBuffer.allocate(16);
                rsp[1].putLong(ledgerId);
                rsp[1].putLong(entryId);
                rsp[1].flip();
            }
            LOG.debug("Sending response for: " + entryId + ", " + new String(rsp[1].array()));
            src.sendResponse(rsp);
            break;
        default:
            ByteBuffer badType = ByteBuffer.allocate(8);
            badType.putInt(type);
            badType.putInt(BookieProtocol.EBADREQ);
            badType.flip();
            src.sendResponse(new ByteBuffer[] { packet });
        }
    }

    public void writeComplete(int rc, long ledgerId, long entryId, InetSocketAddress addr, Object ctx) {
        Cnxn src = (Cnxn) ctx;
        ByteBuffer bb = ByteBuffer.allocate(24);
        bb.putInt(BookieProtocol.ADDENTRY);
        bb.putInt(rc);
        bb.putLong(ledgerId);
        bb.putLong(entryId);
        bb.flip();
        if (LOG.isTraceEnabled()) {
            LOG.trace("Add entry rc = " + rc + " for " + entryId + "@" + ledgerId);
        }
        src.sendResponse(new ByteBuffer[] { bb });
    }
    
}
