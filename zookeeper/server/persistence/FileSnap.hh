/*
 * FileSnap.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef FileSnap_HH_
#define FileSnap_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Util.hh"
#include "./FileHeader.hh"
#include "./SnapShot.hh"
#include "../util/SerializeUtils.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
class FileSnap : virtual public SnapShot, public ESynchronizeable {
private:
    EFile snapDir;
    volatile boolean close_;// = false;
	static const int VERSION = 2;
	static const long dbId = -1;
	static const int SNAP_MAGIC = 1514885966;// ByteBuffer.wrap("ZKSN".getBytes()).getInt();
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FileSnap.class);

	/**
	 * find the last (maybe) valid n snapshots. this does some
	 * minor checks on the validity of the snapshots. It just
	 * checks for / at the end of the snapshot. This does
	 * not mean that the snapshot is truly valid but is
	 * valid with a high probability. also, the most recent
	 * will be first on the list.
	 * @param n the number of most recent snapshots
	 * @return the last n snapshots (the number might be
	 * less than n in case enough snapshots are not available).
	 * @throws IOException
	 */
	sp<EList<EFile*> > findNValidSnapshots(int n) THROWS(EIOException) {
		//@see: List<File> files = Util.sortDataDir(snapDir.listFiles(),"snapshot", false);
		EArray<EFile*> files = snapDir.listFiles();
		Util::sortDataDir(&files, "snapshot", false);

		int count = 0;
		sp<EList<EFile*> > list = new EArrayList<EFile*>();
		auto iter = files.iterator();
		while (iter->hasNext()) {
			EFile* f = iter->next();
			// we should catch the exceptions
			// from the valid snapshot and continue
			// until we find a valid one
			try {
				if (Util::isValidSnapshot(f)) {
					list->add(new EFile(f));
					count++;
					if (count == n) {
						break;
					}
				}
			} catch (EIOException& e) {
				LOG->info(__FILE__, __LINE__, "invalid snapshot " + f->toString(), e);
			}
		}
		return list;
	}

protected:

    /**
     * serialize the datatree and sessions
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param oa the output archive to serialize into
     * @param header the header of this snapshot
     * @throws IOException
     */
	void serialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions,
            EOutputArchive* oa, FileHeader* header) THROWS(EIOException) {
        // this is really a programmatic error and not something that can
        // happen at runtime
        if (header==null)
            throw EIllegalStateException(__FILE__, __LINE__,
                    "Snapshot's not open for writing: uninitialized header");
        header->serialize(oa, "fileheader");
        SerializeUtils::serializeSnapshot(dt, oa, sessions);
    }

public:
	virtual ~FileSnap() {
	}

	FileSnap(EFile* snapDir) : snapDir(snapDir), close_(false) {
    }

    /**
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */ 
	virtual llong deserialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions)
			THROWS(EIOException) {
        // we run through 100 snapshots (not all of them)
        // if we cannot get it running within 100 snapshots
        // we should  give up
		sp<EList<EFile*> > snapList = findNValidSnapshots(100);
        if (snapList->size() == 0) {
            return -1L;
        }
        EFile* snap = null;
        boolean foundValid = false;
        for (int i = 0; i < snapList->size(); i++) {
            snap = snapList->getAt(i);
            try {
                LOG->info("Reading snapshot " + snap->toString());
                EBufferedInputStream snapIS(new EFileInputStream(snap), 8192, true);
                EAdler32 crc32;
                ECheckedInputStream crcIn(&snapIS, &crc32);
                sp<EInputArchive> ia = EBinaryInputArchive::getArchive(&crcIn);
                deserialize(dt, sessions, ia.get());
                llong checkSum = crcIn.getChecksum()->getValue();
                llong val = ia->readLLong("val");
                if (val != checkSum) {
                    throw EIOException(__FILE__, __LINE__, ("CRC corruption in snapshot :  " + snap->toString()).c_str());
                }
                foundValid = true;
                break;
            } catch (EIOException& e) {
                LOG->warn(__FILE__, __LINE__, "problem reading snap file " + snap->toString(), e);
            }
        }
        if (!foundValid) {
            throw EIOException(__FILE__, __LINE__, ("Not able to find valid snapshots in " + snapDir.toString()).c_str());
        }
        dt->lastProcessedZxid = Util::getZxidFromName(snap->getName(), "snapshot");
        return dt->lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    void deserialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions,
            EInputArchive* ia) THROWS(EIOException) {
        FileHeader header;// = new FileHeader();
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw EIOException(__FILE__, __LINE__, (EString("mismatching magic headers ")
                    + header.getMagic() + 
                    " !=  " + SNAP_MAGIC).c_str());
        }
        SerializeUtils::deserializeSnapshot(dt,ia,sessions);
    }

    /**
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
    sp<EFile> findMostRecentSnapshot() THROWS(EIOException) {
    	sp<EList<EFile*> > files = findNValidSnapshots(1);
        if (files->size() == 0) {
            return null;
        }
        return files->getAt(0);
    }
    


    /**
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     * @param the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    sp<EList<EFile*> > findNRecentSnapshots(int n) THROWS(EIOException) {
    	//@see: List<File> files = Util.sortDataDir(snapDir.listFiles(), "snapshot", false);
    	EArray<EFile*> files = snapDir.listFiles();
		Util::sortDataDir(&files, "snapshot", false);

		int count = 0;
		sp<EList<EFile*> > list = new EArrayList<EFile*>();
		auto iter = files.iterator();
		while (iter->hasNext()) {
			EFile* f = iter->next();
            if (count == n)
                break;
            if (Util::getZxidFromName(f->getName(), "snapshot") != -1) {
                count++;
                list->add(new EFile(f));
            }
        }
        return list;
    }

    /**
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     */
    synchronized
    void serialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions, EFile* snapShot)
    		THROWS(EIOException) {
    	SYNCHRONIZED(this) {
			if (!close_) {
				EBufferedOutputStream sessOS(new EFileOutputStream(snapShot), true);
				EAdler32 crc32;
				ECheckedOutputStream crcOut(&sessOS, &crc32);
				sp<EOutputArchive> oa = EBinaryOutputArchive::getArchive(&crcOut);
				FileHeader header(SNAP_MAGIC, VERSION, dbId);
				serialize(dt, sessions, oa.get(), &header);
				llong val = crcOut.getChecksum()->getValue();
				oa->writeLLong(val, "val");
				oa->writeString("/", "path");
				sessOS.flush();
				crcOut.close();
				sessOS.close();
			}
    	}}
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    synchronized
    virtual void close() THROWS(EIOException) {
    	SYNCHRONIZED(this) {
    		close_ = true;
    	}}
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FileSnap_HH_ */
