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

import java.io.IOException;
import java.util.concurrent.Executors;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.SyncCounter;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.log4j.Logger;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

/**
 * BookKeeper client. We assume there is one single writer to a ledger at any
 * time.
 * 
 * There are three possible operations: start a new ledger, write to a ledger,
 * and read from a ledger.
 * 
 * The exceptions resulting from synchronous calls and error code resulting from
 * asynchronous calls can be found in the class {@link BKException}.
 * 
 * 
 */

public class BookKeeper implements OpenCallback, CreateCallback {

  static final Logger LOG = Logger.getLogger(BookKeeper.class);

  ZooKeeper zk = null;
  // whether the zk handle is one we created, or is owned by whoever
  // instantiated us
  boolean ownZKHandle = false;

  ClientSocketChannelFactory channelFactory;
  // whether the socket factory is one we created, or is owned by whoever
  // instantiated us
  boolean ownChannelFactory = false;

  BookieClient bookieClient;
  BookieWatcher bookieWatcher;

  OrderedSafeExecutor callbackWorker = new OrderedSafeExecutor(Runtime
      .getRuntime().availableProcessors());
  OrderedSafeExecutor mainWorkerPool = new OrderedSafeExecutor(Runtime
      .getRuntime().availableProcessors());

  /**
   * Create a bookkeeper client. A zookeeper client and a client socket factory
   * will be instantiated as part of this constructor.
   * 
   * @param servers
   *          A list of one of more servers on which zookeeper is running. The
   *          client assumes that the running bookies have been registered with
   *          zookeeper under the path
   *          {@link BookieWatcher#BOOKIE_REGISTRATION_PATH}
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   */
  public BookKeeper(String servers) throws IOException, InterruptedException,
      KeeperException {
    this(new ZooKeeper(servers, 10000, new Watcher() {
      @Override
      public void process(WatchedEvent event) {
        // TODO: handle session disconnects and expires
        if (LOG.isDebugEnabled()) {
          LOG.debug("Process: " + event.getType() + " " + event.getPath());
        }
      }
    }), new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),
        Executors.newCachedThreadPool()));

    ownZKHandle = true;
    ownChannelFactory = true;
  }

  /**
   * Create a bookkeeper client but use the passed in zookeeper client instead
   * of instantiating one.
   * 
   * @param zk
   *          Zookeeper client instance connected to the zookeeper with which
   *          the bookies have registered
   * @throws InterruptedException
   * @throws KeeperException
   */
  public BookKeeper(ZooKeeper zk) throws InterruptedException, KeeperException {
    this(zk, new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),
        Executors.newCachedThreadPool()));
    ownChannelFactory = true;
  }

  /**
   * Create a bookkeeper client but use the passed in zookeeper client and
   * client socket channel factory instead of instantiating those.
   * 
   * @param zk
   *          Zookeeper client instance connected to the zookeeper with which
   *          the bookies have registered
   * @param channelFactory
   *          A factory that will be used to create connections to the bookies
   * @throws InterruptedException
   * @throws KeeperException
   */
  public BookKeeper(ZooKeeper zk, ClientSocketChannelFactory channelFactory)
      throws InterruptedException, KeeperException {
    if (zk == null || channelFactory == null) {
      throw new NullPointerException();
    }
    this.zk = zk;
    this.channelFactory = channelFactory;
    bookieWatcher = new BookieWatcher(this);
    bookieWatcher.readBookiesBlocking();
    bookieClient = new BookieClient(channelFactory, mainWorkerPool);
  }

  /**
   * There are 2 digest types that can be used for verification. The CRC32 is
   * cheap to compute but does not protect against byzantine bookies (i.e., a
   * bookie might report fake bytes and a matching CRC32). The MAC code is more
   * expensive to compute, but is protected by a password, i.e., a bookie can't
   * report fake bytes with a mathching MAC unless it knows the password
   */
  public enum DigestType {
    MAC, CRC32
  };

  public ZooKeeper getZkHandle() {
    return zk;
  }

  /**
   * Creates a new ledger asynchronously. To create a ledger, we need to specify
   * the ensemble size, the quorum size, the digest type, a password, a callback
   * implementation, and an optional control object. The ensemble size is how
   * many bookies the entries should be striped among and the quorum size is the
   * degree of replication of each entry. The digest type is either a MAC or a
   * CRC. Note that the CRC option is not able to protect a client against a
   * bookie that replaces an entry. The password is used not only to
   * authenticate access to a ledger, but also to verify entries in ledgers.
   * 
   * @param ensSize
   *          ensemble size
   * @param qSize
   *          quorum size
   * @param digestType
   *          digest type, either MAC or CRC32
   * @param passwd
   *          password
   * @param cb
   *          createCallback implementation
   * @param ctx
   *          optional control object
   */
  public void asyncCreateLedger(int ensSize, int qSize, DigestType digestType,
      byte[] passwd, CreateCallback cb, Object ctx) {

    new LedgerCreateOp(this, ensSize, qSize, digestType, passwd, cb, ctx)
        .initiate();

  }

  /**
   * Create callback implementation for synchronous create call.
   * 
   * @param rc
   *          return code
   * @param lh
   *          ledger handle object
   * @param ctx
   *          optional control object
   */
  public void createComplete(int rc, LedgerHandle lh, Object ctx) {
    SyncCounter counter = (SyncCounter) ctx;
    counter.setLh(lh);
    counter.setrc(rc);
    counter.dec();
  }

  /**
   * Creates a new ledger. Default of 3 servers, and quorum of 2 servers.
   * 
   * @param digestType
   *          digest type, either MAC or CRC32
   * @param passwd
   *          password
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   * @throws BKException
   */
  public LedgerHandle createLedger(DigestType digestType, byte passwd[])
      throws KeeperException, BKException, InterruptedException, IOException {
    return createLedger(3, 2, digestType, passwd);
  }

  /**
   * Synchronous call to create ledger. Parameters match those of
   * {@link #asyncCreateLedger(int, int, DigestType, byte[], CreateCallback, Object)}
   * 
   * @param ensSize
   * @param qSize
   * @param digestType
   * @param passwd
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   * @throws IOException
   * @throws BKException
   */
  public LedgerHandle createLedger(int ensSize, int qSize,
      DigestType digestType, byte passwd[]) throws KeeperException,
      InterruptedException, IOException, BKException {
    SyncCounter counter = new SyncCounter();
    counter.inc();
    /*
     * Calls asynchronous version
     */
    asyncCreateLedger(ensSize, qSize, digestType, passwd, this, counter);

    /*
     * Wait
     */
    counter.block(0);
    if (counter.getLh() == null) {
      LOG.error("ZooKeeper error: " + counter.getrc());
      throw BKException.create(Code.ZKException);
    }

    return counter.getLh();
  }

  /**
   * Open existing ledger asynchronously for reading.
   * 
   * @param lId
   *          ledger identifier
   * @param digestType
   *          digest type, either MAC or CRC32
   * @param passwd
   *          password
   * @param ctx
   *          optional control object
   */
  public void asyncOpenLedger(long lId, DigestType digestType, byte passwd[],
      OpenCallback cb, Object ctx) {

    new LedgerOpenOp(this, lId, digestType, passwd, cb, ctx).initiate();

  }

  /**
   * Callback method for synchronous open operation
   * 
   * @param rc
   *          return code
   * @param lh
   *          ledger handle
   * @param ctx
   *          optional control object
   */
  public void openComplete(int rc, LedgerHandle lh, Object ctx) {
    SyncCounter counter = (SyncCounter) ctx;
    counter.setLh(lh);

    LOG.debug("Open complete: " + rc);

    counter.setrc(rc);
    counter.dec();
  }

  /**
   * Synchronous open ledger call
   * 
   * @param lId
   *          ledger identifier
   * @param digestType
   *          digest type, either MAC or CRC32
   * @param passwd
   *          password
   * @return
   * @throws InterruptedException
   * @throws BKException
   */

  public LedgerHandle openLedger(long lId, DigestType digestType, byte passwd[])
      throws BKException, InterruptedException {
    SyncCounter counter = new SyncCounter();
    counter.inc();

    /*
     * Calls async open ledger
     */
    asyncOpenLedger(lId, digestType, passwd, this, counter);

    /*
     * Wait
     */
    counter.block(0);
    if (counter.getrc() != BKException.Code.OK)
      throw BKException.create(counter.getrc());

    return counter.getLh();
  }

  /**
   * Shuts down client.
   * 
   */
  public void halt() throws InterruptedException {
    bookieClient.close();
    bookieWatcher.halt();
    if (ownChannelFactory) {
      channelFactory.releaseExternalResources();
    }
    if (ownZKHandle) {
      zk.close();
    }
    callbackWorker.shutdown();
    mainWorkerPool.shutdown();
  }
}
