namespace SharpKeeper
{
    public class Quotas
    {
        /** the zookeeper nodes that acts as the management and status node **/
        public static string procZookeeper = "/zookeeper";

        /** the zookeeper quota node that acts as the quota
         * management node for zookeeper */
        public static string quotaZookeeper = "/zookeeper/quota";

        /**
         * the limit node that has the limit of
         * a subtree
         */
        public static string limitNode = "zookeeper_limits";

        /**
         * the stat node that monitors the limit of
         * a subtree.
         */
        public static string statNode = "zookeeper_stats";

        /**
         * return the quota path associated with this
         * prefix
         * @param path the actual path in zookeeper.
         * @return the limit quota path
         */
        public static string quotaPath(string path)
        {
            return quotaZookeeper + path +
            "/" + limitNode;
        }

        /**
         * return the stat quota path associated with this
         * prefix.
         * @param path the actual path in zookeeper
         * @return the stat quota path
         */
        public static string statPath(string path)
        {
            return quotaZookeeper + path + "/" +
            statNode;
        }
    }
}
