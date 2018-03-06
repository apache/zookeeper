/*
 * ZooTrace.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./Op.hh"

namespace efc {
namespace ezk {

sp<Op> Op::create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl, int flags) {
	return new Create(path, data, acl, flags);
}

sp<Op> Op::create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl, CreateMode::Mode createMode) {
	return new Create(path, data, acl, createMode);
}

sp<Op> Op::delete_(EString path, int version) {
	return new Delete(path, version);
}

sp<Op> Op::setData(EString path, sp<EA<byte> > data, int version) {
	return new SetData(path, data, version);
}

sp<Op> Op::check(EString path, int version) {
	return new Check(path, version);
}

} /* namespace ezk */
} /* namespace efc */
