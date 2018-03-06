/*
 * RequestProcessor.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef RequestProcessor_HH_
#define RequestProcessor_HH_

#include "Efc.hh"
#include "./Request.hh"

namespace efc {
namespace ezk {

/**
 * RequestProcessors are chained together to process transactions. Requests are
 * always processed in order. The standalone server, follower, and leader all
 * have slightly different RequestProcessors chained together.
 * 
 * Requests always move forward through the chain of RequestProcessors. Requests
 * are passed to a RequestProcessor through processRequest(). Generally method
 * will always be invoked by a single thread.
 * 
 * When shutdown is called, the request RequestProcessor should also shutdown
 * any RequestProcessors that it is connected to.
 */

//@see: Leader.java#XidRolloverException
class XidRolloverException : public EException {
public:
	XidRolloverException(const char *_file_, int _line_, EString message) :
		EException(_file_, _line_, message.c_str()) {
	}
};

class RequestProcessorException : public EException {
public:
	RequestProcessorException(const char* _file_, int _line_, EString msg, EThrowable* t) :
		EException(_file_, _line_, msg.c_str(), t) {
	}
};

interface RequestProcessor : virtual public EObject {
	virtual ~RequestProcessor() {}

	virtual void processRequest(sp<Request> request) THROWS(RequestProcessorException) = 0;

	virtual void shutdown() = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* RequestProcessor_HH_ */
