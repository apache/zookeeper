/*
 * KeeperException.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./KeeperException.hh"

namespace efc {
namespace ezk {

sp<KeeperException> KeeperException::create(const char* _file_, int _line_, Code code) {
	switch (code) {
		case SYSTEMERROR:
			return new SystemErrorException(_file_, _line_);
		case RUNTIMEINCONSISTENCY:
			return new RuntimeInconsistencyException(_file_, _line_);
		case DATAINCONSISTENCY:
			return new DataInconsistencyException(_file_, _line_);
		case CONNECTIONLOSS:
			return new ConnectionLossException(_file_, _line_);
		case MARSHALLINGERROR:
			return new MarshallingErrorException(_file_, _line_);
		case UNIMPLEMENTED:
			return new UnimplementedException(_file_, _line_);
		case OPERATIONTIMEOUT:
			return new OperationTimeoutException(_file_, _line_);
		case BADARGUMENTS:
			return new BadArgumentsException(_file_, _line_);
		case APIERROR:
			return new APIErrorException(_file_, _line_);
		case NONODE:
			return new NoNodeException(_file_, _line_);
		case NOAUTH:
			return new NoAuthException(_file_, _line_);
		case BADVERSION:
			return new BadVersionException(_file_, _line_);
		case NOCHILDRENFOREPHEMERALS:
			return new NoChildrenForEphemeralsException(_file_, _line_);
		case NODEEXISTS:
			return new NodeExistsException(_file_, _line_);
		case INVALIDACL:
			return new InvalidACLException(_file_, _line_);
		case AUTHFAILED:
			return new AuthFailedException(_file_, _line_);
		case NOTEMPTY:
			return new NotEmptyException(_file_, _line_);
		case SESSIONEXPIRED:
			return new SessionExpiredException(_file_, _line_);
		case INVALIDCALLBACK:
			return new InvalidCallbackException(_file_, _line_);
		case SESSIONMOVED:
			return new SessionMovedException(_file_, _line_);
		case NOTREADONLY:
			return new NotReadOnlyException(_file_, _line_);

		case OK:
		default:
			throw EIllegalArgumentException(_file_, _line_, "Invalid exception code");
	}
}

} /* namespace ezk */
} /* namespace efc */
