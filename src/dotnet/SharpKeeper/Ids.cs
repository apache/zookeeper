namespace SharpKeeper
{
    using System.Collections.Generic;
    using Org.Apache.Zookeeper.Data;

    public class Ids
    {
        /**
         * This Id represents anyone.
         */
        public static readonly ZKId ANYONE_ID_UNSAFE = new ZKId("world", "anyone");

        /**
         * This Id is only usable to set ACLs. It will get substituted with the
         * Id's the client authenticated with.
         */
        public static readonly ZKId AUTH_IDS = new ZKId("auth", "");

        /**
         * This is a completely open ACL .
         */
        public static readonly List<ACL> OPEN_ACL_UNSAFE = new List<ACL>(new ACL(Perms.ALL, ANYONE_ID_UNSAFE));

        /**
         * This ACL gives the creators authentication id's all permissions.
         */
        public static readonly List<ACL> CREATOR_ALL_ACL = new List<ACL>(new ACL(Perms.ALL, AUTH_IDS));

        /**
         * This ACL gives the world the ability to read.
         */
        public static readonly List<ACL> READ_ACL_UNSAFE = new List<ACL>(new ACL(Perms.READ, ANYONE_ID_UNSAFE));
    }
}
