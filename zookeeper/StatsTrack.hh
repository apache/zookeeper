/*
 * StatsTrack.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef StatsTrack_HH_
#define StatsTrack_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * a class that represents the stats associated with quotas
 */
class StatsTrack {
private:
	int count;
    llong bytes;
    EString countStr;// = "count";
    EString byteStr;// = "bytes";

public:
    virtual ~StatsTrack() {
    	//
    }

    /**
     * a default constructor for
     * stats
     */
    StatsTrack(): count(-1), bytes(-1L), countStr("count"), byteStr("bytes") {
        //
    }
    /**
     * the stat string should be of the form count=int,bytes=long
     * if stats is called with null the count and bytes are initialized
     * to -1.
     * @param stats the stat string to be intialized with
     */
    StatsTrack(EString stats): count(-1), bytes(-1L), countStr("count"), byteStr("bytes") {
    	/*
    	 if (stats == null) {
            stats = "count=-1,bytes=-1";
         }
    	 */
        EArrayList<EString*> split = EPattern::split(",", stats.c_str(), 0);
        if (split.size() != 2) {
            throw EIllegalArgumentException(__FILE__, __LINE__, ("invalid string " + stats).c_str());
        }
        char *p;
        p = eso_strstr(split[0]->c_str(), "=");
        if (p) {
        	count = EInteger::parseInt(p + 1);
        }
        p = eso_strstr(split[1]->c_str(), "=");
		if (p) {
			bytes = ELLong::parseLLong(p + 1);
		}
    }


    /**
     * get the count of nodes allowed as part of quota
     *
     * @return the count as part of this string
     */
    int getCount() {
        return this->count;
    }

    /**
     * set the count for this stat tracker.
     *
     * @param count
     *            the count to set with
     */
    void setCount(int count) {
    	this->count = count;
    }

    /**
     * get the count of bytes allowed as part of quota
     *
     * @return the bytes as part of this string
     */
    llong getBytes() {
        return this->bytes;
    }

    /**
     * set teh bytes for this stat tracker.
     *
     * @param bytes
     *            the bytes to set with
     */
    void setBytes(long bytes) {
    	this->bytes = bytes;
    }

    /*
     * returns the string that maps to this stat tracking.
     */
    virtual EString toString() {
        return EString(countStr) + "=" + count + "," + byteStr + "=" + bytes;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* StatsTrack_HH_ */
