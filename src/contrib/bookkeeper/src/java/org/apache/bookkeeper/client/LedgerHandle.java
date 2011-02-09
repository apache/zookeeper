package org.apache.bookkeeper.client;

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
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Queue;
import java.util.concurrent.Semaphore;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.client.LedgerMetadata;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.util.SafeRunnable;
import org.apache.bookkeeper.util.StringUtils;

import org.apache.log4j.Logger;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.buffer.ChannelBuffer;

/**
 * Ledger handle contains ledger metadata and is used to access the read and
 * write operations to a ledger.
 */
public class LedgerHandle implements ReadCallback, AddCallback, CloseCallback {
  final static Logger LOG = Logger.getLogger(LedgerHandle.class);

  final byte[] ledgerKey;
  final LedgerMetadata metadata;
  final BookKeeper bk;
  final long ledgerId;
  long lastAddPushed;
  long lastAddConfirmed;
  long length;
  final DigestManager macManager;
  final DistributionSchedule distributionSchedule;

  final Semaphore opCounterSem;
  private Integer throttling = 5000;
  
  final Queue<PendingAddOp> pendingAddOps = new ArrayDeque<PendingAddOp>();
  
  LedgerHandle(BookKeeper bk, long ledgerId, LedgerMetadata metadata,
      DigestType digestType, byte[] password)
      throws GeneralSecurityException, NumberFormatException {
    this.bk = bk;
    this.metadata = metadata;
    if (metadata.isClosed()) {
      lastAddConfirmed = lastAddPushed = metadata.close;
      length = metadata.length;
    } else {
      lastAddConfirmed = lastAddPushed = -1;
      length = 0;
    }
    
    this.ledgerId = ledgerId;
    
    String throttleValue = System.getProperty("throttle");
    if(throttleValue != null){
        this.throttling = new Integer(throttleValue); 
    }
    this.opCounterSem = new Semaphore(throttling);
    
    macManager = DigestManager.instantiate(ledgerId, password, digestType);
    this.ledgerKey = MacDigestManager.genDigest("ledger", password);
    distributionSchedule = new RoundRobinDistributionSchedule(
        metadata.quorumSize, metadata.ensembleSize);
  }
  
  /**
   * Get the id of the current ledger
   * 
   * @return
   */
  public long getId() {
    return ledgerId;
  }

  /**
   * Get the last confirmed entry id on this ledger
   * 
   * @return
   */
  public long getLastAddConfirmed() {
    return lastAddConfirmed;
  }

  /**
   * Get the entry id of the last entry that has been enqueued for addition (but
   * may not have possibly been persited to the ledger)
   * 
   * @return
   */
  public long getLastAddPushed() {
    return lastAddPushed;
  }

  /**
   * Get the Ledger's key/password.
   * 
   * @return byte array for the ledger's key/password.
   */
  public byte[] getLedgerKey() {
      return ledgerKey;
  }
  
  /**
   * Get the LedgerMetadata
   * 
   * @return LedgerMetadata for the LedgerHandle
   */
  public LedgerMetadata getLedgerMetadata() {
      return metadata;
  }
  
  /**
   * Get the DigestManager
   * 
   * @return DigestManager for the LedgerHandle
   */
  public DigestManager getDigestManager() {
      return macManager;
  }
  
  /**
   * Return total number of available slots.
   * 
   * @return int    available slots
   */
  Semaphore getAvailablePermits(){
      return this.opCounterSem;
  }
  
  /**
   *  Add to the length of the ledger in bytes.
   *  
   * @param delta
   * @return
   */
  long addToLength(long delta){
      this.length += delta;
      return this.length;
  }
  
  /**
   * Returns the length of the ledger in bytes. 
   * 
   * @return
   */
  public long getLength(){
      return this.length;
  }
  
  /**
   * Get the Distribution Schedule
   * 
   * @return DistributionSchedule for the LedgerHandle
   */
  public DistributionSchedule getDistributionSchedule() {
      return distributionSchedule;
  }
  
  public void writeLedgerConfig(StatCallback callback, Object ctx) {
    bk.getZkHandle().setData(StringUtils.getLedgerNodePath(ledgerId),
        metadata.serialize(), -1, callback, ctx);
  }

  /**
   * Close this ledger synchronously.
   * 
   */
  public void close() throws InterruptedException {
    SyncCounter counter = new SyncCounter();
    counter.inc();

    asyncClose(this, counter);

    counter.block(0);
  }

  /**
   * Asynchronous close, any adds in flight will return errors
   * 
   * @param cb
   *          callback implementation
   * @param ctx
   *          control object
   * @throws InterruptedException
   */
  public void asyncClose(CloseCallback cb, Object ctx) {
    asyncClose(cb, ctx, BKException.Code.LedgerClosedException);
  }

