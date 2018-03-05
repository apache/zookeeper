/*
 * ServerCnxn.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ServerCnxn_HH_
#define ServerCnxn_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Stats.hh"
#include "./ServerStats.hh"
#include "../Watcher.hh"
#include "../WatchedEvent.hh"
#include "../data/Id.hh"
#include "../proto/ReplyHeader.hh"
#include "../proto/RequestHeader.hh"
#include "../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * Interface to a Server connection - represents a connection from a client
 * to the server.
 */
abstract class ServerCnxn : virtual public Stats, virtual public Watcher, public ESynchronizeable {
public:
	DECLARE_STATIC_INITZZ

    // This is just an arbitrary object to represent requests issued by
    // (aka owned by) this class
    static sp<EObject> me;// = new Object();
    
public:
    sp<EList<sp<Id> > > authInfo;// = new ArrayList<Id>();

    /**
     * If the client is of old version, we don't send r-o mode info to it.
     * The reason is that if we would, old C client doesn't read it, which
     * results in TCP RST packet, i.e. "connection reset by peer".
     */
    boolean isOldClient;// = true;


	EDate established;// = new Date();

	EAtomicLLong packetsReceived;// = new AtomicLong();
	EAtomicLLong packetsSent;// = new AtomicLong();

	llong minLatency;
	llong maxLatency;
	EString lastOp;
	llong lastCxid;
	llong lastZxid;
	llong lastResponseTime;
	llong lastLatency;

	llong count;
	llong totalLatency;

	virtual ServerStats* serverStats() = 0;

	virtual int getSessionTimeout() = 0;

    virtual void close() = 0;

public:
    ServerCnxn() :
    	isOldClient(true),
    	minLatency(0),
    	maxLatency(0),
    	lastCxid(0),
    	lastZxid(0),
    	lastResponseTime(0),
    	lastLatency(0),
    	count(0),
    	totalLatency(0) {
    	authInfo = new EArrayList<sp<Id> >();
    }

	virtual ~ServerCnxn();

    /** auth info for the cnxn, returns an unmodifyable list */
	virtual sp<EList<sp<Id> > > getAuthInfo() {
        return authInfo;
    }

	virtual void addAuthInfo(sp<Id> id) {
        if (authInfo->contains(id.get()) == false) {
            authInfo->add(id);
        }
    }

	virtual boolean removeAuthInfo(Id* id) {
        return authInfo->remove(id);
    }

	virtual void sendResponse(sp<ReplyHeader> h, sp<ERecord> r, EString tag) THROWS(EIOException) = 0;

    /* notify the client the session is closing and close/cleanup socket */
	virtual void sendCloseSession() = 0;

	virtual llong getSessionId() = 0;

	virtual void setSessionId(llong sessionId) = 0;

	virtual void sendBuffer(sp<EIOByteBuffer> closeConn) = 0;

	virtual void enableRecv() = 0;

	virtual void disableRecv() = 0;

	virtual void setSessionTimeout(int sessionTimeout) = 0;

    /**
     * Wrapper method to return the socket address
     */
	virtual EInetAddress* getSocketAddress() = 0;

    virtual llong getOutstandingRequests() = 0;
    virtual sp<EInetSocketAddress> getRemoteSocketAddress() = 0;
    virtual int getInterestOps() = 0;


public:

	static ESimpleLock glock;

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
	static int confCmd;// = ByteBuffer.wrap("conf".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int consCmd;// =ByteBuffer.wrap("cons".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int crstCmd;// = ByteBuffer.wrap("crst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int dumpCmd;// = ByteBuffer.wrap("dump".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int enviCmd;// = ByteBuffer.wrap("envi".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int getTraceMaskCmd;// = ByteBuffer.wrap("gtmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int ruokCmd;// = ByteBuffer.wrap("ruok".getBytes()).getInt();
    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int setTraceMaskCmd;// = ByteBuffer.wrap("stmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int srvrCmd;// = ByteBuffer.wrap("srvr".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int srstCmd;// = ByteBuffer.wrap("srst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int statCmd;// = ByteBuffer.wrap("stat".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int wchcCmd;// = ByteBuffer.wrap("wchc".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int wchpCmd;// = ByteBuffer.wrap("wchp".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int wchsCmd;// = ByteBuffer.wrap("wchs".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int mntrCmd;// = ByteBuffer.wrap("mntr".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    static int isroCmd;// = ByteBuffer.wrap("isro".getBytes()).getInt();

public:

    /**
     * Return the string representation of the specified command code.
     */
    static EString getCommandString(int command) {
        return cmd2String->get(command);
    }

