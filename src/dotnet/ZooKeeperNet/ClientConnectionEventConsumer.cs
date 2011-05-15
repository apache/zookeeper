/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
﻿namespace ZooKeeperNet
{
    using System;
    using System.Collections.Concurrent;
    using System.Threading;
    using log4net;

    public class ClientConnectionEventConsumer : IStartable, IDisposable
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ClientConnectionEventConsumer));

        private readonly ClientConnection conn;
        private readonly Thread eventThread;

        internal readonly BlockingCollection<object> waitingEvents = new BlockingCollection<object>(1000);

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        public ClientConnectionEventConsumer(ClientConnection conn)
        {
            this.conn = conn;
            eventThread = new Thread(new SafeThreadStart(PollEvents).Run) { Name = "ZK-EventThread " + conn.zooKeeper.Id, IsBackground = true };
        }

        public void Start()
        {
            eventThread.Start();
        }

        public void PollEvents()
        {
            try
            {
                while (!waitingEvents.IsCompleted)
                {
                    object @event = waitingEvents.Take();
                    try
                    {
                        if (@event is ClientConnection.WatcherSetEventPair)
                        {
                            // each watcher will process the event
                            ClientConnection.WatcherSetEventPair pair = (ClientConnection.WatcherSetEventPair) @event;
                            foreach (IWatcher watcher in pair.watchers)
                            {
                                try
                                {
                                    watcher.Process(pair.@event);
                                }
                                catch (Exception t)
                                {
                                    LOG.Error("Error while calling watcher ", t);
                                }
                            }
                        }
                    }
                    catch (OperationCanceledException)
                    {
                        //ignored
                    }
                    catch (Exception t)
                    {
                        LOG.Error("Caught unexpected throwable", t);
                    }
                }
            }
            catch (ThreadInterruptedException e)
            {
                LOG.Error("Event thread exiting due to interruption", e);
            }

            LOG.Info("EventThread shut down");
        }

        public void QueueEvent(WatchedEvent @event)
        {
            if (@event.Type == EventType.None && sessionState == @event.State) return;
            
            sessionState = @event.State;

            // materialize the watchers based on the event
            var pair = new ClientConnection.WatcherSetEventPair(conn.watcher.Materialize(@event.State, @event.Type,@event.Path), @event);
            // queue the pair (watch set & event) for later processing
            AppendToQueue(pair);
        }

        public void QueuePacket(Packet packet)
        {
            AppendToQueue(packet);
        }

        private void AppendToQueue(object o)
        {
            waitingEvents.Add(o);
        }

        public void Dispose()
        {
            waitingEvents.CompleteAdding();
            eventThread.Join(2000);
        }
    }
}
