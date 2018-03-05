/*
 * Request.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Request_HH_
#define Request_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ServerCnxn.hh"
#include "../KeeperException.hh"
#include "../ZooDefs.hh"
#include "../common/TimeUtils.hh"
#include "../data/Id.hh"
#include "../txn/TxnHeader.hh"
#include "../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * This is the structure that represents a request moving through a chain of
 * RequestProcessors. There are various pieces of information that is tacked
 * onto the request as it is processed.
 */
class Request : public EObject {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(Request.class);

public:
	static sp<Request> requestOfDeath;// = new Request(null, 0, 0, 0, null, null);

    /**
     * @param cnxn
     * @param sessionId
     * @param xid
     * @param type
     * @param bb
     */
    Request(sp<ServerCnxn> cnxn, llong sessionId, int xid, int type,
            sp<EIOByteBuffer> bb, sp<EList<sp<Id> > > authInfo) :
            zxid(-1),
            createTime(TimeUtils::currentElapsedTime()) {
        this->cnxn = cnxn;
        this->sessionId = sessionId;
        this->cxid = xid;
        this->type = type;
        this->request = bb;
        this->authInfo = authInfo;
    }

    llong sessionId;

    int cxid;

    int type;

    sp<EIOByteBuffer> request;

    sp<ServerCnxn> cnxn;

    sp<TxnHeader> hdr;

    sp<ERecord> txn;

    llong zxid;// = -1;

    sp<EList<sp<Id> > > authInfo;

    llong createTime;// = Time.currentElapsedTime();
    
    sp<EObject> getOwner() {
        return owner;
    }
    
    void setOwner(sp<EObject> owner) {
        this->owner = owner;
    }

    /**
     * is the packet type a valid packet in zookeeper
     * 
     * @param type
     *                the type of the packet
     * @return true if a valid packet, false if not
     */
    static boolean isValid(int type) {
        // make sure this is always synchronized with Zoodefs!!
        switch (type) {
        case ZooDefs::OpCode::notification:
            return false;
        case ZooDefs::OpCode::create:
        case ZooDefs::OpCode::delete_:
        case ZooDefs::OpCode::createSession:
        case ZooDefs::OpCode::exists:
        case ZooDefs::OpCode::getData:
        case ZooDefs::OpCode::check:
        case ZooDefs::OpCode::multi:
        case ZooDefs::OpCode::setData:
        case ZooDefs::OpCode::sync:
        case ZooDefs::OpCode::getACL:
        case ZooDefs::OpCode::setACL:
        case ZooDefs::OpCode::getChildren:
        case ZooDefs::OpCode::getChildren2:
        case ZooDefs::OpCode::ping:
        case ZooDefs::OpCode::closeSession:
        case ZooDefs::OpCode::setWatches:
            return true;
        default:
            return false;
        }
    }

    static boolean isQuorum(int type) {
        switch (type) {
        case ZooDefs::OpCode::exists:
        case ZooDefs::OpCode::getACL:
        case ZooDefs::OpCode::getChildren:
        case ZooDefs::OpCode::getChildren2:
        case ZooDefs::OpCode::getData:
            return false;
        case ZooDefs::OpCode::error:
        case ZooDefs::OpCode::closeSession:
        case ZooDefs::OpCode::create:
        case ZooDefs::OpCode::createSession:
        case ZooDefs::OpCode::delete_:
        case ZooDefs::OpCode::setACL:
        case ZooDefs::OpCode::setData:
        case ZooDefs::OpCode::check:
        case ZooDefs::OpCode::multi:
            return true;
        default:
            return false;
        }
    }
    
    static EString op2String(int op) {
        switch (op) {
        case ZooDefs::OpCode::notification:
            return "notification";
        case ZooDefs::OpCode::create:
            return "create";
        case ZooDefs::OpCode::setWatches:
            return "setWatches";
        case ZooDefs::OpCode::delete_:
            return "delete";
        case ZooDefs::OpCode::exists:
            return "exists";
        case ZooDefs::OpCode::getData:
            return "getData";
        case ZooDefs::OpCode::check:
            return "check";
        case ZooDefs::OpCode::multi:
            return "multi";
        case ZooDefs::OpCode::setData:
            return "setData";
        case ZooDefs::OpCode::sync:
              return "sync:";
        case ZooDefs::OpCode::getACL:
            return "getACL";
        case ZooDefs::OpCode::setACL:
            return "setACL";
        case ZooDefs::OpCode::getChildren:
            return "getChildren";
        case ZooDefs::OpCode::getChildren2:
            return "getChildren2";
        case ZooDefs::OpCode::ping:
            return "ping";
        case ZooDefs::OpCode::createSession:
            return "createSession";
        case ZooDefs::OpCode::closeSession:
            return "closeSession";
        case ZooDefs::OpCode::error:
            return "error";
        default:
            return EString("unknown ") + op;
        }
    }

    virtual EString toString() {
    	EString sb;// = new StringBuilder();
        sb.append("sessionid:0x").append(ELLong::toHexString(sessionId))
            .append(" type:").append(op2String(type))
        .append(" cxid:0x").append(ELLong::toHexString(cxid))
        .append(" zxid:0x").append(ELLong::toHexString(hdr == null ?
                    -2 : hdr->getZxid()))
            .append(" txntype:").append(hdr == null ?
                    "unknown" : EString(hdr->getType()));

        // best effort to print the path assoc with this request
        EString path = "n/a";
        if (type != ZooDefs::OpCode::createSession
                && type != ZooDefs::OpCode::setWatches
                && type != ZooDefs::OpCode::closeSession
                && request != null
                && request->remaining() >= 4)
        {
            try {
                // make sure we don't mess with request itself
                sp<EIOByteBuffer> rbuf = request->asReadOnlyBuffer();
                rbuf->clear();
                int pathLen = rbuf->getInt();
                // sanity check
                if (pathLen >= 0
                        && pathLen < 4096
                        && rbuf->remaining() >= pathLen)
                {
                    byte b[pathLen];// = new byte[pathLen];
                    rbuf->get(b, pathLen, pathLen);
                    path = EString((char*)b, 0, pathLen);
                }
            } catch (EException& e) {
                // ignore - can't find the path, will output "n/a" instead
            }
        }
        sb.append(" reqpath:").append(path);

        return sb;
    }

    void setException(sp<KeeperException> e) {
        this->e = e;
    }
	
    sp<KeeperException> getException() {
        return e;
    }

private:
    sp<EObject> owner;

    sp<KeeperException> e;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Request_HH_ */
