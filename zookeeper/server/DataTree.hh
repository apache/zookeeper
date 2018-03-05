/*
 * DataTree.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef DataTree_HH_
#define DataTree_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./DataNode.hh"
#include "./WatchManager.hh"
#include "./ByteBufferInputStream.hh"
#include "./ReferenceCountedACLCache.hh"
#include "./upgrade/DataNodeV1.hh"
#include "../ZooDefs.hh"
#include "../Quotas.hh"
#include "../Watcher.hh"
#include "../WatchedEvent.hh"
#include "../StatsTrack.hh"
#include "../KeeperException.hh"
#include "../txn/CheckVersionTxn.hh"
#include "../txn/CreateTxn.hh"
#include "../txn/DeleteTxn.hh"
#include "../txn/ErrorTxn.hh"
#include "../txn/MultiTxn.hh"
#include "../txn/SetACLTxn.hh"
#include "../txn/SetDataTxn.hh"
#include "../txn/Txn.hh"
#include "../txn/TxnHeader.hh"
#include "../common/PathTrie.hh"
#include "../data/ACL.hh"
#include "../data/Stat.hh"
#include "../data/StatPersisted.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"
#include "../../jute/inc/ECsvOutputArchive.hh"


namespace efc {
namespace ezk {

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 */
class DataTree : public ESynchronizeable {
public:
	template<typename T>
	class HashSetSync : public EHashSet<T>, public ESynchronizeable {
	};

private:
    /**
     * a encapsultaing class for return value
     */
    class Counts : public EObject {
    public:
        llong bytes;
        int count;

        Counts(DataTree* o) : dt(o) {
        }

    private:
        DataTree* dt;
    };

    /**
     * this method gets the count of nodes and the bytes under a subtree
     *
     * @param path
     *            the path to be used
     * @param counts
     *            the int count
     */
    void getCounts(EString path, Counts& counts);

    /**
     * update the quota for the given path
     *
     * @param path
     *            the path to be used
     */
    void updateQuotaForPath(EString path);

    /**
     * this method traverses the quota path and update the path trie and sets
     *
     * @param path
     */
    void traverseNode(EString path);

    /**
     * this method sets up the path trie and sets up stats for quota nodes
     */
    void setupQuota();

private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(DataTree.class);

    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    EConcurrentHashMap<EString, DataNode> nodes;// = new ConcurrentHashMap<String, DataNode>();

    WatchManager dataWatches;// = new WatchManager();

    WatchManager childWatches;// = new WatchManager();

    /** the root of zookeeper tree */
    constexpr static char const* rootZookeeper = "/";

    /** the zookeeper nodes that acts as the management and status node **/
    constexpr static char const* procZookeeper = Quotas::procZookeeper;

    /** this will be the string thats stored as a child of root */
    constexpr static char const* procChildZookeeper =  procZookeeper + 1;// = procZookeeper.substring(1);

    /**
     * the zookeeper quota node that acts as the quota management node for
     * zookeeper
     */
    constexpr static char const* quotaZookeeper = Quotas::quotaZookeeper;

    /** this will be the string thats stored as a child of /zookeeper */
    constexpr static char const* quotaChildZookeeper = "quota";// = quotaZookeeper.substring(procZookeeper.length() + 1);

    /**
     * the path trie that keeps track fo the quota nodes in this datatree
     */
    PathTrie pTrie;// = new PathTrie();

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     */
    EConcurrentHashMap<llong, HashSetSync<EString*> > ephemerals;// = new ConcurrentHashMap<Long, HashSet<String>>();

    ReferenceCountedACLCache aclCache;// = new ReferenceCountedACLCache();

    int scount; //?

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    sp<DataNode> root;// = new DataNode(null, new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper filesystem that is the proc filesystem of zookeeper
     */
    sp<DataNode> procDataNode;// = new DataNode(root, new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper/quota node for maintaining quota properties for
     * zookeeper
     */
    sp<DataNode> quotaDataNode;// = new DataNode(procDataNode, new byte[0], -1L, new StatPersisted());

public:
    volatile llong lastProcessedZxid;// = 0;
    boolean initialized;// = false;

    class ProcessTxnResult : public EObject {
    public:
    	llong clientId;

        int cxid;

        llong zxid;

        int err;

        int type;

        EString path;

        sp<Stat> stat;

        sp<EArrayList<sp<ProcessTxnResult> > > multiResult;

        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        virtual boolean equals(EObject* o) {
        	if (o == this) return true;

        	ProcessTxnResult* that = dynamic_cast<ProcessTxnResult*>(o);
        	if (!that) return false;

			return that->clientId == clientId && that->cxid == cxid;
        }

        /**
         * See equals() to find the rational for how this hashcode is generated.
         *
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        virtual int hashCode() {
            return (int) ((clientId ^ cxid) % EInteger::MAX_VALUE);
        }
    };

    static void copyStatPersisted(sp<StatPersisted> from, sp<StatPersisted> to) {
        to->setAversion(from->getAversion());
        to->setCtime(from->getCtime());
        to->setCversion(from->getCversion());
        to->setCzxid(from->getCzxid());
        to->setMtime(from->getMtime());
        to->setMzxid(from->getMzxid());
        to->setPzxid(from->getPzxid());
        to->setVersion(from->getVersion());
        to->setEphemeralOwner(from->getEphemeralOwner());
    }

    static void copyStat(sp<Stat> from, sp<Stat> to) {
    	to->setAversion(from->getAversion());
    	to->setCtime(from->getCtime());
    	to->setCversion(from->getCversion());
    	to->setCzxid(from->getCzxid());
    	to->setMtime(from->getMtime());
    	to->setMzxid(from->getMzxid());
    	to->setPzxid(from->getPzxid());
    	to->setVersion(from->getVersion());
    	to->setEphemeralOwner(from->getEphemeralOwner());
    	to->setDataLength(from->getDataLength());
    	to->setNumChildren(from->getNumChildren());
    }

public:

	DataTree();

    sp<EHashSet<EString*> > getEphemerals(llong sessionId);

    EConcurrentHashMap<llong, HashSetSync<EString*> >* getEphemeralsMap();

    ESet<llong>* getSessions();

    /**
     * just an accessor method to allow raw creation of datatree's from a bunch
     * of datanodes
     *
     * @param path
     *            the path of the datanode
     * @param node
     *            the datanode corresponding to this path
     */
    void addDataNode(EString path, sp<DataNode> node);

