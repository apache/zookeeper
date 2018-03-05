/*
 * ConfigException.hh
 *
 *  Created on: 2013-3-14
 *      Author: cxxjava@163.com
 */

#ifndef ConfigException_HH_
#define ConfigException_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

#define CONFIGEXCEPTION        CONFIGEXCEPTION(__FILE__, __LINE__, errno)
#define CONFIGEXCEPTIONS(msg)  CONFIGEXCEPTION(__FILE__, __LINE__, msg)

/**
 * Signals that a data format error has occurred.
 *
 * @version 	1.14, 11/17/05
 */

class ConfigException: public EException {
public:
	/**
	 * Constructs an <code>ConfigException</code> with no
	 * detail message.
	 *
	 * @param   _file_   __FILE__
	 * @param   _line_   __LINE__
	 * @param   errn     errno
	 */
	ConfigException(const char *_file_, int _line_, int errn = 0) :
			EException(_file_, _line_, errn) {
	}

	/**
	 * Constructs an <code>ConfigException</code> with the
	 * specified detail message.
	 *
	 * @param   _file_   __FILE__.
	 * @param   _line_   __LINE__.
	 * @param   s   the detail message.
	 */
	ConfigException(const char *_file_,
			int _line_, const char *s) :
			EException(_file_, _line_, s) {
	}

	/**
	 * Constructs an <code>Exception</code> with the specified detail message.
	 *
	 * @param   _file_   __FILE__
	 * @param   _line_   __LINE__
	 * @param   cause    the cause (which is saved for later retrieval by the
	 *         {@link #getCause()} method).  (A {@code null} value is
	 *         permitted, and indicates that the cause is nonexistent or
	 *         unknown.)
	 */
	ConfigException(const char *_file_, int _line_, EThrowable* cause) :
		EException(_file_, _line_, cause) {
	}

	/**
	 * Constructs a new exception with the specified detail message and
	 * cause.
	 *
	 * @param   _file_   __FILE__
	 * @param   _line_   __LINE__
	 * @param   s   the detail message.
	 * @param   cause    the cause (which is saved for later retrieval by the
	 *         {@link #getCause()} method).  (A {@code null} value is
	 *         permitted, and indicates that the cause is nonexistent or
	 *         unknown.)
	 */
	ConfigException(const char *_file_, int _line_, const char *s, EThrowable* cause) :
		EException(_file_, _line_, s, cause) {
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ConfigException_HH_ */
