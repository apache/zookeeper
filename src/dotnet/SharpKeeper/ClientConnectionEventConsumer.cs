namespace SharpKeeper
{
    using System;
    using System.Collections.Generic;
    using System.Threading;
    using Org.Apache.Zookeeper.Proto;

    public class ClientConnectionEventConsumer : IStartable, IDisposable
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientConnectionEventConsumer));

        private readonly ClientConnection conn;
        private readonly Thread eventThread;
        static readonly object eventOfDeath = new object();

        private readonly object locker = new object();
        internal readonly Queue<object> waitingEvents = new Queue<object>();

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        public ClientConnectionEventConsumer(ClientConnection conn)
        {
            this.conn = conn;
            eventThread = new Thread(new SafeThreadStart(PollEvents).Run) { Name = "ZK-SendThread", IsBackground = true };
        }

        public void Start()
        {
            eventThread.Start();
        }

        public void PollEvents()
        {
            try
            {
                while (true)
                {
                    object @event;
                    lock (locker)
                    {
                        if (Monitor.Wait(locker, 2000)) @event = waitingEvents.Dequeue();
                        else continue;
                    }
                    try
                    {
                        if (@event == eventOfDeath)
                        {
                            return;
                        }

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
                        else
                        {
                            Packet p = (Packet) @event;
                            int rc = 0;
                            String clientPath = p.clientPath;
                            if (p.replyHeader.Err != 0)
                            {
                                rc = p.replyHeader.Err;
                            }

                            if (p.cb == null) return;

                            if (p.response is ExistsResponse || p.response is SetDataResponse ||
                                p.response is SetACLResponse)
                            {
                                StatCallback cb = (StatCallback) p.cb;
                                StatEventArgs args = new StatEventArgs {ReturnCode = rc, Path = clientPath};
                                if (rc == 0)
                                {
                                    if (p.response is ExistsResponse)
                                    {
                                        var exists = ((ExistsResponse) p.response);
                                        args.Stat = exists.Stat;
                                    }
                                    else if (p.response is SetDataResponse)
                                    {
                                        var response = ((SetDataResponse) p.response);
                                        args.Stat = response.Stat;
                                    }
                                    else if (p.response is SetACLResponse)
                                    {
                                        var response = ((SetACLResponse) p.response);
                                        args.Stat = response.Stat;
                                    }
                                }

                                cb(p.ctx, args);
                            }
                            else if (p.response is GetDataResponse)
                            {
                                DataCallback cb = (DataCallback) p.cb;
                                GetDataResponse rsp = (GetDataResponse) p.response;
                                DataEventArgs args = new DataEventArgs {ReturnCode = rc, Path = clientPath};
                                if (rc == 0)
                                {
                                    args.Data = rsp.Data;
                                    args.Stat = rsp.Stat;
                                }
                                cb(p.ctx, args);
                            }
                            else if (p.response is GetACLResponse)
                            {
                                ACLCallback cb = (ACLCallback) p.cb;
                                GetACLResponse rsp = (GetACLResponse) p.response;
                                AclEventArgs args = new AclEventArgs {ReturnCode = rc, Path = clientPath};
                                if (rc == 0)
                                {
                                    args.Acl = rsp.Acl;
                                    args.Stat = rsp.Stat;
                                }

                                cb(p.ctx, args);
                            }
                            else if (p.response is GetChildrenResponse)
                            {
                                ChildrenCallback cb = (ChildrenCallback) p.cb;
                                GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                                ChildrenEventArgs args = new ChildrenEventArgs {ReturnCode = rc, Path = clientPath};
                                if (rc == 0)
                                {
                                    args.Children = rsp.Children;
                                }

                                cb(p.ctx, args);
                            }
                            else if (p.response is GetChildren2Response)
                            {
                                ChildrenCallback cb = (ChildrenCallback) p.cb;
                                GetChildren2Response rsp = (GetChildren2Response) p.response;
                                ChildrenEventArgs args = new ChildrenEventArgs {ReturnCode = rc, Path = clientPath};
                                if (rc == 0)
                                {
                                    args.Children = rsp.Children;
                                    args.Stat = rsp.Stat;
                                }

                                cb(p.ctx, args);
                            }
                            else if (p.response is CreateResponse)
                            {
                                StringCallback cb = (StringCallback) p.cb;
                                CreateResponse rsp = (CreateResponse) p.response;
                                StringEventArgs args = new StringEventArgs();
                                if (rc == 0)
                                {
                                    args.Name = conn.ChrootPath == null
                                                    ? rsp.Path
                                                    : rsp.Path.Substring(conn.ChrootPath.Length);
                                }

                                cb(p.ctx, args);
                            }
                            else if (p.cb is VoidCallback)
                            {
                                VoidCallback cb = (VoidCallback) p.cb;
                                ZooKeeperEventArgs args = new ZooKeeperEventArgs() {ReturnCode = rc, Path = clientPath};
                                cb(p.ctx, args);
                            }
                        }
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

        private void QueueEventOfDeath()
        {
            AppendToQueue(eventOfDeath);
        }

        private void AppendToQueue(object o)
        {
            lock (locker)
            {
                waitingEvents.Enqueue(o);
                Monitor.Pulse(locker);
            }
        }

        public void Dispose()
        {
            QueueEventOfDeath();
            eventThread.Join(1000);
        }
    }
}