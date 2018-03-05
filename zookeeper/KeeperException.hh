/*
 * KeeperException.hh
 *
 *  Created on: 2013-3-14
 *      Author: cxxjava@163.com
 */

#ifndef KeeperException_HH_
#define KeeperException_HH_

#include "Efc.hh"
#include "./OpResult.hh"

namespace efc {
namespace ezk {


class KeeperException : public EException {
public:
	/** Codes which represent the various KeeperException
	 * types. This enum replaces the deprecated earlier static final int
	 * constants. The old, deprecated, values are in "camel case" while the new
	 * enum values are in all CAPS.
	 */
	enum Code {
		/** Everything is OK */
		OK = 0,

		/** System and server-side errors.
		 * This is never thrown by the server, it shouldn't be used other than
		 * to indicate a range. Specifically error codes greater than this
		 * value, but lesser than {@link #APIERROR}, are system errors.
		 */
		SYSTEMERROR  = -1,

		/** A runtime inconsistency was found */
		RUNTIMEINCONSISTENCY =-2,
		/** A data inconsistency was found */
		DATAINCONSISTENCY = -3,
		/** Connection to the server has been lost */
		CONNECTIONLOSS = -4,
		/** Error while marshalling or unmarshalling data */
		MARSHALLINGERROR  = -5,
		/** Operation is unimplemented */
		UNIMPLEMENTED = -6,
		/** Operation timeout */
		OPERATIONTIMEOUT = -7,
		/** Invalid arguments */
		BADARGUMENTS = -8,

		/** API errors.
		 * This is never thrown by the server, it shouldn't be used other than
		 * to indicate a range. Specifically error codes greater than this
		 * value are API errors (while values less than this indicate a
		 * {@link #SYSTEMERROR}).
		 */
		APIERROR = -100,

		/** Node does not exist */
		NONODE = -101,
		/** Not authenticated */
		NOAUTH = -102,
		/** Version conflict */
		BADVERSION = -103,
		/** Ephemeral nodes may not have children */
		NOCHILDRENFOREPHEMERALS = -108,
		/** The node already exists */
		NODEEXISTS = -110,
		/** The node has children */
		NOTEMPTY = -111,
		/** The session has been expired by the server */
		SESSIONEXPIRED = -112,
		/** Invalid callback specified */
		INVALIDCALLBACK = -113,
		/** Invalid ACL specified */
		INVALIDACL = -114,
		/** Client authentication failed */
		AUTHFAILED = -115,
		/** Session moved to another server, so operation is ignored */
		SESSIONMOVED = -118,
		/** State-changing request is passed to read-only server */
		NOTREADONLY = -119
	};

	/**
	 * Read the error Code for this exception
	 * @return the error Code for this exception
	 */
	Code code() {
		return code_;
	}

	/**
	 * Read the path for this exception
	 * @return the path associated with this error, null if none
	 */
	EString getPath() {
		return path;
	}

	virtual const char* getMessage() {
		if (detailMessage.isEmpty()) {
			if (path.isEmpty()) {
				detailMessage =  "KeeperErrorCode = " + getCodeMessage(code_);
			} else {
				detailMessage = "KeeperErrorCode = " + getCodeMessage(code_) + " for " + path;
			}
		}
		return detailMessage.c_str();
	}

	static EString getCodeMessage(Code code) {
		switch (code) {
			case OK:
				return "ok";
			case SYSTEMERROR:
				return "SystemError";
			case RUNTIMEINCONSISTENCY:
				return "RuntimeInconsistency";
			case DATAINCONSISTENCY:
				return "DataInconsistency";
			case CONNECTIONLOSS:
				return "ConnectionLoss";
			case MARSHALLINGERROR:
				return "MarshallingError";
			case UNIMPLEMENTED:
				return "Unimplemented";
			case OPERATIONTIMEOUT:
				return "OperationTimeout";
			case BADARGUMENTS:
				return "BadArguments";
			case APIERROR:
				return "APIError";
			case NONODE:
				return "NoNode";
			case NOAUTH:
				return "NoAuth";
			case BADVERSION:
				return "BadVersion";
			case NOCHILDRENFOREPHEMERALS:
				return "NoChildrenForEphemerals";
			case NODEEXISTS:
				return "NodeExists";
			case INVALIDACL:
				return "InvalidACL";
			case AUTHFAILED:
				return "AuthFailed";
			case NOTEMPTY:
				return "Directory not empty";
			case SESSIONEXPIRED:
				return "Session expired";
			case INVALIDCALLBACK:
				return "Invalid callback";
			case SESSIONMOVED:
				return "Session moved";
			case NOTREADONLY:
				return "Not a read-only call";
			default:
				return EString("Unknown error ") + code;
		}
	}

	/**
	 * All non-specific keeper exceptions should be constructed via
	 * this factory method in order to guarantee consistency in error
	 * codes and such.  If you know the error code, then you should
	 * construct the special purpose exception directly.  That will
	 * allow you to have the most specific possible declarations of
	 * what exceptions might actually be thrown.
	 *
	 * @param code The error code.
	 * @param path The ZooKeeper path being operated on.
	 * @return The specialized exception, presumably to be thrown by
	 *  the caller.
	 */
	static sp<KeeperException> create(const char* _file_, int _line_, Code code);

