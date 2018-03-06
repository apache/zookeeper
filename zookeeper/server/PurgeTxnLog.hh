/*
 * PurgeTxnLog.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef PurgeTxnLog_HH_
#define PurgeTxnLog_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./persistence/FileTxnSnapLog.hh"
#include "./persistence/Util.hh"

namespace efc {
namespace ezk {

/**
 * this class is used to clean up the 
 * snapshot and data log dir's. This is usually
 * run as a cronjob on the zookeeper server machine.
 * Invocation of this class will clean up the datalogdir
 * files and snapdir files keeping the last "-n" snapshot files
 * and the corresponding logs.
 */
class PurgeTxnLog {
private:
    static sp<ELogger> LOG;// = LoggerFactory.getLogger(PurgeTxnLog.class);

    constexpr static const char* COUNT_ERR_MSG = "count should be greater than or equal to 3";

    constexpr static const char* PREFIX_SNAPSHOT = "snapshot";
    constexpr static const char* PREFIX_LOG = "log";

public:
    /**
     * Purges the snapshot and logs keeping the last num snapshots and the
     * corresponding logs. If logs are rolling or a new snapshot is created
     * during this process, these newest N snapshots or any data logs will be
     * excluded from current purging cycle.
     *
     * @param dataDir the dir that has the logs
     * @param snapDir the dir that has the snapshots
     * @param num the number of snapshots to keep
     * @throws IOException
     */
    static void purge(EFile* dataDir, EFile* snapDir, int num) THROWS(EIOException) {
        if (num < 3) {
            throw EIllegalArgumentException(__FILE__, __LINE__, COUNT_ERR_MSG);
        }

        FileTxnSnapLog txnLog(dataDir, snapDir);

        sp<EList<EFile*> > snaps = txnLog.findNRecentSnapshots(num);
        int numSnaps = snaps->size();
        if (numSnaps > 0) {
            purgeOlderSnapshots(txnLog, snaps->getAt(numSnaps - 1));
        }
    }

