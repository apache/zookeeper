using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Threading.Tasks;
using org.apache.utils;

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

namespace org.apache.zookeeper.recipes.leader {
    /// <summary>
    ///     <para>
    ///         A leader election support library implementing the ZooKeeper election recipe.
    ///     </para>
    ///     <para>
    ///         This support library is meant to simplify the construction of an exclusive
    ///         leader system on top of Apache ZooKeeper. Any application that can become the
    ///         leader (usually a process that provides a service, exclusively) would
    ///         configure an instance of this class with their hostname, at least one
    ///         listener (an implementation of <seealso cref="LeaderElectionAware" />), and either an
    ///         instance of <seealso cref="ZooKeeper" /> or the proper connection information. Once
    ///         configured, invoking <seealso cref="start()" /> will cause the client to connect to
    ///         ZooKeeper and create a leader offer. The library then determines if it has
    ///         been elected the leader using the algorithm described below. The client
    ///         application can follow all state transitions via the listener callback.
    ///     </para>
    ///     <para>
    ///         Leader election algorithm
    ///     </para>
    ///     <para>
    ///         The library starts in a START state. Through each state transition, a state
    ///         start and a state complete event are sent to all listeners. When
    ///         <seealso cref="start()" /> is called, a leader offer is created in ZooKeeper. A leader
    ///         offer is an ephemeral sequential node that indicates a process that can act
    ///         as a leader for this service. A read of all leader offers is then performed.
    ///         The offer with the lowest sequence number is said to be the leader. The
    ///         process elected leader will transition to the leader state. All other
    ///         processes will transition to a ready state. Internally, the library creates a
    ///         ZooKeeper watch on the leader offer with the sequence ID of N - 1 (where N is
    ///         the process's sequence ID). If that offer disappears due to a process
    ///         failure, the watching process will run through the election determination
    ///         process again to see if it should become the leader. Note that sequence ID
    ///         may not be contiguous due to failed processes. A process may revoke its offer
    ///         to be the leader at any time by calling <seealso cref="stop()" />.
    ///     </para>
    ///     <para>
    ///         Guarantees (not) Made and Caveats
    ///     </para>
    ///     <para>
    ///         <ul>
    ///             <li>
    ///                 It is possible for a (poorly implemented) process to create a leader
    ///                 offer, get the lowest sequence ID, but have something terrible occur where it
    ///                 maintains its connection to ZK (and thus its ephemeral leader offer node) but
    ///                 doesn't actually provide the service in question. It is up to the user to
    ///                 ensure any failure to become the leader - and whatever that means in the
    ///                 context of the user's application - results in a revocation of its leader
    ///                 offer (i.e. that <seealso cref="stop()" /> is called).
    ///             </li>
    ///             <li>
    ///                 It is possible for ZK timeouts and retries to play a role in service
    ///                 liveliness. In other words, if process A has the lowest sequence ID but
    ///                 requires a few attempts to read the other leader offers' sequence IDs,
    ///                 election can seem slow. Users should apply timeouts during the determination
    ///                 process if they need to hit a specific SLA.
    ///             </li>
    ///             <li>
    ///                 The library makes a "best effort" to detect catastrophic failures of the
    ///                 process. It is possible that an unforeseen event results in (for instance) an
    ///                 unchecked exception that propagates passed normal error handling code. This
    ///                 normally doesn't matter as the same exception would almost certain destroy
    ///                 the entire process and thus the connection to ZK and the leader offer
    ///                 resulting in another round of leader determination.
    ///             </li>
    ///         </ul>
    ///     </para>
    /// </summary>
    public sealed class LeaderElectionSupport {
        private readonly AsyncLock lockable = new AsyncLock();
  
        private static readonly ILogProducer logger = TypeLogger<LeaderElectionSupport>.Instance;
        private readonly ConcurrentDictionary<LeaderElectionAware, byte> listeners;
        private readonly ElectionWatcher electionWatcher;
        private byte dummy;
        private LeaderOffer leaderOffer;
        private State state;

