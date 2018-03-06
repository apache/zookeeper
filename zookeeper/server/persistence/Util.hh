/*
 * Util.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Persistence_Util_HH_
#define Persistence_Util_HH_

#include "Efc.hh"

#include "../DataTree.hh"
#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * A collection of utility methods for dealing with file name parsing, 
 * low level I/O file operations and marshalling/unmarshalling.
 */
class Util {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(Util.class);
	constexpr static const char* SNAP_DIR="snapDir";
	constexpr static const char* LOG_DIR="logDir";
	constexpr static const char* DB_FORMAT_CONV="dbFormatConversion";
    
public:
    /**
     * Creates a snapshot file name.
     * 
     * @param zxid used as a suffix
     * @return file name
     */
    static EString makeSnapshotName(llong zxid) {
        return "snapshot." + ELLong::toHexString(zxid);
    }
    
    /**
     * Extracts snapshot directory property value from the container.
     * 
     * @param props properties container
     * @return file representing the snapshot directory
     */
    static EFile getSnapDir(EProperties& props) {
        return EFile(props.getProperty(SNAP_DIR)->c_str());
    }

    /**
     * Extracts zxid from the file name. The file name should have been created
     * using one of the {@link makeLogName} or {@link makeSnapshotName}.
     * 
     * @param name the file name to parse
     * @param prefix the file name prefix (snapshot or log)
     * @return zxid
     */
    static llong getZxidFromName(EString name, EString prefix) {
        llong zxid = -1;
        auto nameParts = EPattern::split("\\.", name.c_str(), 0);
        if (nameParts.length() == 2 && nameParts[0]->equals(prefix)) {
            try {
                zxid = ELLong::parseLLong(nameParts[1]->c_str(), 16);
            } catch (ENumberFormatException& e) {
            }
        }
        return zxid;
    }

    /**
     * Verifies that the file is a valid snapshot. Snapshot may be invalid if 
     * it's incomplete as in a situation when the server dies while in the process
     * of storing a snapshot. Any file that is not a snapshot is also 
     * an invalid snapshot. 
     * 
     * @param f file to verify
     * @return true if the snapshot is valid
     * @throws IOException
     */
    static boolean isValidSnapshot(EFile* f) THROWS(EIOException) {
        if (f==null || getZxidFromName(f->getName(), "snapshot") == -1)
            return false;

        // Check for a valid snapshot
        ERandomAccessFile raf(f, "r");
        {
            // including the header and the last / bytes
            // the snapshot should be atleast 10 bytes
            if (raf.length() < 10) {
                return false;
            }
            raf.seek(raf.length() - 5);
            byte bytes[5];
            int readlen = 0;
            int l;
            while (readlen < 5 &&
                  (l = raf.read(bytes + readlen, 5 - readlen)) >= 0) {
                readlen += l;
            }
            if (readlen != 5) {
                LOG->info(("Invalid snapshot " + f->toString()
                        + " too short, len = " + readlen).c_str());
                return false;
            }
            sp<EIOByteBuffer> bb = EIOByteBuffer::wrap(bytes, 5);
            int len = bb->getInt();
            byte b = bb->get();
            if (len != 1 || b != '/') {
                LOG->info(("Invalid snapshot " + f->toString() + " len = " + len
                        + " byte = " + (b & 0xff)).c_str());
                return false;
            }
        }

        return true;
    }

    /**
     * Reads a transaction entry from the input archive.
     * @param ia archive to read from
     * @return null if the entry is corrupted or EOF has been reached; a buffer
     * (possible empty) containing serialized transaction record.
     * @throws IOException
     */
    static sp<EA<byte> > readTxnBytes(EInputArchive* ia) THROWS(EIOException) {
        try{
        	sp<EA<byte> > bytes = ia->readBuffer("txtEntry");
            // Since we preallocate, we define EOF to be an
            // empty transaction
            if (bytes->length() == 0)
                return bytes;
            if (ia->readByte("EOF") != 'B') {
                LOG->error("Last transaction was partial.");
                return null;
            }
            return bytes;
        } catch (EEOFException& e){
        }
        return null;
    }

    /**
     * Serializes transaction header and transaction data into a byte buffer.
     *  
     * @param hdr transaction header
     * @param txn transaction data
     * @return serialized transaction record
     * @throws IOException
     */
    static sp<EA<byte> > marshallTxnEntry(TxnHeader* hdr, ERecord* txn)
            THROWS(EIOException) {
        EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
        sp<EOutputArchive> boa = EBinaryOutputArchive::getArchive(&baos);

        hdr->serialize(boa.get(), "hdr");
        if (txn != null) {
            txn->serialize(boa.get(), "txn");
        }
        //@see: return baos.toByteArray();
        return new EA<byte>(baos.data(), baos.size());
    }

    /**
     * Write the serialized transaction record to the output archive.
     *  
     * @param oa output archive
     * @param bytes serialized trasnaction record
     * @throws IOException
     */
    static void writeTxnBytes(EOutputArchive* oa, sp<EA<byte> > bytes)
    		THROWS(EIOException) {
        oa->writeBuffer(bytes.get(), "txnEntry");
        oa->writeByte((byte) 0x42, "EOR"); // 'B'
    }
    
    
    /**
     * Compare file file names of form "prefix.version". Sort order result
     * returned in order of version.
     */
    class DataDirFileComparator : public EComparator<EFile*> {
    private:
    	EString prefix_;
        boolean ascending_;

    public:
        DataDirFileComparator(EString prefix, boolean ascending) :
        	prefix_(prefix),
        	ascending_(ascending) {
        }

        virtual int compare(EFile* o1, EFile* o2) {
            llong z1 = getZxidFromName(o1->getName(), prefix_);
            llong z2 = getZxidFromName(o2->getName(), prefix_);
            int result = z1 < z2 ? -1 : (z1 > z2 ? 1 : 0);
            return ascending_ ? result : -result;
        }
    };
    
    /**
     * Sort the list of files. Recency as determined by the version component
     * of the file name.
     *
     * @param files array of files
     * @param prefix files not matching this prefix are assumed to have a
     * version = -1)
     * @param ascending true sorted in ascending order, false results in
     * descending order
     * @return sorted input files
     */
    /*
    static sp<EList<EFile*> > sortDataDir(EList<EFile*>* files, EString prefix, boolean ascending)
	{
		if (files==null)
			return null;
		DataDirFileComparator ddfc(prefix, ascending);

		sp<EList<EFile*> > clone = new EArray<EFile*>();
		auto iter = files->iterator();
		while (iter->hasNext()) {
			EFile* f = iter->next();
			clone->add(new EFile(f));
		}
		ECollections::sort(clone.get(), &ddfc);
		return clone;
	}
	*/
    static void sortDataDir(EList<EFile*>* files, EString prefix, boolean ascending)
    {
        if (files==null)
            return;
        DataDirFileComparator ddfc(prefix, ascending);
        ECollections::sort(files, &ddfc);
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Persistence_Util_HH_ */
