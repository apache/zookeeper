using System;
using System.Collections.Generic;
using org.apache.zookeeper.data;

namespace org.apache.zookeeper
{
    /// <summary/>
    public static class ZooDefs
    {
        internal enum OpCode
        {
            //Notification = 0, Used by server
            create = 1,
            delete = 2,
            exists = 3,
            getData = 4,
            setData = 5,
            getACL = 6,
            setACL = 7,
            //getChildren = 8, Not used by this client
            sync = 9,
            ping = 11,
            getChildren2 = 12,
            check = 13,
            multi = 14,
            auth = 100,
            setWatches = 101,
            //CreateSession = -10, Used by server
            closeSession = -11,
            error = -1,
        }

        /// <summary>
        /// ZooKeeper Permissions
        /// </summary>
        [Flags]
        public enum Perms
        {
            /// <summary>
            /// read permission
            /// </summary>
            READ = 1 << 0,
            /// <summary>
            /// write permission
            /// </summary>
            WRITE = 1 << 1,
            /// <summary>
            /// create permission
            /// </summary>
            CREATE = 1 << 2,
            /// <summary>
            /// delete permission
            /// </summary>
            DELETE = 1 << 3,
            /// <summary>
            /// admin permission
            /// </summary>
            ADMIN = 1 << 4,
            /// <summary>
            /// All permissions
            /// </summary>
            ALL = READ | WRITE | CREATE | DELETE | ADMIN
        }

        
        /// <summary/>
        public static class Ids
        {
            /// <summary>
            /// This Id represents anyone.
            /// </summary>
            public static readonly Id ANYONE_ID_UNSAFE = new Id("world", "anyone");
            
            /// <summary>
            /// This Id is only usable to set ACLs. It will get substituted with the 
            /// Id's the client authenticated with.
            /// </summary>
            public static readonly Id AUTH_IDS = new Id("auth", "");
            
            /// <summary>
            /// This is a completely open ACL
            /// </summary>
            public static readonly List<ACL> OPEN_ACL_UNSAFE = new List<ACL>(new[] { new ACL((int)Perms.ALL, ANYONE_ID_UNSAFE) });
            
            /// <summary>
            /// This ACL gives the creators authentication id's all permissions.
            /// </summary>
            public static readonly List<ACL> CREATOR_ALL_ACL = new List<ACL>(new[] { new ACL((int)Perms.ALL, AUTH_IDS) });
            
            /// <summary>
            /// This ACL gives the world the ability to read.
            /// </summary>
            public static readonly List<ACL> READ_ACL_UNSAFE = new List<ACL>(new[] { new ACL((int)Perms.READ, ANYONE_ID_UNSAFE) });
        }
    }
}