        /// <summary>
        /// Create a new instance of leader election recipe.
        /// </summary>
        /// <param name="zooKeeper">the zookeeper instance to use</param>
        /// <param name="rootNodeName">the root node to perform elections on</param>
        /// <param name="hostName">the name of the current host</param>
        public LeaderElectionSupport(ZooKeeper zooKeeper, string rootNodeName, string hostName) {
            ZooKeeper = zooKeeper;
            RootNodeName = rootNodeName;
            HostName = hostName;
            state = State.STOP;
            electionWatcher = new ElectionWatcher(this);
            listeners = new ConcurrentDictionary<LeaderElectionAware, byte>();
        }

        /// <summary>
        ///     Fetch the (user supplied) hostname of the current leader. Note that by the
        ///     time this method returns, state could have changed so do not depend on this
        ///     to be strongly consistent. This method has to read all leader offers from
        ///     ZooKeeper to deterime who the leader is (i.e. there is no caching) so
        ///     consider the performance implications of frequent invocation. If there are
        ///     no leader offers this method returns null.
        /// </summary>
        /// <returns> hostname of the current leader </returns>
        /// <exception cref="KeeperException"> </exception>
        public async Task<string> getLeaderHostName() {
            var leaderOffers = await toLeaderOffers((await ZooKeeper.getChildrenAsync(RootNodeName).ConfigureAwait(false)).Children).ConfigureAwait(false);

            if (leaderOffers.Count > 0) {
                return leaderOffers[0].HostName;
            }

            return null;
        }

        /// <summary>
        ///     <para>
        ///         Gets the ZooKeeper root node to use for this service.
        ///     </para>
        ///     <para>
        ///         For instance, a root node of {@code /mycompany/myservice} would be the
        ///         parent of all leader offers for this service. Obviously all processes that
        ///         wish to contend for leader status need to use the same root node. Note: We
        ///         assume this node already exists.
        ///     </para>
        /// </summary>
        /// <returns> a znode path </returns>
        private readonly string RootNodeName;

        /// <summary>
        ///     The <seealso cref="ZooKeeper" /> instance to use for all operations. Provided this
        ///     overrides any connectString or sessionTimeout set.
        /// </summary>
        private readonly ZooKeeper ZooKeeper;

        /// <summary>
        ///     The hostname of this process. Mostly used as a convenience for logging and
        ///     to respond to <seealso cref="getLeaderHostName()" /> requests.
        /// </summary>
        private readonly string HostName;

        /// <summary>
        ///     <para>
        ///         Start the election process. This method will create a leader offer,
        ///         determine its status, and either become the leader or become ready. If an
        ///         instance of <seealso cref="ZooKeeper" /> has not yet been configured by the user, a
        ///         new instance is created using the connectString and sessionTime specified.
        ///     </para>
        ///     <para>
        ///         Any (anticipated) failures result in a failed event being sent to all
        ///         listeners.
        ///     </para>
        /// </summary>
        public async Task start() {
            using (await lockable.LockAsync().ConfigureAwait(false))
             {
                state = State.START;
                await dispatchEvent(ElectionEventType.START).ConfigureAwait(false);

                logger.info("Starting leader election support");

                if (ZooKeeper == null) {
                    throw new InvalidOperationException("No instance of zookeeper provided. Hint: use setZooKeeper()");
                }

                if (HostName == null) {
                    throw new InvalidOperationException("No hostname provided. Hint: use setHostName()");
                }
                KeeperException ke = null;
                try {
                    await makeOffer().ConfigureAwait(false);
                    await determineElectionStatus().ConfigureAwait(false);
                }
                catch (KeeperException e) {
                    ke = e;
                }
                if (ke != null) {
                    await becomeFailed(ke).ConfigureAwait(false);
                }
            }
        }