    /**
     * Check if the specified command code is from a known command.
     *
     * @param command The integer code of command.
     * @return true if the specified command is known, false otherwise.
     */
    static boolean isKnown(int command) {
        return cmd2String->containsKey(command);
    }

    /**
     * Check if the specified command is enabled.
     *
     * In ZOOKEEPER-2693 we introduce a configuration option to only
     * allow a specific set of white listed commands to execute.
     * A command will only be executed if it is also configured
     * in the white list.
     *
     * @param command The command string.
     * @return true if the specified command is enabled.
     */
    /*synchronized*/
    static boolean isEnabled(EString command) {
    	SYNCBLOCK(&glock) {
			if (whiteListInitialized) {
				return whiteListedCommands->contains(&command);
			}

			EString commands = ESystem::getProperty(ZOOKEEPER_4LW_COMMANDS_WHITELIST.c_str());
			if (!commands.isEmpty()) {
				EArrayList<EString*> list = EPattern::split(",", commands.c_str(), 0);
				sp<EIterator<EString*> > iter = list.iterator();
				while (iter->hasNext()) {
					EString cmd = iter->next()->trim();
					if (cmd.equals("*")) {
						sp<EIterator<EMapEntry<int, EString*>*> > i = cmd2String->entrySet()->iterator();
						while (i->hasNext()) {
							EMapEntry<int, EString*>* entry = i->next();
							whiteListedCommands->add(new EString(entry->getValue()));
						}
						break;
					}
					if (!cmd.isEmpty()) {
						whiteListedCommands->add(new EString(cmd));
					}
				}
			} else {
				sp<EIterator<EMapEntry<int, EString*>*> > i = cmd2String->entrySet()->iterator();
				while (i->hasNext()) {
					EMapEntry<int, EString*>* entry = i->next();
					EString* cmd = entry->getValue();
					if (cmd->equals("wchc") || cmd->equals("wchp")) {
						// ZOOKEEPER-2693 / disable these exploitable commands by default.
						continue;
					}
					whiteListedCommands->add(new EString(cmd));
				}
			}

			// Readonly mode depends on "isro".
			if (EString("true").equals(ESystem::getProperty("readonlymode.enabled", "false"))) {
				whiteListedCommands->add(new EString("isro"));
			}
			// zkServer.sh depends on "srvr".
			whiteListedCommands->add(new EString("srvr"));
			whiteListInitialized = true;
			LOG->info(__FILE__, __LINE__, EString::formatOf("The list of known four letter word commands is : %s", cmd2String->toString().c_str()).c_str());
			LOG->info(__FILE__, __LINE__, EString::formatOf("The list of enabled four letter word commands is : %s", whiteListedCommands->toString().c_str()).c_str());
			return whiteListedCommands->contains(&command);
    	}}
    }

	/*synchronized*/
	void resetStats() {
		SYNCHRONIZED(this) {
			packetsReceived.set(0);
			packetsSent.set(0);
			minLatency = ELLong::MAX_VALUE;
			maxLatency = 0;
			lastOp = "NA";
			lastCxid = -1;
			lastZxid = -1;
			lastResponseTime = 0;
			lastLatency = 0;

			count = 0;
			totalLatency = 0;
		}}
	}

