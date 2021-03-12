package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;

import org.apache.commons.lang.NullArgumentException;
import org.apache.commons.lang.time.StopWatch;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.slf4j.Logger;

/**
 * Base class for the txnlog and snap back processes.
 * Provides the main backup loop and copying to remote storage (via HDFS APIs)
 */
public abstract class BackupProcess implements Runnable {
  protected final Logger logger;
  private BackupStorageProvider backupStorage;
  private final long backupIntervalInMilliseconds;
  protected volatile boolean isRunning = true;

  /**
   * Initialize starting backup point based on remote storage and backupStatus file
   */
  protected abstract void initialize() throws IOException;

  /**
   * Marks the start of a backup iteration.  A backup iteration is run every
   * backup.interval.  This is called at the start of the iteration and before
   * any calls to getNextFileToBackup
   * @throws IOException
   */
  protected abstract void startIteration() throws IOException;

  /**
   * Marks the end of a backup iteration.  After this call there will be no more
   * calls to getNextFileToBackup or backupComplete until startIteration is
   * called again.
   * @param errorFree whether the iteration was error free
   * @throws IOException
   */
  protected abstract void endIteration(boolean errorFree);

  /**
   * Get the next file to backup
   * @return the next file to copy to backup storage.
   * @throws IOException
   */
  protected abstract BackupManager.BackupFile getNextFileToBackup() throws IOException;

  /**
   * Marks that the copy of the specified file to backup storage has completed
   * @param file the file to backup
   * @throws IOException
   */
  protected abstract void backupComplete(BackupManager.BackupFile file) throws IOException;

  /**
   * Create an instance of the backup process
   * @param logger the logger to use for this process.
   */
  public BackupProcess(Logger logger, BackupStorageProvider backupStorage,
      long backupIntervalInMilliseconds) {
    if (logger == null) {
      throw new NullArgumentException("BackupProcess: logger is null!");
    }

    this.logger = logger;
    this.backupStorage = backupStorage;
    this.backupIntervalInMilliseconds = backupIntervalInMilliseconds;
  }

  /**
   * Runs the main file based backup loop indefinitely.
   */
  public void run() {
    run(0);
  }

  /**
   * Runs the main file based backup loop the specified number of time.
   * Calls methods implemented by derived classes to get the next file to copy.
   */
  public void run(int iterations) {
    try {
      boolean errorFree = true;
      logger.debug("Thread starting.");

      while (isRunning) {
        BackupManager.BackupFile fileToCopy;
        StopWatch sw = new StopWatch();

        sw.start();

        try {
          if (logger.isDebugEnabled()) {
            logger.debug("Starting iteration");
          }

          // Cleanup any invalid backups that may have been left behind by the
          // previous failed iteration.
          // NOTE: Not done on first iteration (errorFree initialized to true) since
          //       initialize already does this.
          if (!errorFree) {
            backupStorage.cleanupInvalidFiles(null);
          }

          startIteration();

          while ((fileToCopy = getNextFileToBackup()) != null) {
            // Consider: compress file before sending to remote storage
            copyToRemoteStorage(fileToCopy);
            backupComplete(fileToCopy);
            fileToCopy.cleanup();
          }

          errorFree = true;
        } catch (IOException e) {
          errorFree = false;
          logger.warn("Exception hit during backup", e);
        }

        endIteration(errorFree);

        sw.stop();
        long elapsedTime = sw.getTime();

        logger.info("Completed backup iteration in {} milliseconds.  ErrorFree: {}.",
            elapsedTime, errorFree);

        if (iterations != 0) {
          iterations--;

          if (iterations < 1) {
            break;
          }
        }

        // Count elapsed time towards the backup interval
        long waitTime = backupIntervalInMilliseconds - elapsedTime;

        synchronized (this) {  // synchronized to get notification of termination
          if (waitTime > 0) {
            wait(waitTime);
          }
        }
      }
    } catch (InterruptedException e) {
      logger.warn("Interrupted exception while waiting for backup interval.", e.fillInStackTrace());
    } catch (Exception e) {
      logger.error("Hit unexpected exception", e.fillInStackTrace());
    }

    logger.warn("BackupProcess thread exited loop!");
  }

  /**
   * Copy given file to remote storage via Backup Storage (HDFS, NFS, etc.) APIs.
   * @param fileToCopy the file to copy
   * @throws IOException
   */
  private void copyToRemoteStorage(BackupManager.BackupFile fileToCopy) throws IOException {
    if (fileToCopy.getFile() == null) {
      return;
    }

    // Use the file name to encode the max included zxid
    String backupName = BackupUtil.makeBackupName(
        fileToCopy.getFile().getName(), fileToCopy.getMaxZxid());

    backupStorage.copyToBackupStorage(fileToCopy.getFile(), new File(backupName));
  }

  /**
   * Shutdown the backup process
   */
  public void shutdown() {
    synchronized (this) {
      isRunning = false;
      notifyAll();
    }
  }
}