        /// <summary>
        ///     Stops all election services, revokes any outstanding leader offers, and
        ///     disconnects from ZooKeeper.
        /// </summary>
        public async Task stop()
        {
            using (await lockable.LockAsync().ConfigureAwait(false))
             {
                state = State.STOP;
                await dispatchEvent(ElectionEventType.STOP_START).ConfigureAwait(false);

                logger.info("Stopping leader election support");

                if (leaderOffer != null) {
                    KeeperException ke = null;
                    try {
                        await ZooKeeper.deleteAsync(leaderOffer.NodePath).ConfigureAwait(false);
                        logger.debugFormat("Removed leader offer {0}", leaderOffer.NodePath);
                    }
                    catch (KeeperException e)
                    {
                        ke = e;
                    }
                    if (ke != null)
                    {
                        await becomeFailed(ke).ConfigureAwait(false);
                    }
                }

                await dispatchEvent(ElectionEventType.STOP_COMPLETE).ConfigureAwait(false);
            }
        }

        private async Task makeOffer() {
            state = State.OFFER;
            await dispatchEvent(ElectionEventType.OFFER_START).ConfigureAwait(false);

            leaderOffer = new LeaderOffer();

            leaderOffer.HostName = HostName;
            leaderOffer.NodePath = await ZooKeeper.createAsync(RootNodeName + "/" + "n_", HostName.UTF8getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL).ConfigureAwait(false);

            logger.debugFormat("Created leader offer {0}", leaderOffer);

            await dispatchEvent(ElectionEventType.OFFER_COMPLETE).ConfigureAwait(false);
        }

        private async Task determineElectionStatus() {
            state = State.DETERMINE;
            await dispatchEvent(ElectionEventType.DETERMINE_START).ConfigureAwait(false);

            var components = leaderOffer.NodePath.Split('/');

            leaderOffer.Id = int.Parse(components[components.Length - 1].Substring("n_".Length));

            var leaderOffers = await toLeaderOffers((await ZooKeeper.getChildrenAsync(RootNodeName).ConfigureAwait(false)).Children).ConfigureAwait(false);

            /*
		 * For each leader offer, find out where we fit in. If we're first, we
		 * become the leader. If we're not elected the leader, attempt to stat the
		 * offer just less than us. If they exist, watch for their failure, but if
		 * they don't, become the leader.
		 */
            for (var i = 0; i < leaderOffers.Count; i++) {
                if (leaderOffers[i].Id.Equals(leaderOffer.Id)) {
                    logger.debugFormat("There are {0} leader offers. I am {1} in line.", leaderOffers.Count, i);

                    await dispatchEvent(ElectionEventType.DETERMINE_COMPLETE).ConfigureAwait(false);

                    if (i == 0) {
                        await becomeLeader().ConfigureAwait(false);
                    }
                    else {
                        await becomeReady(leaderOffers[i - 1]).ConfigureAwait(false);
                    }

                    /* Once we've figured out where we are, we're done. */
                    break;
                }
            }
        }

        private async Task becomeReady(LeaderOffer neighborLeaderOffer) {
            await dispatchEvent(ElectionEventType.READY_START).ConfigureAwait(false);

            logger.debugFormat("{0} not elected leader. Watching node:{1}", leaderOffer.NodePath,
                neighborLeaderOffer.NodePath);

            /*
		 * Make sure to pass an explicit Watcher because we could be sharing this
		 * zooKeeper instance with someone else.
		 */
            var stat = await ZooKeeper.existsAsync(neighborLeaderOffer.NodePath, electionWatcher).ConfigureAwait(false);

            if (stat != null) {
                logger.debugFormat("We're behind {0} in line and they're alive. Keeping an eye on them.",
                    neighborLeaderOffer.NodePath);
                state = State.READY;
                await dispatchEvent(ElectionEventType.READY_COMPLETE).ConfigureAwait(false);
            }
            else {
                /*
		   * If the stat fails, the node has gone missing between the call to
		   * getChildren() and exists(). We need to try and become the leader.
		   */
                logger.debugFormat("We were behind {0} but it looks like they died. Back to determination.",
                    neighborLeaderOffer.NodePath);
                await determineElectionStatus().ConfigureAwait(false);
            }
        }

        private async Task becomeLeader()
        {
            state = State.ELECTED;
            await dispatchEvent(ElectionEventType.ELECTED_START).ConfigureAwait(false);

            logger.debugFormat("Becoming leader with node:{0}", leaderOffer.NodePath);

            await dispatchEvent(ElectionEventType.ELECTED_COMPLETE).ConfigureAwait(false);
        }