//protected:
public:
    void packetReceived() {
        incrPacketsReceived();
        ServerStats* ss = serverStats();
        if (ss != null) {
        	ss->incrementPacketsReceived();
        }
    }

    void packetSent() {
        incrPacketsSent();
        ServerStats* ss = serverStats();
        if (ss != null) {
        	ss->incrementPacketsSent();
        }
    }

    llong incrPacketsReceived() {
        return packetsReceived.incrementAndGet();
    }
    
    void incrOutstandingRequests(RequestHeader* h) {
    }

    llong incrPacketsSent() {
        return packetsSent.incrementAndGet();
    }

    /*synchronized*/
    void updateStatsForResponse(llong cxid, llong zxid,
            EString op, llong start, llong end)
    {
    	SYNCHRONIZED(this) {
			// don't overwrite with "special" xids - we're interested
			// in the clients last real operation
			if (cxid >= 0) {
				lastCxid = cxid;
			}
			lastZxid = zxid;
			lastOp = op;
			lastResponseTime = end;
			llong elapsed = end - start;
			lastLatency = elapsed;
			if (elapsed < minLatency) {
				minLatency = elapsed;
			}
			if (elapsed > maxLatency) {
				maxLatency = elapsed;
			}
			count++;
			totalLatency += elapsed;
    	}}
    }

    /**
      * Print information about the connection.
      * @param brief iff true prints brief details, otw full detail
      * @return information about this connection
      */
    /*synchronized*/
     void dumpConnectionInfo(EPrintStream* pwriter, boolean brief) {
    	 SYNCHRONIZED(this) {
			 pwriter->print(" ");
			 pwriter->print(getRemoteSocketAddress()->toString().c_str());
			 pwriter->print("[");
			 int interestOps = getInterestOps();
			 pwriter->print(interestOps == 0 ? "0" : EInteger::toHexString(interestOps).c_str());
			 pwriter->print("](queued=");
			 pwriter->print(getOutstandingRequests());
			 pwriter->print(",recved=");
			 pwriter->print(getPacketsReceived());
			 pwriter->print(",sent=");
			 pwriter->print(getPacketsSent());

			 if (!brief) {
				 llong sessionId = getSessionId();
				 if (sessionId != 0) {
					 pwriter->print(",sid=0x");
					 pwriter->print(ELLong::toHexString(sessionId).c_str());
					 pwriter->print(",lop=");
					 pwriter->print(getLastOperation().c_str());
					 pwriter->print(",est=");
					 pwriter->print(getEstablished().getTime());
					 pwriter->print(",to=");
					 pwriter->print(getSessionTimeout());
					 llong lastCxid = getLastCxid();
					 if (lastCxid >= 0) {
						 pwriter->print(",lcxid=0x");
						 pwriter->print(ELLong::toHexString(lastCxid).c_str());
					 }
					 pwriter->print(",lzxid=0x");
					 pwriter->print(ELLong::toHexString(getLastZxid()).c_str());
					 pwriter->print(",lresp=");
					 pwriter->print(getLastResponseTime());
					 pwriter->print(",llat=");
					 pwriter->print(getLastLatency());
					 pwriter->print(",minlat=");
					 pwriter->print(getMinLatency());
					 pwriter->print(",avglat=");
					 pwriter->print(getAvgLatency());
					 pwriter->print(",maxlat=");
					 pwriter->print(getMaxLatency());
				 }
			 }
			 pwriter->print(")");
    	 }}
     }

public:
    EDate getEstablished() {
        return EDate(established);
    }

    llong getPacketsReceived() {
        return packetsReceived.llongValue();
    }

    llong getPacketsSent() {
        return packetsSent.llongValue();
    }

    synchronized llong getMinLatency() {
    	SYNCHRONIZED(this) {
    		return minLatency == ELLong::MAX_VALUE ? 0 : minLatency;
    	}}
    }

    synchronized llong getAvgLatency() {
    	SYNCHRONIZED(this) {
    		return count == 0 ? 0 : totalLatency / count;
    	}}
    }

    synchronized llong getMaxLatency() {
    	SYNCHRONIZED(this) {
    		return maxLatency;
    	}}
    }

    synchronized EString getLastOperation() {
    	SYNCHRONIZED(this) {
    		return lastOp;
    	}}
    }

    synchronized llong getLastCxid() {
    	SYNCHRONIZED(this) {
    		return lastCxid;
    	}}
    }

    synchronized llong getLastZxid() {
    	SYNCHRONIZED(this) {
    		return lastZxid;
    	}}
    }

    synchronized llong getLastResponseTime() {
    	SYNCHRONIZED(this) {
    		return lastResponseTime;
    	}}
    }

    synchronized llong getLastLatency() {
    	SYNCHRONIZED(this) {
    		return lastLatency;
    	}}
    }

    /**
     * Prints detailed stats information for the connection.
     *
     * @see dumpConnectionInfo(PrintWriter, boolean) for brief stats
     */
    virtual EString toString() {
    	EByteArrayOutputStream baos;
    	EPrintStream pwriter(&baos);
        dumpConnectionInfo(&pwriter, false);
        pwriter.flush();
        pwriter.close();
        return EString((char*)baos.data(), 0, baos.size());
    }

private:
    static sp<ELogger> LOG;// = LoggerFactory.getLogger(ServerCnxn.class);

    static sp<EMap<int, EString*> > cmd2String;// = new HashMap<Integer, String>();

    static EString ZOOKEEPER_4LW_COMMANDS_WHITELIST;// = "zookeeper.4lw.commands.whitelist";

    static sp<ESet<EString*> > whiteListedCommands;// = new HashSet<String>();

    static boolean whiteListInitialized;// = false;
};

//=============================================================================

class CloseRequestException : public EIOException {
public:
	CloseRequestException(const char *_file_, int _line_, EString s) :
		EIOException(_file_, _line_, s.c_str()) {
    }
};

class EndOfStreamException : public EIOException {
public:
	EndOfStreamException(const char *_file_, int _line_, EString s) :
		EIOException(_file_, _line_, s.c_str()) {
    }

    virtual EString toString() {
        return EString("EndOfStreamException: ") + getMessage();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ServerCnxn_HH_ */