  /**
   * Same as public version of asynClose except that this one takes an
   * additional parameter which is the return code to hand to all the pending
   * add ops
   * 
   * @param cb
   * @param ctx
   * @param rc
   */
  private void asyncClose(final CloseCallback cb, final Object ctx, final int rc) {

    bk.mainWorkerPool.submitOrdered(ledgerId, new SafeRunnable() {

      @Override
      public void safeRun() {
        metadata.length = length;
        // Close operation is idempotent, so no need to check if we are
        // already closed
        metadata.close(lastAddConfirmed);
        errorOutPendingAdds(rc);
        lastAddPushed = lastAddConfirmed;

        if (LOG.isDebugEnabled()) {
          LOG.debug("Closing ledger: " + ledgerId + " at entryId: "
              + metadata.close + " with this many bytes: " + metadata.length);
        }

        writeLedgerConfig(new StatCallback() {
          @Override
          public void processResult(int rc, String path, Object subctx,
              Stat stat) {
            if (rc != KeeperException.Code.OK.intValue()) {
              cb.closeComplete(BKException.Code.ZKException, LedgerHandle.this,
                  ctx);
            } else {
              cb.closeComplete(BKException.Code.OK, LedgerHandle.this, ctx);
            }
          }
        }, null);

      }
    });
  }

  /**
   * Read a sequence of entries synchronously.
   * 
   * @param firstEntry
   *          id of first entry of sequence (included)
   * @param lastEntry
   *          id of last entry of sequence (included)
   * 
   */
  public Enumeration<LedgerEntry> readEntries(long firstEntry, long lastEntry)
      throws InterruptedException, BKException {
    SyncCounter counter = new SyncCounter();
    counter.inc();

    asyncReadEntries(firstEntry, lastEntry, this, counter);

    counter.block(0);
    if (counter.getrc() != BKException.Code.OK) {
      throw BKException.create(counter.getrc());
    }

    return counter.getSequence();
  }

  /**
   * Read a sequence of entries asynchronously.
   * 
   * @param firstEntry
   *          id of first entry of sequence
   * @param lastEntry
   *          id of last entry of sequence
   * @param cb
   *          object implementing read callback interface
   * @param ctx
   *          control object
   */
  public void asyncReadEntries(long firstEntry, long lastEntry,
      ReadCallback cb, Object ctx) {
    // Little sanity check
    if (firstEntry < 0 || lastEntry > lastAddConfirmed
        || firstEntry > lastEntry) {
      cb.readComplete(BKException.Code.ReadException, this, null, ctx);
      return;
    }

    try{
        new PendingReadOp(this, firstEntry, lastEntry, cb, ctx).initiate();
  
    } catch (InterruptedException e) {
        cb.readComplete(BKException.Code.InterruptedException, this, null, ctx);
    }
  }

  /**
   * Add entry synchronously to an open ledger.
   * 
   * @param data
   *         array of bytes to be written to the ledger
   */

  public long addEntry(byte[] data) throws InterruptedException, BKException {
    LOG.debug("Adding entry " + data);
    SyncCounter counter = new SyncCounter();
    counter.inc();

    asyncAddEntry(data, this, counter);
    counter.block(0);

    return counter.getrc();
  }

  /**
   * Add entry asynchronously to an open ledger.
   * 
   * @param data
   *          array of bytes to be written
   * @param cb
   *          object implementing callbackinterface
   * @param ctx
   *          some control object
   */
  public void asyncAddEntry(final byte[] data, final AddCallback cb,
      final Object ctx) {
      try{
          opCounterSem.acquire();
      } catch (InterruptedException e) {
          cb.addComplete(BKException.Code.InterruptedException,
                  LedgerHandle.this, -1, ctx);
      }
      
      try{
          bk.mainWorkerPool.submitOrdered(ledgerId, new SafeRunnable() {
              @Override
              public void safeRun() {
                  if (metadata.isClosed()) {
                      LOG.warn("Attempt to add to closed ledger: " + ledgerId);
                      LedgerHandle.this.opCounterSem.release();
                      cb.addComplete(BKException.Code.LedgerClosedException,
                              LedgerHandle.this, -1, ctx);
                      return;
                  }

                  long entryId = ++lastAddPushed;
                  long currentLength = addToLength(data.length);
                  PendingAddOp op = new PendingAddOp(LedgerHandle.this, cb, ctx, entryId);
                  pendingAddOps.add(op);
                  ChannelBuffer toSend = macManager.computeDigestAndPackageForSending(
                          entryId, lastAddConfirmed, currentLength, data);
                  op.initiate(toSend);
              }
          });
      } catch (RuntimeException e) {
          opCounterSem.release();
          throw e;
      }
  }

