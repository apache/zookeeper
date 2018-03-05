/*
 * AtomicFileOutputStream.cpp
 *
 *  Created on: 2017-11-20
 *      Author: cxxjava@163.com
 */

#include "./AtomicFileOutputStream.hh"

namespace efc {
namespace ezk {

#define TMP_EXTENSION  ".tmp"

sp<ELogger> AtomicFileOutputStream::LOG = ELoggerManager::getLogger("AtomicFileOutputStream");

AtomicFileOutputStream::~AtomicFileOutputStream() {
	//
}

// Code unfortunately must be duplicated below since we can't assign
// anything
// before calling super
AtomicFileOutputStream::AtomicFileOutputStream(EFile* f) :
		EFilterOutputStream(
				new EFileOutputStream(
						EString::formatOf("%s%s", f->getPath().c_str(),
								TMP_EXTENSION).c_str()), true),
		origFile(f->getPath().c_str()),
		tmpFile(EString::formatOf("%s%s", f->getPath().c_str(), TMP_EXTENSION).c_str()),
		closed(false) {
}

void AtomicFileOutputStream::write(const void* b, int len) {
	_out->write(b, len);
}

void AtomicFileOutputStream::close() {
	if (closed) return;

	closed = true;

	boolean triedToClose = false, success = false;
	ON_FINALLY_NOTHROW(
		if (success) {
			boolean renamed = tmpFile.renameTo(&origFile);
			if (!renamed) {
				// On windows, renameTo does not replace.
				if (!origFile.remove() || !tmpFile.renameTo(&origFile)) {
						throw EIOException(__FILE__, __LINE__,
								EString::formatOf("Could not rename temporary file %s to %s",
										tmpFile.toString().c_str(),
										origFile.toString().c_str()).c_str());
				}
			}
		} else {
			if (!triedToClose) {
				// If we failed when flushing, try to close it to not leak
				// an FD
				//@see: IOUtils.closeStream(out);
				try {
					_out->close();
				} catch (EIOException& e) {
				}
			}
			// close wasn't successful, try to delete the tmp file
			if (!tmpFile.remove()) {
				LOG->warn(EString::formatOf("Unable to delete tmp file %s", tmpFile.toString().c_str()));
			}
		}
	) {
		flush();

		//@see: ((FileOutputStream) out).getChannel().force(true);
		EFileOutputStream* fos = dynamic_cast<EFileOutputStream*>(_out);
		EFileDispatcher::force(eso_fileno(fos->getFD()), true); //FIXME: fdatasync?

		triedToClose = true;
		EFilterOutputStream::close();
		success = true;
	}}
}

void AtomicFileOutputStream::abort() {
	try {
		EFilterOutputStream::close();
	} catch (EIOException& ioe) {
		LOG->warn(EString::formatOf("Unable to abort file %s", tmpFile.toString().c_str()));
	}
	if (!tmpFile.remove()) {
		LOG->warn(EString::formatOf("Unable to delete tmp file during abort %s", tmpFile.toString().c_str()));
	}
}

} /* namespace ezk */
} /* namespace efc */