    sp<DataNode> getNode(EString path);

    int getNodeCount();

    int getWatchCount();

    int getEphemeralsCount();

    /**
     * Get the size of the nodes based on path and data length.
     *
     * @return size of the data
     */
    llong approximateDataSize();

    /**
     * is the path one of the special paths owned by zookeeper.
     *
     * @param path
     *            the path to be checked
     * @return true if a special path. false if not.
     */
    boolean isSpecialPath(EString path);

    /**
     * update the count of this stat datanode
     *
     * @param lastPrefix
     *            the path of the node that is quotaed.
     * @param diff
     *            the diff to be added to the count
     */
    void updateCount(EString lastPrefix, int diff);

    /**
     * update the count of bytes of this stat datanode
     *
     * @param lastPrefix
     *            the path of the node that is quotaed
     * @param diff
     *            the diff to added to number of bytes
     * @throws IOException
     *             if path is not found
     */
    void updateBytes(EString lastPrefix, llong diff);

    /**
     * @param path
     * @param data
     * @param acl
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     * @param time
     * @return the patch of the created node
     * @throws KeeperException
     */
    EString createNode(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl,
            llong ephemeralOwner, int parentCVersion, llong zxid, llong time)
            THROWS2(NoNodeException, NodeExistsException);

    /**
     * remove the path from the datatree
     *
     * @param path
     *            the path to of the node to be deleted
     * @param zxid
     *            the current zxid
     * @throws KeeperException.NoNodeException
     */
    void deleteNode(EString path, llong zxid) THROWS(NoNodeException);

    sp<Stat> setData(EString path, sp<EA<byte> > data, int version, llong zxid,
            llong time) THROWS(NoNodeException);

    /**
     * If there is a quota set, return the appropriate prefix for that quota
     * Else return null
     * @param path The ZK path to check for quota
     * @return Max quota prefix, or null if none
     */
    EString getMaxPrefixWithQuota(EString path);

    sp<EA<byte> > getData(EString path, sp<Stat> stat, sp<Watcher> watcher)
            THROWS(NoNodeException);

	sp<Stat> statNode(EString path, sp<Watcher> watcher)
			THROWS(NoNodeException);

    sp<EList<EString*> > getChildren(EString path, sp<Stat> stat, sp<Watcher> watcher)
			THROWS(NoNodeException);

    sp<Stat> setACL(EString path, sp<EList<sp<ACL> > > acl, int version)
			THROWS(NoNodeException);

    sp<EList<sp<ACL> > > getACL(EString path, sp<Stat> stat)
			THROWS(NoNodeException);

    sp<EList<sp<ACL> > > getACL(DataNode* node);

    llong getACL(DataNodeV1* oldDataNode);

    int aclCacheSize();

    sp<ProcessTxnResult> processTxn(TxnHeader* header, ERecord* txn);

    void killSession(llong session, llong zxid);

    /**
     * this method uses a stringbuilder to create a new path for children. This
     * is faster than string appends ( str1 + str2).
     *
     * @param oa
     *            OutputArchive to write to.
     * @param path
     *            a string builder.
     * @throws IOException
     * @throws InterruptedException
     */
    void serializeNode(EOutputArchive* oa, EString& path) THROWS(EIOException);

    void serialize(EOutputArchive* oa, EString tag) THROWS(EIOException);

    void deserialize(EInputArchive* ia, EString tag) THROWS(EIOException);

    /**
     * Summary of the watches on the datatree.
     * @param pwriter the output to write to
     */
    synchronized
    void dumpWatchesSummary(EPrintStream* pwriter);

    /**
     * Write a text dump of all the watches on the datatree.
     * Warning, this is expensive, use sparingly!
     * @param pwriter the output to write to
     */
    synchronized
    void dumpWatches(EPrintStream* pwriter, boolean byPath);

    /**
     * Write a text dump of all the ephemerals in the datatree.
     * @param pwriter the output to write to
     */
    void dumpEphemerals(EPrintStream* pwriter);

    void removeCnxn(Watcher* watcher);

    void clear();

    void setWatches(llong relativeZxid, sp<EList<EString*> > dataWatches,
    		sp<EList<EString*> > existWatches, sp<EList<EString*> > childWatches,
    		sp<Watcher> watcher);

     /**
      * This method sets the Cversion and Pzxid for the specified node to the
      * values passed as arguments. The values are modified only if newCversion
      * is greater than the current Cversion. A NoNodeException is thrown if
      * a znode for the specified path is not found.
      *
      * @param path
      *     Full path to the znode whose Cversion needs to be modified.
      *     A "/" at the end of the path is ignored.
      * @param newCversion
      *     Value to be assigned to Cversion
      * @param zxid
      *     Value to be assigned to Pzxid
      * @throws KeeperException.NoNodeException
      *     If znode not found.
      **/
    void setCversionPzxid(EString path, int newCversion, llong zxid)
        	THROWS(NoNodeException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* DataTree_HH_ */
