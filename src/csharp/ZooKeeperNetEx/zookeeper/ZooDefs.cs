using System;
using System.Collections.Generic;
using org.apache.zookeeper.data;

namespace org.apache.zookeeper
{
    public static class ZooDefs
    {
        public enum OpCode
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

        [Flags]
        public enum Perms
        {
            READ = 1 << 0,
            WRITE = 1 << 1,
            CREATE = 1 << 2,
            DELETE = 1 << 3,
            ADMIN = 1 << 4,
            ALL = READ | WRITE | CREATE | DELETE | ADMIN
        }

        public static class Ids
        {
            /**
             * This Id represents anyone.
             */
            public static readonly Id ANYONE_ID_UNSAFE = new Id("world", "anyone");

            /**
             * This Id is only usable to set ACLs. It will get substituted with the
             * Id's the client authenticated with.
             */
            public static readonly Id AUTH_IDS = new Id("auth", "");

            /**
             * This is a completely open ACL .
             */
            public static readonly List<ACL> OPEN_ACL_UNSAFE = new List<ACL>(new[] { new ACL((int)Perms.ALL, ANYONE_ID_UNSAFE) });

            /**
             * This ACL gives the creators authentication id's all permissions.
             */
            public static readonly List<ACL> CREATOR_ALL_ACL = new List<ACL>(new[] { new ACL((int)Perms.ALL, AUTH_IDS) });

            /**
             * This ACL gives the world the ability to read.
             */
            public static readonly List<ACL> READ_ACL_UNSAFE = new List<ACL>(new[] { new ACL((int)Perms.READ, ANYONE_ID_UNSAFE) });
        }
    }
}