        private Task becomeFailed(Exception e) {
            logger.debugFormat("Failed in state {0} - Exception:{1}", state, e);

            state = State.FAILED;
            return dispatchEvent(ElectionEventType.FAILED);
        }

        private async Task<List<LeaderOffer>> toLeaderOffers(List<string> strings) {
            var leaderOffers = new List<LeaderOffer>(strings.Count);

            /*
		 * Turn each child of rootNodeName into a leader offer. This is a tuple of
		 * the sequence number and the node name.
		 */
            foreach (var offer in strings) {
                var currentHostName = (await ZooKeeper.getDataAsync(RootNodeName + "/" + offer).ConfigureAwait(false)).Data.UTF8bytesToString();

                leaderOffers.Add(new LeaderOffer(int.Parse(offer.Substring("n_".Length)),
                    RootNodeName + "/" + offer, currentHostName));
            }

            /*
		 * We sort leader offers by sequence number (which may not be zero-based or
		 * contiguous) and keep their paths handy for setting watches.
		 */
            leaderOffers.Sort(new LeaderOffer.IdComparator());

            return leaderOffers;
        }
        private class ElectionWatcher:Watcher
        {
            private readonly LeaderElectionSupport les;

            public ElectionWatcher(LeaderElectionSupport leaderElectionSupport)
            {
                les = leaderElectionSupport;
            }

            public async override Task process(WatchedEvent @event) {
                if (@event.get_Type().Equals(Event.EventType.NodeDeleted)) {
                    if (!@event.get_Type().ToString().Equals(les.leaderOffer.NodePath) && les.state != State.STOP) {
                        logger.debugFormat("Node {0} deleted. Need to run through the election process.", @event.getPath());
                        KeeperException ke = null;
                        try {
                            await les.determineElectionStatus().ConfigureAwait(false);
                        }
                        catch (KeeperException e) {
                            ke = e;
                        }
                        if (ke != null) {
                            await les.becomeFailed(ke).ConfigureAwait(false);
                        }
                    }
                }
            }
        }

        private readonly AsyncLock listenersLock = new AsyncLock();

        private async Task dispatchEvent(ElectionEventType eventType) {
            logger.debugFormat("Dispatching event:{0}", eventType);

            using (await listenersLock.LockAsync().ConfigureAwait(false)) {
                if (listeners.Count > 0) {
                    foreach (var observer in listeners.Keys) {
                        try {
                            await observer.onElectionEvent(eventType).ConfigureAwait(false);
                        }
                        catch (Exception e) {
                            logger.warn("error while calling observer", e);
                        }
                    }
                }
            }
        }

        /// <summary>
        ///     Adds {@code listener} to the list of listeners who will receive events.
        /// </summary>
        /// <param name="listener"> </param>
        public void addListener(LeaderElectionAware listener) {
            listeners[listener] = dummy;
        }

        /// <summary>
        ///     Remove {@code listener} from the list of listeners who receive events.
        /// </summary>
        /// <param name="listener"> </param>
        public void removeListener(LeaderElectionAware listener) {
            listeners.TryRemove(listener, out dummy);
        }

        /// <summary>
        /// </summary>
        public override string ToString() {
            return "{ state:" + state + " leaderOffer:" + leaderOffer + " zooKeeper:" + ZooKeeper + " hostName:" +
                   HostName + " listeners:" + listeners.Keys.ToCommaDelimited() +" }";
        }

        /// <summary>
        ///     The internal state of the election support service.
        /// </summary>
        private enum State {
            START,
            OFFER,
            DETERMINE,
            ELECTED,
            READY,
            FAILED,
            STOP
        }
    }
    /// <summary>
    ///     The type of election event.
    /// </summary>
    public enum ElectionEventType
    {
#pragma warning disable 1591
        START,
        OFFER_START,
        OFFER_COMPLETE,
        DETERMINE_START,
        DETERMINE_COMPLETE,
        ELECTED_START,
        ELECTED_COMPLETE,
        READY_START,
        READY_COMPLETE,
        FAILED,
        STOP_START,
        STOP_COMPLETE
#pragma warning restore 1591
    }
}