    // VisibleForTesting
    static void purgeOlderSnapshots(FileTxnSnapLog& txnLog, EFile* snapShot) {
        llong leastZxidToBeRetain = Util::getZxidFromName(snapShot->getName(), PREFIX_SNAPSHOT);

        /**
         * We delete all files with a zxid in their name that is less than leastZxidToBeRetain.
         * This rule applies to both snapshot files as well as log files, with the following
         * exception for log files.
         *
         * A log file with zxid less than X may contain transactions with zxid larger than X.  More
         * precisely, a log file named log.(X-a) may contain transactions newer than snapshot.X if
         * there are no other log files with starting zxid in the interval (X-a, X].  Assuming the
         * latter condition is true, log.(X-a) must be retained to ensure that snapshot.X is
         * recoverable.  In fact, this log file may very well extend beyond snapshot.X to newer
         * snapshot files if these newer snapshots were not accompanied by log rollover (possible in
         * the learner state machine at the time of this writing).  We can make more precise
         * determination of whether log.(leastZxidToBeRetain-a) for the smallest 'a' is actually
         * needed or not (e.g. not needed if there's a log file named log.(leastZxidToBeRetain+1)),
         * but the complexity quickly adds up with gains only in uncommon scenarios.  It's safe and
         * simple to just preserve log.(leastZxidToBeRetain-a) for the smallest 'a' to ensure
         * recoverability of all snapshots being retained.  We determine that log file here by
         * calling txnLog.getSnapshotLogs().
         */
        /* @see:
        final Set<File> retainedTxnLogs = new HashSet<File>();
        retainedTxnLogs.addAll(Arrays.asList(txnLog.getSnapshotLogs(leastZxidToBeRetain)));
		*/
        sp<EArray<EFile*> > snapshotLogs =  txnLog.getSnapshotLogs(leastZxidToBeRetain);
		EHashSet<sp<EFile> > retainedTxnLogs;
		for (int i=0; i<snapshotLogs->size(); i++) {
			retainedTxnLogs.add(new EFile(snapshotLogs->getAt(i)));
		}

        /**
         * Finds all candidates for deletion, which are files with a zxid in their name that is less
         * than leastZxidToBeRetain.  There's an exception to this rule, as noted above.
         */
        class MyFileFilter : public EFileFilter{
        private:
        	EString prefix;
        	EHashSet<sp<EFile> >& retainedTxnLogs;
        	llong leastZxidToBeRetain;
        public:
            MyFileFilter(EString prefix, EHashSet<sp<EFile> >& files, llong zxid) :
            	prefix(prefix), retainedTxnLogs(files), leastZxidToBeRetain(zxid) {
            }
            virtual boolean accept(EFile* f){
                if(!f->getName().startsWith((prefix + ".").c_str()))
                    return false;
                if (retainedTxnLogs.contains(f)) {
                    return false;
                }
                llong fZxid = Util::getZxidFromName(f->getName(), prefix);
                if (fZxid >= leastZxidToBeRetain) {
                    return false;
                }
                return true;
            }
        };
        EArray<EFile*> files;
        // add all non-excluded log files
        MyFileFilter filter1(PREFIX_LOG, retainedTxnLogs, leastZxidToBeRetain);
        txnLog.getDataDir()->listFiles(&files, &filter1);

        // add all non-excluded snapshot files to the deletion list
        MyFileFilter filter2(PREFIX_SNAPSHOT, retainedTxnLogs, leastZxidToBeRetain);
		txnLog.getSnapDir()->listFiles(&files, &filter2);

        // remove the old files
        for (int i=0; i<files.size(); i++) {
        	EFile* f = files.getAt(i);
        	ECalendar cal(f->lastModified());
            EString msg = "Removing file: " + cal.toString() + "\t" + f->getPath();
            LOG->info(msg.c_str());
            ESystem::out->println(msg.c_str());
            if(!f->remove()){
            	ESystem::err->println((EString("Failed to remove ") + f->getPath()).c_str());
            }
        }
    }

#if 0
    /**
     * @param args dataLogDir [snapDir] -n count
     * dataLogDir -- path to the txn log directory
     * snapDir -- path to the snapshot directory
     * count -- the number of old snaps/logs you want to keep, value should be greater than or equal to 3<br>
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            printUsageThenExit();
        }
        File dataDir = validateAndGetFile(args[0]);
        File snapDir = dataDir;
        int num = -1;
        String countOption = "";
        if (args.length == 3) {
            countOption = args[1];
            num = validateAndGetCount(args[2]);
        } else {
            snapDir = validateAndGetFile(args[1]);
            countOption = args[2];
            num = validateAndGetCount(args[3]);
        }
        if (!"-n".equals(countOption)) {
            printUsageThenExit();
        }
        purge(dataDir, snapDir, num);
    }

    /**
     * validates file existence and returns the file
     *
     * @param path
     * @return File
     */
    private static File validateAndGetFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.err.println("Path '" + file.getAbsolutePath()
                    + "' does not exist. ");
            printUsageThenExit();
        }
        return file;
    }

    /**
     * Returns integer if parsed successfully and it is valid otherwise prints
     * error and usage and then exits
     *
     * @param number
     * @return count
     */
    private static int validateAndGetCount(String number) {
        int result = 0;
        try {
            result = Integer.parseInt(number);
            if (result < 3) {
                System.err.println(COUNT_ERR_MSG);
                printUsageThenExit();
            }
        } catch (NumberFormatException e) {
            System.err
                    .println("'" + number + "' can not be parsed to integer.");
            printUsageThenExit();
        }
        return result;
    }

    private static void printUsageThenExit() {
        printUsage();
        System.exit(1);
    }
#endif
};

} /* namespace ezk */
} /* namespace efc */
#endif /* PurgeTxnLog_HH_ */
