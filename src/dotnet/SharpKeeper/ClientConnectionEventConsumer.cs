namespace SharpKeeper
{
    using System;
    using System.Threading;

    public class ClientConnectionEventConsumer : IStartable, IDisposable
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientConnectionEventConsumer));

        private readonly ClientConnection conn;
        private readonly Thread eventThread;
        static readonly object eventOfDeath = new object();

        internal readonly BlockingQueue<object> waitingEvents = new BlockingQueue<object>(1000);

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
                while (true)
                {
                    object @event = waitingEvents.Dequeue();
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
            waitingEvents.Enqueue(o);
        }

        public void Dispose()
        {
            QueueEventOfDeath();
            eventThread.Join(2000);
        }
    }
}