/*
 * MultiTransactionRecord.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef MultiTransactionRecord_HH_
#define MultiTransactionRecord_HH_

#include "Efc.hh"
#include "../jute/inc/EInputArchive.hh"
#include "../jute/inc/EOutputArchive.hh"
#include "../jute/inc/ERecord.hh"
#include "./proto/MultiHeader.hh"

namespace efc {
namespace ezk {

/**
 * Encodes a composite transaction.  In the wire format, each transaction
 * consists of a single MultiHeader followed by the appropriate request.
 * Each of these MultiHeaders has a type which indicates
 * the type of the following transaction or a negative number if no more transactions
 * are included.
 */
class MultiTransactionRecord : public ERecord, public EIterable<sp<Op> > {
private:
	EList<sp<Op> >* ops;// = new ArrayList<Op>();

public:
	virtual ~MultiTransactionRecord() {
		delete ops;
	}

	MultiTransactionRecord() {
		ops = new EArrayList<sp<Op> >();
    }

    MultiTransactionRecord(sp<EIterable<sp<Op> > > ops) {
    	sp<EIterator<sp<Op> > > iter = ops->iterator();
    	while (iter->hasNext()) {
            add(iter->next());
        }
    }

    virtual sp<EIterator<sp<Op> > > iterator(int index=0) {
        return ops->iterator() ;
    }

    void add(sp<Op> op) {
        ops->add(op);
    }

    int size() {
        return ops->size();
    }

    virtual void serialize(EOutputArchive* archive, const char* tag) THROWS(EIOException) {
        archive->startRecord(this, tag);
        int index = 0 ;
        sp<EIterator<sp<Op> > > iter = ops->iterator();
        while (iter->hasNext()) {
        	sp<Op> op = iter->next();
            MultiHeader h(op->getType(), false, -1);
            h.serialize(archive, tag);
            switch (op->getType()) {
               case ZooDefs::OpCode::create:
                    op->toRequestRecord()->serialize(archive, tag);
                    break;
                case ZooDefs::OpCode::delete_:
                    op->toRequestRecord()->serialize(archive, tag);
                    break;
                case ZooDefs::OpCode::setData:
                    op->toRequestRecord()->serialize(archive, tag);
                    break;
                case ZooDefs::OpCode::check:
                    op->toRequestRecord()->serialize(archive, tag);
                    break;
                default:
                    throw EIOException(__FILE__, __LINE__, "Invalid type of op");
            }
        }
        MultiHeader mh(-1, true, -1);
        mh.serialize(archive, tag);

        archive->endRecord(this, tag);
    }

    virtual void deserialize(EInputArchive* archive, const char* tag) THROWS(EIOException) {
        archive->startRecord(tag);
        MultiHeader h;// = new MultiHeader();
        h.deserialize(archive, tag);

        while (!h.getDone()) {
            switch (h.getType()) {
               case ZooDefs::OpCode::create: {
                    CreateRequest cr;// = new CreateRequest();
                    cr.deserialize(archive, tag);
                    add(Op::create(cr.getPath(), cr.getData(), cr.getAcl(), cr.getFlags()));
                    break;
               }
                case ZooDefs::OpCode::delete_: {
                    DeleteRequest dr;// = new DeleteRequest();
                    dr.deserialize(archive, tag);
                    add(Op::delete_(dr.getPath(), dr.getVersion()));
                    break;
                }
                case ZooDefs::OpCode::setData: {
                    SetDataRequest sdr;// = new SetDataRequest();
                    sdr.deserialize(archive, tag);
                    add(Op::setData(sdr.getPath(), sdr.getData(), sdr.getVersion()));
                    break;
                }
                case ZooDefs::OpCode::check: {
                    CheckVersionRequest cvr;// = new CheckVersionRequest();
                    cvr.deserialize(archive, tag);
                    add(Op::check(cvr.getPath(), cvr.getVersion()));
                    break;
                }
                default:
                    throw EIOException(__FILE__, __LINE__, "Invalid type of op");
            }
            h.deserialize(archive, tag);
        }
        archive->endRecord(tag);
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;

        MultiTransactionRecord* that = dynamic_cast<MultiTransactionRecord*>(o);
        if (!that) return false;

		sp<EIterator<sp<Op> > > iter = ops->iterator();
		sp<EIterator<sp<Op> > > other = that->ops->iterator();
		while (iter->hasNext()) {
			sp<Op> op = iter->next();
			boolean hasMoreData = other->hasNext();
			if (!hasMoreData) {
				return false;
			}
			sp<Op> otherOp = other->next();
			if (!op->equals(otherOp.get())) {
				return false;
			}
		}
		return !other->hasNext();
    }

	virtual int hashCode() {
		int h = 1023;
		sp<EIterator<sp<Op> > > iter = ops->iterator();
		while (iter->hasNext()) {
			sp<Op> op = iter->next();
			h = h * 25 + op->hashCode();
		}
		return h;
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* MultiTransactionRecord_HH_ */
