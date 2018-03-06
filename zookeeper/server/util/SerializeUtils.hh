/*
 * SerializeUtils.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef SerializeUtils_HH_
#define SerializeUtils_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../ZooTrace.hh"
#include "../DataTree.hh"
#include "../../ZooDefs.hh"
#include "../../txn/CreateSessionTxn.hh"
#include "../../txn/CreateTxn.hh"
#include "../../txn/CreateTxnV0.hh"
#include "../../txn/DeleteTxn.hh"
#include "../../txn/ErrorTxn.hh"
#include "../../txn/SetACLTxn.hh"
#include "../../txn/SetDataTxn.hh"
#include "../../txn/TxnHeader.hh"
#include "../../txn/MultiTxn.hh"

namespace efc {
namespace ezk {

class SerializeUtils {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(SerializeUtils.class);
    
public:
	static sp<ERecord> deserializeTxn(sp<EA<byte> > txnBytes, TxnHeader* hdr) THROWS(EIOException) {
        EByteArrayInputStream bais(txnBytes->address(), txnBytes->length());// = new ByteArrayInputStream(txnBytes);
        sp<EInputArchive> ia = EBinaryInputArchive::getArchive(&bais);

        hdr->deserialize(ia.get(), "hdr");
        bais.mark(bais.available());
        sp<ERecord> txn = null;
        switch (hdr->getType()) {
        case ZooDefs::OpCode::createSession:
            // This isn't really an error txn; it just has the same
            // format. The error represents the timeout
            txn = new CreateSessionTxn();
            break;
        case ZooDefs::OpCode::closeSession:
            return null;
        case ZooDefs::OpCode::create:
            txn = new CreateTxn();
            break;
        case ZooDefs::OpCode::delete_:
            txn = new DeleteTxn();
            break;
        case ZooDefs::OpCode::setData:
            txn = new SetDataTxn();
            break;
        case ZooDefs::OpCode::setACL:
            txn = new SetACLTxn();
            break;
        case ZooDefs::OpCode::error:
            txn = new ErrorTxn();
            break;
        case ZooDefs::OpCode::multi:
            txn = new MultiTxn();
            break;
        default:
            throw EIOException(__FILE__, __LINE__, (EString("Unsupported Txn with type=%d") + hdr->getType()).c_str());
        }
        if (txn != null) {
            try {
                txn->deserialize(ia.get(), "txn");
            } catch(EEOFException& e) {
                // perhaps this is a V0 Create
                if (hdr->getType() == ZooDefs::OpCode::create) {
                    sp<CreateTxn> create = dynamic_pointer_cast<CreateTxn>(txn);
                    bais.reset();
                    sp<CreateTxnV0> createv0 = new CreateTxnV0();
                    createv0->deserialize(ia.get(), "txn");
                    // cool now make it V1. a -1 parentCVersion will
                    // trigger fixup processing in processTxn
                    create->setPath(createv0->getPath());
                    create->setData(createv0->getData());
                    create->setAcl(createv0->getAcl());
                    create->setEphemeral(createv0->getEphemeral());
                    create->setParentCVersion(-1);
                } else {
                    throw e;
                }
            }
        }
        return txn;
    }

    static void deserializeSnapshot(sp<DataTree> dt, EInputArchive* ia,
            EConcurrentHashMap<llong, EInteger>* sessions)  THROWS(EIOException) {
        int count = ia->readInt("count");
        while (count > 0) {
            llong id = ia->readLLong("id");
            int to = ia->readInt("timeout");
            sessions->put(id, new EInteger(to));
            if (LOG->isTraceEnabled()) {
                ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
                        (EString("loadData --- session in archive: ") + id
                        + " with timeout: " + to).c_str());
            }
            count--;
        }
        dt->deserialize(ia, "tree");
    }

    static void serializeSnapshot(sp<DataTree> dt, EOutputArchive* oa,
    		EConcurrentHashMap<llong, EInteger>* sessions)  THROWS(EIOException) {
        //@see: HashMap<Long, Integer> sessSnap = new HashMap<Long, Integer>(sessions);
    	EHashMap<llong, sp<EInteger> > sessSnap;
    	auto iter1 = sessions->entrySet()->iterator();
    	while (iter1->hasNext()) {
    		auto e = iter1->next();
    		sessSnap.put(e->getKey(), new EInteger(e->getValue()->intValue()));
    	}

        oa->writeInt(sessSnap.size(), "count");
        auto iter2 = sessSnap.entrySet()->iterator();
        while (iter2->hasNext()) {
        	auto e = iter2->next();
            oa->writeLLong(e->getKey(), "id");
            oa->writeInt(e->getValue()->intValue(), "timeout");
        }
        dt->serialize(oa, "tree");
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SerializeUtils_HH_ */
