using System;
using System.Collections.Generic;

namespace SharpKeeper
{
    using System.Threading;
    using Org.Apache.Zookeeper.Proto;

    public partial class ClientCnxn : IClientCnxn
    {
        class EventConsumer
        {
            internal readonly Queue<Object> waitingEvents = new Queue<Object>();

            /** This is really the queued session state until the event
             * thread actually processes the event and hands it to the watcher.
             * But for all intents and purposes this is the state.
             */
            private volatile KeeperState sessionState = KeeperState.Disconnected;

            private readonly IClientWatchManager watcher;
            private readonly string chrootPath;

            public EventConsumer(IClientWatchManager watcher, string chrootPath)
            {
                this.watcher = watcher;
                this.chrootPath = chrootPath;
            }

            public void queueEvent(WatchedEvent @event)
            {
                if (@event.Type == EventType.None && sessionState == @event.State)
                {
                    return;
                }
                sessionState = @event.State;

                // materialize the watchers based on the event
                WatcherSetEventPair pair = new WatcherSetEventPair(
                        watcher.Materialize(@event.State, @event.Type,
                                @event.Path),
                                @event);
                // queue the pair (watch set & event) for later processing
                waitingEvents.Enqueue(pair);
            }

            public void queuePacket(Packet packet)
            {
                waitingEvents.Enqueue(packet);
            }

            public void queueEventOfDeath()
            {
                waitingEvents.Enqueue(eventOfDeath);
            }

            public void run()
            {
                try
                {
                    while (true)
                    {
                        Object @event = waitingEvents.Dequeue();
                        try
                        {
                            if (@event == eventOfDeath)
                            {
                                return;
                            }

                            if (@event is WatcherSetEventPair)
                            {
                                // each watcher will process the event
                                WatcherSetEventPair pair = (WatcherSetEventPair)@event;
                                foreach (Watcher watcher in pair.watchers)
                                {
                                    try
                                    {
                                        watcher.process(pair.@event);
                                    }
                                    catch (Exception t)
                                    {
                                        LOG.Error("Error while calling watcher ", t);
                                    }
                                }
                            }
                            else
                            {
                                Packet p = (Packet)@event;
                                int rc = 0;
                                String clientPath = p.clientPath;
                                if (p.replyHeader.Err != 0)
                                {
                                    rc = p.replyHeader.Err;
                                }
                                if (p.cb == null)
                                {
                                    LOG.Warn("Somehow a null cb got to EventThread!");
                                }
                                else if (p.response is ExistsResponse
                                        || p.response is SetDataResponse
                                        || p.response is SetACLResponse)
                                {
                                    StatCallback cb = (StatCallback)p.cb;
                                    if (rc == 0)
                                    {
                                        if (p.response is ExistsResponse)
                                        {
                                            cb(rc, clientPath, p.ctx, ((ExistsResponse)p.response).Stat);
                                        }
                                        else if (p.response is SetDataResponse)
                                        {
                                            cb(rc, clientPath, p.ctx, ((SetDataResponse)p.response).Stat);
                                        }
                                        else if (p.response is SetACLResponse)
                                        {
                                            cb(rc, clientPath, p.ctx, ((SetACLResponse)p.response).Stat);
                                        }
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null);
                                    }
                                }
                                else if (p.response is GetDataResponse)
                                {
                                    DataCallback cb = (DataCallback)p.cb;
                                    GetDataResponse rsp = (GetDataResponse)p.response;
                                    if (rc == 0)
                                    {
                                        cb(rc, clientPath, p.ctx, rsp.Data, rsp.Stat);
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null, null);
                                    }
                                }
                                else if (p.response is GetACLResponse)
                                {
                                    ACLCallback cb = (ACLCallback)p.cb;
                                    GetACLResponse rsp = (GetACLResponse)p.response;
                                    if (rc == 0)
                                    {
                                        cb(rc, clientPath, p.ctx, rsp.Acl, rsp.Stat);
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null, null);
                                    }
                                }
                                else if (p.response is GetChildrenResponse)
                                {
                                    ChildrenCallback cb = (ChildrenCallback)p.cb;
                                    GetChildrenResponse rsp = (GetChildrenResponse)p.response;
                                    if (rc == 0)
                                    {
                                        cb(rc, clientPath, p.ctx, rsp.Children);
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null);
                                    }
                                }
                                else if (p.response is GetChildren2Response)
                                {
                                    Children2Callback cb = (Children2Callback)p.cb;
                                    GetChildren2Response rsp = (GetChildren2Response)p.response;
                                    if (rc == 0)
                                    {
                                        cb(rc, clientPath, p.ctx, rsp.Children, rsp.Stat);
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null, null);
                                    }
                                }
                                else if (p.response is CreateResponse)
                                {
                                    StringCallback cb = (StringCallback)p.cb;
                                    CreateResponse rsp = (CreateResponse)p.response;
                                    if (rc == 0)
                                    {
                                        cb(rc, clientPath, p.ctx, (chrootPath == null ? rsp.Path : rsp.Path.Substring(chrootPath.Length)));
                                    }
                                    else
                                    {
                                        cb(rc, clientPath, p.ctx, null);
                                    }
                                }
                                else if (p.cb is VoidCallback)
                                {
                                    VoidCallback cb = (VoidCallback)p.cb;
                                    cb(rc, clientPath, p.ctx);
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

                LOG.info("EventThread shut down");
            }
        }

        private void finishPacket(Packet p)
        {
            if (p.watchRegistration != null)
            {
                p.watchRegistration.Register(p.replyHeader.Err);
            }

            if (p.cb == null)
            {
                lock (p)
                {
                    p.finished = true;
                    Monitor.PulseAll(p);
                }
            }
            else
            {
                p.finished = true;
                eventConsumer.queuePacket(p);
            }
        }
    }
}
