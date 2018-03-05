/*
 * ZooDefs.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooDefs_HH_
#define ZooDefs_HH_

#include "Efc.hh"
#include "./data/ACL.hh"
#include "./data/Id.hh"

namespace efc {
namespace ezk {

class ZooDefs {
public:
    interface OpCode: virtual public EObject {
        static const int notification = 0;

        static const int create = 1;

        static const int delete_ = 2;

        static const int exists = 3;

        static const int getData = 4;

        static const int setData = 5;

        static const int getACL = 6;

        static const int setACL = 7;

        static const int getChildren = 8;

        static const int sync = 9;

        static const int ping = 11;

        static const int getChildren2 = 12;

        static const int check = 13;

        static const int multi = 14;

        static const int auth = 100;

        static const int setWatches = 101;

//        static const int sasl = 102;

        static const int createSession = -10;

        static const int closeSession = -11;

        static const int error = -1;
    };

    interface Perms : virtual public EObject {
    	static const int READ = 1 << 0;

    	static const int WRITE = 1 << 1;

    	static const int CREATE = 1 << 2;

    	static const int DELETE = 1 << 3;

    	static const int ADMIN = 1 << 4;

    	static const int ALL = READ | WRITE | CREATE | DELETE | ADMIN;
    };

    interface Ids : virtual public EObject {
        /**
         * This ACL gives the world the ability to read.
         */
        EArrayList<sp<ACL> >* READ_ACL_UNSAFE;

        virtual ~Ids() {
        	delete READ_ACL_UNSAFE;
        }

        Ids() {
        	READ_ACL_UNSAFE = new EArrayList<sp<ACL> >();
        	READ_ACL_UNSAFE->add(new ACL(Perms::READ, new Id("world", "anyone")));
        }
    };
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooDefs_HH_ */