	static sp<KeeperException> create(const char* _file_, int _line_, Code code, EString path) {
		sp<KeeperException> r = create(_file_, _line_, code);
		r->path = path;
		return r;
	}

protected:

	KeeperException(const char *_file_, int _line_, Code code) :
		EException(_file_, _line_, 0) {
		this->code_ = code;
	}

	KeeperException(const char *_file_, int _line_, Code code, EString path) :
		EException(_file_, _line_, 0) {
		this->code_ = code;
		this->path = path;
	}

private:
	Code code_;

	EString path;
};

//=============================================================================

/**
 *  @see Code#APIERROR
 */
class APIErrorException : public KeeperException {
public:
	APIErrorException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::APIERROR) {
	}
};

/**
 *  @see Code#AUTHFAILED
 */
class AuthFailedException : public KeeperException {
public:
	AuthFailedException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::AUTHFAILED) {
	}
};

/**
 *  @see Code#BADARGUMENTS
 */
class BadArgumentsException : public KeeperException {
public:
	BadArgumentsException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::BADARGUMENTS) {
	}
	BadArgumentsException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::BADARGUMENTS, path) {
	}
};

/**
 * @see Code#BADVERSION
 */
class BadVersionException : public KeeperException {
public:
	BadVersionException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::BADVERSION) {
	}
	BadVersionException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::BADVERSION, path) {
	}
};

/**
 * @see Code#CONNECTIONLOSS
 */
class ConnectionLossException : public KeeperException {
public:
	ConnectionLossException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::CONNECTIONLOSS) {
	}
};

/**
 * @see Code#DATAINCONSISTENCY
 */
class DataInconsistencyException : public KeeperException {
public:
	DataInconsistencyException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::DATAINCONSISTENCY) {
	}
};

/**
 * @see Code#INVALIDACL
 */
class InvalidACLException : public KeeperException {
public:
	InvalidACLException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::INVALIDACL) {
	}
	InvalidACLException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::INVALIDACL, path) {
	}
};

/**
 * @see Code#INVALIDCALLBACK
 */
class InvalidCallbackException : public KeeperException {
public:
	InvalidCallbackException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::INVALIDCALLBACK) {
	}
};

/**
 * @see Code#MARSHALLINGERROR
 */
class MarshallingErrorException : public KeeperException {
public:
	MarshallingErrorException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::MARSHALLINGERROR) {
	}
};

/**
 * @see Code#NOAUTH
 */
class NoAuthException : public KeeperException {
public:
	NoAuthException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NOAUTH) {
	}
};

/**
 * @see Code#NOCHILDRENFOREPHEMERALS
 */
class NoChildrenForEphemeralsException : public KeeperException {
public:
	NoChildrenForEphemeralsException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NOCHILDRENFOREPHEMERALS) {
	}
	NoChildrenForEphemeralsException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::NOCHILDRENFOREPHEMERALS, path) {
	}
};

/**
 * @see Code#NODEEXISTS
 */
class NodeExistsException : public KeeperException {
public:
	NodeExistsException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NODEEXISTS) {
	}
	NodeExistsException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::NODEEXISTS, path) {
	}
};

/**
 * @see Code#NONODE
 */
class NoNodeException : public KeeperException {
public:
	NoNodeException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NONODE) {
	}
	NoNodeException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::NONODE, path) {
	}
};

/**
 * @see Code#NOTEMPTY
 */
class NotEmptyException : public KeeperException {
public:
	NotEmptyException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NOTEMPTY) {
	}
	NotEmptyException(const char *_file_, int _line_, EString path) : KeeperException(_file_, _line_, Code::NOTEMPTY, path) {
	}
};

/**
 * @see Code#OPERATIONTIMEOUT
 */
class OperationTimeoutException : public KeeperException {
public:
	OperationTimeoutException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::OPERATIONTIMEOUT) {
	}
};

/**
 * @see Code#RUNTIMEINCONSISTENCY
 */
class RuntimeInconsistencyException : public KeeperException {
public:
	RuntimeInconsistencyException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::RUNTIMEINCONSISTENCY) {
	}
};

/**
 * @see Code#SESSIONEXPIRED
 */
class SessionExpiredException : public KeeperException {
public:
	SessionExpiredException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::SESSIONEXPIRED) {
	}
};

/**
 * @see Code#SESSIONMOVED
 */
class SessionMovedException : public KeeperException {
public:
	SessionMovedException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::SESSIONMOVED) {
	}
};

/**
 * @see Code#NOTREADONLY
 */
class NotReadOnlyException : public KeeperException {
public:
	NotReadOnlyException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::NOTREADONLY) {
	}
};

/**
 * @see Code#SYSTEMERROR
 */
class SystemErrorException : public KeeperException {
public:
	SystemErrorException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::SYSTEMERROR) {
	}
};

/**
 * @see Code#UNIMPLEMENTED
 */
class UnimplementedException : public KeeperException {
public:
	UnimplementedException(const char *_file_, int _line_) : KeeperException(_file_, _line_, Code::UNIMPLEMENTED) {
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* KeeperException_HH_ */
