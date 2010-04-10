namespace SharpKeeper
{
    public interface IWatcher
    {      
        void Process(WatchedEvent @event);
    }
}
