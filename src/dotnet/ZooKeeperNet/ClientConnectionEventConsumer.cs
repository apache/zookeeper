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
    using System.Text;
using System.Collections.Generic;

    public class ClientConnectionEventConsumer : IStartable, IDisposable
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ClientConnectionEventConsumer));

        private readonly ClientConnection conn;
        private readonly Thread eventThread;
        //ConcurrentQueue gives us the non-blocking way of processing, it reduced the contention so much
        internal readonly ConcurrentQueue<ClientConnection.WatcherSetEventPair> waitingEvents = new ConcurrentQueue<ClientConnection.WatcherSetEventPair>();

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        public ClientConnectionEventConsumer(ClientConnection conn)
        {
            this.conn = conn;
            eventThread = new Thread(new SafeThreadStart(PollEvents).Run) { Name = new StringBuilder("ZK-EventThread ").Append(conn.zooKeeper.Id).ToString(), IsBackground = true };
        }

        public void Start()
        {
            eventThread.Start();
        }

        private static void ProcessWatcher(IEnumerable<IWatcher> watchers,WatchedEvent watchedEvent)
        {
            foreach (IWatcher watcher in watchers)
            {
                try
                {
                    watcher.Process(watchedEvent);
                }
                catch (Exception t)
                {
                    LOG.Error("Error while calling watcher ", t);
                }
            }
        }

        public void PollEvents()
        {
            try
            {
                SpinWait spin = new SpinWait();
                while(Interlocked.CompareExchange(ref isDisposed, 1, 1) == 0)
                {
                    try
                    {
                        ClientConnection.WatcherSetEventPair pair;
                        if (waitingEvents.TryDequeue(out pair))
                            ProcessWatcher(pair.Watchers, pair.WatchedEvent);
                        else
                        {
                            spin.SpinOnce();
                            if (spin.Count > ClientConnection.maxSpin)
                                spin.Reset();
                        }
                        
                    }
                    catch (ObjectDisposedException)
                    {
                    }
                    catch (InvalidOperationException)
                    {
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

            if (Interlocked.CompareExchange(ref isDisposed, 0, 0) == 1)
                throw new InvalidOperationException("consumer has been disposed");
            
            sessionState = @event.State;

            // materialize the watchers based on the event
            var pair = new ClientConnection.WatcherSetEventPair(conn.watcher.Materialize(@event.State, @event.Type,@event.Path), @event);
            // queue the pair (watch set & event) for later processing
            waitingEvents.Enqueue(pair);
        }

        private int isDisposed = 0;
        private void InternalDispose()
        {
            if (Interlocked.CompareExchange(ref isDisposed, 1, 0) == 0)
            {
                try
                {
                    if (eventThread.IsAlive)
                    {
                        eventThread.Join();
                    }
                    //process any unprocessed event
                    ClientConnection.WatcherSetEventPair pair;
                    while (waitingEvents.TryDequeue(out pair))
                        ProcessWatcher(pair.Watchers, pair.WatchedEvent);  
                }
                catch (Exception ex)
                {
                    LOG.WarnFormat("Error disposing {0} : {1}", this.GetType().FullName, ex.Message);
                }
            }
        }

        public void Dispose()
        {
            InternalDispose();
            GC.SuppressFinalize(this);
        }

        ~ClientConnectionEventConsumer()
        {
            InternalDispose();
        }
    }
}
