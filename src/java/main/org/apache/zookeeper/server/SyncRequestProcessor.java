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

import java.io.Flushable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;


/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 */
public class SyncRequestProcessor extends Thread implements RequestProcessor {
    private static final Logger LOG = Logger.getLogger(SyncRequestProcessor.class);
    private ZooKeeperServer zks;
    private LinkedBlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();
    private RequestProcessor nextProcessor;
    Thread snapInProcess = null;
    
    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private LinkedList<Request> toFlush = new LinkedList<Request>();
    private Random r = new Random(System.nanoTime());
    private int logCount = 0;
    /**
     * The number of log entries to log before starting a snapshot
     */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    private Request requestOfDeath = Request.requestOfDeath;

    public SyncRequestProcessor(ZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
    }
    
    /**
     * used by tests to check for changing 
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }
    
    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }
    
    @Override
    public void run() {
        try {
            int randRoll = r.nextInt(snapCount/2);
            while (true) {
                Request si = null;
                if (toFlush.isEmpty()) {
                    si = queuedRequests.take();
                } else {
                    si = queuedRequests.poll();
                    if (si == null) {
                        flush(toFlush);
                        continue;
                    }
                }
                if (si == requestOfDeath) {
                    break;
                }
                if (si != null) {
                    zks.getLogWriter().append(si);
                        logCount++;
                        if (logCount > (snapCount / 2 + randRoll)) {
                            randRoll = r.nextInt(snapCount/2);
                            // roll the log
                            zks.getLogWriter().rollLog();
                            // take a snapshot
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            }
                            else {
                                snapInProcess = new Thread("Snapshot Thread") {
                                    public void run() {
                                     try {
                                         zks.takeSnapshot();
                                     } catch(Exception e) {
                                         LOG.warn("Unexpected exception", e);
                                     }
                                    }
                                };
                                snapInProcess.start();
                            }
                            logCount = 0;
                        }
                    toFlush.add(si);
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.fatal("Severe unrecoverable error, exiting", t);
            System.exit(11);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush(LinkedList<Request> toFlush) throws IOException {
        if (toFlush.size() == 0)
            return;

        zks.getLogWriter().commit();
        while (toFlush.size() > 0) {
            Request i = toFlush.remove();
            nextProcessor.processRequest(i);
        }
        if (nextProcessor instanceof Flushable) {
            ((Flushable)nextProcessor).flush();
        }
    }

    public void shutdown() {
        queuedRequests.add(requestOfDeath);
        nextProcessor.shutdown();
    }

    public void processRequest(Request request) {
        // request.addRQRec(">sync");
        queuedRequests.add(request);
    }

}
