namespace SharpKeeper
{
    public enum OpCode
    {
        Notification = 0,
        Create = 1,
        Delete = 2,
        Exists = 3,
        GetData = 4,
        SetData = 5,
        GetACL = 6,
        SetACL = 7,
        GetChildren = 8,
        Sync = 9,
        Ping = 11,
        GetChildren2 = 12,
        Auth = 100,
        SetWatches = 101,
        CreateSession = -10,
        CloseSession = -11,
        Error = -1,    
    }
}