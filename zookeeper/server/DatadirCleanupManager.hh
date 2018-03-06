/*
 * DatadirCleanupManager.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef DatadirCleanupManager_HH_
#define DatadirCleanupManager_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./PurgeTxnLog.hh"

namespace efc {
namespace ezk {

/**
 * This class manages the cleanup of snapshots and corresponding transaction
 * logs by scheduling the auto purge task with the specified
 * 'autopurge.purgeInterval'. It keeps the most recent
 * 'autopurge.snapRetainCount' number of snapshots and corresponding transaction
 * logs.
 */
class DatadirCleanupManager : public EObject {
public:

    /**
     * Status of the dataDir purge task
     */
    enum PurgeTaskStatus {
        NOT_STARTED, STARTED, COMPLETED
    };

    /**
     * Constructor of DatadirCleanupManager. It takes the parameters to schedule
     * the purge task.
     * 
     * @param snapDir
     *            snapshot directory
     * @param dataLogDir
     *            transaction log directory
     * @param snapRetainCount
     *            number of snapshots to be retained after purge
     * @param purgeInterval
     *            purge interval in hours
     */
    DatadirCleanupManager(EString snapDir, EString dataLogDir, int snapRetainCount,
            int purgeInterval) {
        this->snapDir = snapDir;
        this->dataLogDir = dataLogDir;
        this->snapRetainCount = snapRetainCount;
        this->purgeInterval = purgeInterval;
        this->purgeTaskStatus = PurgeTaskStatus::NOT_STARTED;
        LOG->info(EString("autopurge.snapRetainCount set to ") + snapRetainCount);
        LOG->info(EString("autopurge.purgeInterval set to ") + purgeInterval);
    }

    /**
     * Validates the purge configuration and schedules the purge task. Purge
     * task keeps the most recent <code>snapRetainCount</code> number of
     * snapshots and deletes the remaining for every <code>purgeInterval</code>
     * hour(s).
     * <p>
     * <code>purgeInterval</code> of <code>0</code> or
     * <code>negative integer</code> will not schedule the purge task.
     * </p>
     * 
     * @see PurgeTxnLog#purge(File, File, int)
     */
    void start() {
        if (PurgeTaskStatus::STARTED == purgeTaskStatus) {
            LOG->warn("Purge task is already running.");
            return;
        }
        // Don't schedule the purge task with zero or negative purge interval.
        if (purgeInterval <= 0) {
            LOG->info("Purge task is not scheduled.");
            return;
        }

        timer = new ETimer("PurgeTask");
        timer->scheduleAtFixedRate(new PurgeTask(dataLogDir, snapDir, snapRetainCount),
        		(llong)0L, ETimeUnit::HOURS->toMillis(purgeInterval));

        purgeTaskStatus = PurgeTaskStatus::STARTED;
    }

    /**
     * Shutdown the purge task.
     */
    void shutdown() {
        if (PurgeTaskStatus::STARTED == purgeTaskStatus) {
            LOG->info("Shutting down purge task.");
            timer->cancel();
            purgeTaskStatus = PurgeTaskStatus::COMPLETED;
        } else {
            LOG->warn("Purge task not started. Ignoring shutdown!");
        }
    }

    /**
     * Returns the status of the purge task.
     * 
     * @return the status of the purge task
     */
    PurgeTaskStatus getPurgeTaskStatus() {
        return purgeTaskStatus;
    }

    /**
     * Returns the snapshot directory.
     * 
     * @return the snapshot directory.
     */
    EString getSnapDir() {
        return snapDir;
    }

    /**
     * Returns transaction log directory.
     * 
     * @return the transaction log directory.
     */
    EString getDataLogDir() {
        return dataLogDir;
    }

    /**
     * Returns purge interval in hours.
     * 
     * @return the purge interval in hours.
     */
    int getPurgeInterval() {
        return purgeInterval;
    }

    /**
     * Returns the number of snapshots to be retained after purge.
     * 
     * @return the number of snapshots to be retained after purge.
     */
    int getSnapRetainCount() {
        return snapRetainCount;
    }

private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(DatadirCleanupManager.class);

    PurgeTaskStatus purgeTaskStatus;// = PurgeTaskStatus.NOT_STARTED;

    EString snapDir;

    EString dataLogDir;

    int snapRetainCount;

    int purgeInterval;

    sp<ETimer> timer;

    class PurgeTask : public ETimerTask {
    private:
    	EString logsDir;
        EString snapsDir;
        int snapRetainCount;

    public:
        PurgeTask(EString dataDir, EString snapDir, int count) {
            this->logsDir = dataDir;
            this->snapsDir = snapDir;
            this->snapRetainCount = count;
        }

        virtual void run() {
        	DatadirCleanupManager::LOG->info("Purge task started.");
            try {
            	EFile ld(logsDir.c_str());
            	EFile sd(snapsDir.c_str());
                PurgeTxnLog::purge(&ld, &sd, snapRetainCount);
            } catch (EException& e) {
            	DatadirCleanupManager::LOG->error("Error occurred while purging.", e);
            }
            DatadirCleanupManager::LOG->info("Purge task completed.");
        }
    };
};

} /* namespace ezk */
} /* namespace efc */
#endif /* DatadirCleanupManager_HH_ */