  // close the ledger and send fails to all the adds in the pipeline
  void handleUnrecoverableErrorDuringAdd(int rc) {
    asyncClose(NoopCloseCallback.instance, null, rc);
  }

  void errorOutPendingAdds(int rc) {
    PendingAddOp pendingAddOp;
    while ((pendingAddOp = pendingAddOps.poll()) != null) {
      pendingAddOp.submitCallback(rc);
    }
  }

  void sendAddSuccessCallbacks() {
    // Start from the head of the queue and proceed while there are
    // entries that have had all their responses come back
    PendingAddOp pendingAddOp;
    while ((pendingAddOp = pendingAddOps.peek()) != null) {
      if (pendingAddOp.numResponsesPending != 0) {
        return;
      }
      pendingAddOps.remove();
      lastAddConfirmed = pendingAddOp.entryId;
      pendingAddOp.submitCallback(BKException.Code.OK);
    }

  }

  void handleBookieFailure(InetSocketAddress addr, final int bookieIndex) {
    InetSocketAddress newBookie;

    if (LOG.isDebugEnabled()) {
      LOG.debug("Handling failure of bookie: " + addr + " index: "
          + bookieIndex);
    }

    try {
      newBookie = bk.bookieWatcher
          .getAdditionalBookie(metadata.currentEnsemble);
    } catch (BKNotEnoughBookiesException e) {
      LOG
          .error("Could not get additional bookie to remake ensemble, closing ledger: "
              + ledgerId);
      handleUnrecoverableErrorDuringAdd(e.getCode());
      return;
    }

    final ArrayList<InetSocketAddress> newEnsemble = new ArrayList<InetSocketAddress>(
        metadata.currentEnsemble);
    newEnsemble.set(bookieIndex, newBookie);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Changing ensemble from: " + metadata.currentEnsemble + " to: "
          + newEnsemble + " for ledger: " + ledgerId + " starting at entry: "
          + (lastAddConfirmed + 1));
    }

    metadata.addEnsemble(lastAddConfirmed + 1, newEnsemble);

    writeLedgerConfig(new StatCallback() {
      @Override
      public void processResult(final int rc, String path, Object ctx, Stat stat) {

        bk.mainWorkerPool.submitOrdered(ledgerId, new SafeRunnable() {
          @Override
          public void safeRun() {
            if (rc != KeeperException.Code.OK.intValue()) {
              LOG
                  .error("Could not persist ledger metadata while changing ensemble to: "
                      + newEnsemble + " , closing ledger");
              handleUnrecoverableErrorDuringAdd(BKException.Code.ZKException);
              return;
            }

            for (PendingAddOp pendingAddOp : pendingAddOps) {
              pendingAddOp.unsetSuccessAndSendWriteRequest(bookieIndex);
            }
          }
        });

      }
    }, null);

  }

  void recover(GenericCallback<Void> cb) {
    if (metadata.isClosed()) {
      // We are already closed, nothing to do
      cb.operationComplete(BKException.Code.OK, null);
      return;
    }

    new LedgerRecoveryOp(this, cb).initiate();
  }

  static class NoopCloseCallback implements CloseCallback {
    static NoopCloseCallback instance = new NoopCloseCallback();

    @Override
    public void closeComplete(int rc, LedgerHandle lh, Object ctx) {
      // noop
    }
  }

  /**
   * Implementation of callback interface for synchronous read method.
   * 
   * @param rc
   *          return code
   * @param leder
   *          ledger identifier
   * @param seq
   *          sequence of entries
   * @param ctx
   *          control object
   */
  public void readComplete(int rc, LedgerHandle lh,
      Enumeration<LedgerEntry> seq, Object ctx) {

    SyncCounter counter = (SyncCounter) ctx;
    synchronized (counter) {
      counter.setSequence(seq);
      counter.setrc(rc);
      counter.dec();
      counter.notify();
    }
  }

  /**
   * Implementation of callback interface for synchronous read method.
   * 
   * @param rc
   *          return code
   * @param leder
   *          ledger identifier
   * @param entry
   *          entry identifier
   * @param ctx
   *          control object
   */
  public void addComplete(int rc, LedgerHandle lh, long entry, Object ctx) {
    SyncCounter counter = (SyncCounter) ctx;

    counter.setrc(rc);
    counter.dec();
  }

  /**
   * Close callback method
   * 
   * @param rc
   * @param lh
   * @param ctx
   */
  public void closeComplete(int rc, LedgerHandle lh, Object ctx) {

    SyncCounter counter = (SyncCounter) ctx;
    counter.setrc(rc);
    synchronized (counter) {
      counter.dec();
      counter.notify();
    }

  }
}
