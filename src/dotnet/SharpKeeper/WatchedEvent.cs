namespace SharpKeeper
{
    using System;
    using Org.Apache.Zookeeper.Proto;

    public class WatchedEvent
    {
        private readonly KeeperState state;
        private readonly EventType type;
        private readonly string path;

        public WatchedEvent(KeeperState state, EventType type, string path)
        {
            this.state = state;
            this.type = type;
            this.path = path;
        }

        public WatchedEvent(WatcherEvent eventMessage)
        {
            state = (KeeperState)Enum.ToObject(typeof(KeeperState), eventMessage.State);
            type = (EventType)Enum.ToObject(typeof (EventType), eventMessage.State);
            path = eventMessage.Path;
        }

        public KeeperState State
        {
            get { return state; }
        }

        public EventType Type
        {
            get { return type; }
        }

        public string Path
        {
            get { return path; }
        }

        public override string ToString()
        {
            return "WatchedEvent state:" + state
                + " type:" + type + " path:" + path;
        }

        /**
         *  Convert WatchedEvent to type that can be sent over network
         */
        public WatcherEvent GetWrapper()
        {
            return new WatcherEvent((int)type, (int)state, path);
        }
    }
}
