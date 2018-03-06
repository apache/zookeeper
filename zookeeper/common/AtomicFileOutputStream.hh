/*
 * AtomicFileOutputStream.hh
 *
 *  Created on: 2017-11-20

 */

#ifndef ATOMICFILEOUTPUTSTREAM_HH_
#define ATOMICFILEOUTPUTSTREAM_HH_

#include "Efc.hh"
#include "ELog.hh"

namespace efc {
namespace ezk {

/*
 * This code is originally from HDFS, see the similarly named files there
 * in case of bug fixing, history, etc...
 */

/**
 * A FileOutputStream that has the property that it will only show up at its
 * destination once it has been entirely written and flushed to disk. While
 * being written, it will use a .tmp suffix.
 *
 * When the output stream is closed, it is flushed, fsynced, and will be moved
 * into place, overwriting any file that already exists at that location.
 *
 * <b>NOTE</b>: on Windows platforms, it will not atomically replace the target
 * file - instead the target file is deleted before this one is moved into
 * place.
 */

class AtomicFileOutputStream: public efc::EFilterOutputStream {
public:
	virtual ~AtomicFileOutputStream();

	AtomicFileOutputStream(EFile* f) THROWS(EFileNotFoundException);

	/**
	 * The default write method in FilterOutputStream does not call the write
	 * method of its underlying input stream with the same arguments. Instead
	 * it writes the data byte by byte, override it here to make it more
	 * efficient.
	 */
	virtual void write(const void *b, int len) THROWS(EIOException);

	virtual void close() THROWS(EIOException);

	 /**
	 * Close the atomic file, but do not "commit" the temporary file on top of
	 * the destination. This should be used if there is a failure in writing.
	 */
	void abort();

private:
	EFile origFile;
	EFile tmpFile;

	boolean closed;// = false;

	static sp<ELogger> LOG;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ATOMICFILEOUTPUTSTREAM_HH_ */
