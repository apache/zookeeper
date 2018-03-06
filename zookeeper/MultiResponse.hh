/*
 * MultiResponse.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef MultiResponse_HH_
#define MultiResponse_HH_

#include "Efc.hh"
#include "../jute/inc/EInputArchive.hh"
#include "../jute/inc/EOutputArchive.hh"
#include "../jute/inc/ERecord.hh"
#include "./proto/CreateResponse.hh"
#include "./proto/MultiHeader.hh"
#include "./proto/SetDataResponse.hh"
#include "./proto/ErrorResponse.hh"

namespace efc {
namespace ezk {

/**
 * Handles the response from a multi request.  Such a response consists of
 * a sequence of responses each prefixed by a MultiResponse that indicates
 * the type of the response.  The end of the list is indicated by a MultiHeader
 * with a negative type.  Each individual response is in the same format as
 * with the corresponding operation in the original request list.
 */
class MultiResponse : public ERecord, public EIterable<OpResult*> {
private:
	EList<OpResult*>* results;// = new ArrayList<OpResult>();

public:
	virtual ~MultiResponse() {
		delete results;
	}

	MultiResponse() {
		results = new EArrayList<OpResult*>();
	}

	void add(OpResult* x) {
        results->add(x);
    }

    virtual sp<EIterator<OpResult*> > iterator(int index=0) {
        return results->iterator(index);
    }

    int size() {
        return results->size();
    }

    virtual void serialize(EOutputArchive* archive, const char* tag) THROWS(EIOException) {
        archive->startRecord(this, tag);

        int index = 0;

        sp<EIterator<OpResult*> > iter = results->iterator();
        while (iter->hasNext()) {
        	OpResult* result = iter->next();

            int err = result->getType() == ZooDefs::OpCode::error ? ((ErrorResult*)result)->getErr() : 0;

            MultiHeader mh(result->getType(), false, err);
            mh.serialize(archive, tag);

            switch (result->getType()) {
                case ZooDefs::OpCode::create: {
                    CreateResponse cr(((CreateResult*) result)->getPath());
                    cr.serialize(archive, tag);
                    break;
                }
                case ZooDefs::OpCode::delete_:
                case ZooDefs::OpCode::check:
                    break;
                case ZooDefs::OpCode::setData: {
                    SetDataResponse sdr(((SetDataResult*) result)->getStat());
                    sdr.serialize(archive, tag);
                    break;
                }
                case ZooDefs::OpCode::error: {
                    ErrorResponse er(((ErrorResult*) result)->getErr());
                    er.serialize(archive, tag);
                    break;
                }
                default:
                    throw EIOException(__FILE__, __LINE__, (EString("Invalid type ") + result->getType() + " in MultiResponse").c_str());
            }
        }
        MultiHeader mr(-1, true, -1);
        mr.serialize(archive, tag);

        archive->endRecord(this, tag);
    }

    virtual void deserialize(EInputArchive* archive, const char* tag) THROWS(EIOException) {
        //results = new ArrayList<OpResult>(); ???

        archive->startRecord(tag);
        MultiHeader h;
        h.deserialize(archive, tag);
        while (!h.getDone()) {
            switch (h.getType()) {
                case ZooDefs::OpCode::create: {
                    CreateResponse cr;
                    cr.deserialize(archive, tag);
                    results->add(new CreateResult(cr.getPath()));
                    break;
                }
                case ZooDefs::OpCode::delete_:
                    results->add(new DeleteResult());
                    break;

                case ZooDefs::OpCode::setData: {
                    SetDataResponse sdr;
                    sdr.deserialize(archive, tag);
                    results->add(new SetDataResult(sdr.getStat()));
                    break;
                }
                case ZooDefs::OpCode::check:
                    results->add(new CheckResult());
                    break;

                case ZooDefs::OpCode::error: {
                    //FIXME: need way to more cleanly serialize/deserialize exceptions
                    ErrorResponse er;
                    er.deserialize(archive, tag);
                    results->add(new ErrorResult(er.getErr()));
                    break;
                }
                default:
                    throw EIOException(__FILE__, __LINE__, (EString("Invalid type ") + h.getType() + " in MultiResponse").c_str());
            }
            h.deserialize(archive, tag);
        }
        archive->endRecord(tag);
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;

        MultiResponse* other = dynamic_cast<MultiResponse*>(o);
        if (!other) return false;

		sp<EIterator<OpResult*> > h = results->iterator();
		sp<EIterator<OpResult*> > i = other->results->iterator();
		while (h->hasNext()) {
			OpResult* rh = h->next();
			if (i->hasNext()) {
				OpResult* ri = i->next();
				if (!rh->equals(ri)) {
					return false;
				}
			} else {
				return false;
			}
		}
		return !i->hasNext();
    }

    virtual int hashCode() {
        int hash = results->size();
		sp<EIterator<OpResult*> > i = results->iterator();
		while (i->hasNext()) {
        	OpResult* result = i->next();
            hash = (hash * 35) + result->hashCode();
        }
        return hash;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* MultiResponse_HH_ */
