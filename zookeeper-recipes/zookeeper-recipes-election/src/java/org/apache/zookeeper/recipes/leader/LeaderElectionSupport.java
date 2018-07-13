/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.recipes.leader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A leader election support library implementing the ZooKeeper election recipe.
 * </p>
 * <p>
 * This support library is meant to simplify the construction of an exclusive
 * leader system on top of Apache ZooKeeper. Any application that can become the
 * leader (usually a process that provides a service, exclusively) would
 * configure an instance of this class with their hostname, at least one
 * listener (an implementation of {@link LeaderElectionAware}), and either an
 * instance of {@link ZooKeeper} or the proper connection information. Once
 * configured, invoking {@link #start()} will cause the client to connect to
 * ZooKeeper and create a leader offer. The library then determines if it has
 * been elected the leader using the algorithm described below. The client
 * application can follow all state transitions via the listener callback.
 * </p>
 * <p>
 * Leader election algorithm
 * </p>
 * <p>
 * The library starts in a START state. Through each state transition, a state
 * start and a state complete event are sent to all listeners. When
 * {@link #start()} is called, a leader offer is created in ZooKeeper. A leader
 * offer is an ephemeral sequential node that indicates a process that can act
 * as a leader for this service. A read of all leader offers is then performed.
 * The offer with the lowest sequence number is said to be the leader. The
 * process elected leader will transition to the leader state. All other
 * processes will transition to a ready state. Internally, the library creates a
 * ZooKeeper watch on the leader offer with the sequence ID of N - 1 (where N is
 * the process's sequence ID). If that offer disappears due to a process
 * failure, the watching process will run through the election determination
 * process again to see if it should become the leader. Note that sequence ID
 * may not be contiguous due to failed processes. A process may revoke its offer
 * to be the leader at any time by calling {@link #stop()}.
 * </p>
 * <p>
 * Guarantees (not) Made and Caveats
 * </p>
 * <p>
 * <ul>
 * <li>It is possible for a (poorly implemented) process to create a leader
 * offer, get the lowest sequence ID, but have something terrible occur where it
 * maintains its connection to ZK (and thus its ephemeral leader offer node) but
 * doesn't actually provide the service in question. It is up to the user to
 * ensure any failure to become the leader - and whatever that means in the
 * context of the user's application - results in a revocation of its leader
 * offer (i.e. that {@link #stop()} is called).</li>
 * <li>It is possible for ZK timeouts and retries to play a role in service
 * liveliness. In other words, if process A has the lowest sequence ID but
 * requires a few attempts to read the other leader offers' sequence IDs,
 * election can seem slow. Users should apply timeouts during the determination
 * process if they need to hit a specific SLA.</li>
 * <li>The library makes a "best effort" to detect catastrophic failures of the
 * process. It is possible that an unforeseen event results in (for instance) an
 * unchecked exception that propagates passed normal error handling code. This
 * normally doesn't matter as the same exception would almost certain destroy
 * the entire process and thus the connection to ZK and the leader offer
 * resulting in another round of leader determination.</li>
 * </ul>
 * </p>
 */
public class LeaderElectionSupport implements Watcher {

  private static final Logger logger = LoggerFactory
      .getLogger(LeaderElectionSupport.class);

  private ZooKeeper zooKeeper;

  private State state;
  private Set<LeaderElectionAware> listeners;

  private String rootNodeName;
  private LeaderOffer leaderOffer;
  private String hostName;

  public LeaderElectionSupport() {
    state = State.STOP;
    listeners = Collections.synchronizedSet(new HashSet<LeaderElectionAware>());
  }

  /**
   * <p>
   * Start the election process. This method will create a leader offer,
   * determine its status, and either become the leader or become ready. If an
   * instance of {@link ZooKeeper} has not yet been configured by the user, a
   * new instance is created using the connectString and sessionTime specified.
   * </p>
   * <p>
   * Any (anticipated) failures result in a failed event being sent to all
   * listeners.
   * </p>
   */
  public synchronized void start() {
    state = State.START;
    dispatchEvent(EventType.START);

    logger.info("Starting leader election support");

    if (zooKeeper == null) {
      throw new IllegalStateException(
          "No instance of zookeeper provided. Hint: use setZooKeeper()");
    }

    if (hostName == null) {
      throw new IllegalStateException(
          "No hostname provided. Hint: use setHostName()");
    }

    try {
      makeOffer();
      determineElectionStatus();
    } catch (KeeperException e) {
      becomeFailed(e);
      return;
    } catch (InterruptedException e) {
      becomeFailed(e);
      return;
    }
  }

  /**
   * Stops all election services, revokes any outstanding leader offers, and
   * disconnects from ZooKeeper.
   */
  public synchronized void stop() {
    state = State.STOP;
    dispatchEvent(EventType.STOP_START);

    logger.info("Stopping leader election support");

    if (leaderOffer != null) {
      try {
        zooKeeper.delete(leaderOffer.getNodePath(), -1);
        logger.info("Removed leader offer {}", leaderOffer.getNodePath());
      } catch (InterruptedException e) {
        becomeFailed(e);
      } catch (KeeperException e) {
        becomeFailed(e);
      }
    }

    dispatchEvent(EventType.STOP_COMPLETE);
  }

  private void makeOffer() throws KeeperException, InterruptedException {
    state = State.OFFER;
    dispatchEvent(EventType.OFFER_START);

    leaderOffer = new LeaderOffer();

    leaderOffer.setHostName(hostName);
    leaderOffer.setNodePath(zooKeeper.create(rootNodeName + "/" + "n_",
        hostName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL_SEQUENTIAL));

    logger.debug("Created leader offer {}", leaderOffer);

    dispatchEvent(EventType.OFFER_COMPLETE);
  }

  private void determineElectionStatus() throws KeeperException,
      InterruptedException {

    state = State.DETERMINE;
    dispatchEvent(EventType.DETERMINE_START);

    String[] components = leaderOffer.getNodePath().split("/");

    leaderOffer.setId(Integer.valueOf(components[components.length - 1]
        .substring("n_".length())));

    List<LeaderOffer> leaderOffers = toLeaderOffers(zooKeeper.getChildren(
        rootNodeName, false));

    /*
     * For each leader offer, find out where we fit in. If we're first, we
     * become the leader. If we're not elected the leader, attempt to stat the
     * offer just less than us. If they exist, watch for their failure, but if
     * they don't, become the leader.
     */
    for (int i = 0; i < leaderOffers.size(); i++) {
      LeaderOffer leaderOffer = leaderOffers.get(i);

      if (leaderOffer.getId().equals(this.leaderOffer.getId())) {
        logger.debug("There are {} leader offers. I am {} in line.",
            leaderOffers.size(), i);

        dispatchEvent(EventType.DETERMINE_COMPLETE);

        if (i == 0) {
          becomeLeader();
        } else {
          becomeReady(leaderOffers.get(i - 1));
        }

        /* Once we've figured out where we are, we're done. */
        break;
      }
    }
  }

  private void becomeReady(LeaderOffer neighborLeaderOffer)
      throws KeeperException, InterruptedException {

    logger.info("{} not elected leader. Watching node:{}",
        leaderOffer.getNodePath(), neighborLeaderOffer.getNodePath());

    /*
     * Make sure to pass an explicit Watcher because we could be sharing this
     * zooKeeper instance with someone else.
     */
    Stat stat = zooKeeper.exists(neighborLeaderOffer.getNodePath(), this);

    if (stat != null) {
      dispatchEvent(EventType.READY_START);
      logger.debug(
          "We're behind {} in line and they're alive. Keeping an eye on them.",
          neighborLeaderOffer.getNodePath());
      state = State.READY;
      dispatchEvent(EventType.READY_COMPLETE);
    } else {
      /*
       * If the stat fails, the node has gone missing between the call to
       * getChildren() and exists(). We need to try and become the leader.
       */
      logger
          .info(
              "We were behind {} but it looks like they died. Back to determination.",
              neighborLeaderOffer.getNodePath());
      determineElectionStatus();
    }

  }

  private void becomeLeader() {
    state = State.ELECTED;
    dispatchEvent(EventType.ELECTED_START);

    logger.info("Becoming leader with node:{}", leaderOffer.getNodePath());

    dispatchEvent(EventType.ELECTED_COMPLETE);
  }

  private void becomeFailed(Exception e) {
    logger.error("Failed in state {} - Exception:{}", state, e);

    state = State.FAILED;
    dispatchEvent(EventType.FAILED);
  }

  /**
   * Fetch the (user supplied) hostname of the current leader. Note that by the
   * time this method returns, state could have changed so do not depend on this
   * to be strongly consistent. This method has to read all leader offers from
   * ZooKeeper to deterime who the leader is (i.e. there is no caching) so
   * consider the performance implications of frequent invocation. If there are
   * no leader offers this method returns null.
   * 
   * @return hostname of the current leader
   * @throws KeeperException
   * @throws InterruptedException
   */
  public String getLeaderHostName() throws KeeperException,
      InterruptedException {

    List<LeaderOffer> leaderOffers = toLeaderOffers(zooKeeper.getChildren(
        rootNodeName, false));

    if (leaderOffers.size() > 0) {
      return leaderOffers.get(0).getHostName();
    }

    return null;
  }

  private List<LeaderOffer> toLeaderOffers(List<String> strings)
      throws KeeperException, InterruptedException {

    List<LeaderOffer> leaderOffers = new ArrayList<LeaderOffer>(strings.size());

    /*
     * Turn each child of rootNodeName into a leader offer. This is a tuple of
     * the sequence number and the node name.
     */
    for (String offer : strings) {
      String hostName = new String(zooKeeper.getData(
          rootNodeName + "/" + offer, false, null));

      leaderOffers.add(new LeaderOffer(Integer.valueOf(offer.substring("n_"
          .length())), rootNodeName + "/" + offer, hostName));
    }

    /*
     * We sort leader offers by sequence number (which may not be zero-based or
     * contiguous) and keep their paths handy for setting watches.
     */
    Collections.sort(leaderOffers, new LeaderOffer.IdComparator());

    return leaderOffers;
  }

  @Override
  public void process(WatchedEvent event) {
    if (event.getType().equals(Watcher.Event.EventType.NodeDeleted)) {
      if (!event.getPath().equals(leaderOffer.getNodePath())
          && state != State.STOP) {
        logger.debug(
            "Node {} deleted. Need to run through the election process.",
            event.getPath());
        try {
          determineElectionStatus();
        } catch (KeeperException e) {
          becomeFailed(e);
        } catch (InterruptedException e) {
          becomeFailed(e);
        }
      }
    }
  }

  private void dispatchEvent(EventType eventType) {
    logger.debug("Dispatching event:{}", eventType);

    synchronized (listeners) {
      if (listeners.size() > 0) {
        for (LeaderElectionAware observer : listeners) {
          observer.onElectionEvent(eventType);
        }
      }
    }
  }

  /**
   * Adds {@code listener} to the list of listeners who will receive events.
   * 
   * @param listener
   */
  public void addListener(LeaderElectionAware listener) {
    listeners.add(listener);
  }

  /**
   * Remove {@code listener} from the list of listeners who receive events.
   * 
   * @param listener
   */
  public void removeListener(LeaderElectionAware listener) {
    listeners.remove(listener);
  }

  @Override
  public String toString() {
    return "{ state:" + state + " leaderOffer:" + leaderOffer + " zooKeeper:"
        + zooKeeper + " hostName:" + hostName + " listeners:" + listeners
        + " }";
  }

  /**
   * <p>
   * Gets the ZooKeeper root node to use for this service.
   * </p>
   * <p>
   * For instance, a root node of {@code /mycompany/myservice} would be the
   * parent of all leader offers for this service. Obviously all processes that
   * wish to contend for leader status need to use the same root node. Note: We
   * assume this node already exists.
   * </p>
   * 
   * @return a znode path
   */
  public String getRootNodeName() {
    return rootNodeName;
  }

  /**
   * <p>
   * Sets the ZooKeeper root node to use for this service.
   * </p>
   * <p>
   * For instance, a root node of {@code /mycompany/myservice} would be the
   * parent of all leader offers for this service. Obviously all processes that
   * wish to contend for leader status need to use the same root node. Note: We
   * assume this node already exists.
   * </p>
   */
  public void setRootNodeName(String rootNodeName) {
    this.rootNodeName = rootNodeName;
  }

  /**
   * The {@link ZooKeeper} instance to use for all operations. Provided this
   * overrides any connectString or sessionTimeout set.
   */
  public ZooKeeper getZooKeeper() {
    return zooKeeper;
  }

  public void setZooKeeper(ZooKeeper zooKeeper) {
    this.zooKeeper = zooKeeper;
  }

  /**
   * The hostname of this process. Mostly used as a convenience for logging and
   * to respond to {@link #getLeaderHostName()} requests.
   */
  public String getHostName() {
    return hostName;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }

  /**
   * The type of event.
   */
  public static enum EventType {
    START, OFFER_START, OFFER_COMPLETE, DETERMINE_START, DETERMINE_COMPLETE, ELECTED_START, ELECTED_COMPLETE, READY_START, READY_COMPLETE, FAILED, STOP_START, STOP_COMPLETE,
  }

  /**
   * The internal state of the election support service.
   */
  public static enum State {
    START, OFFER, DETERMINE, ELECTED, READY, FAILED, STOP
  }
}
