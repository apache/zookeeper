/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.upgrade;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * This class upgrades the older database 
 * to a new database for the zookeeper 
 * servers.
 * The way to run it is 
 * java -class path zookeeper.jar Upgrade dataDir snapShotDir
 * or using zookeeper scripts with zkServer -upgrade dataDir snapShotDir 
 * it creates a backup in the dataDir/.bkup and snapShotDir/.bkup which 
 * can be retrieved back to the snapShotDir and dataDir 
 */
public class UpgradeMain {
    File snapShotDir;
    File dataDir;
    File bkupsnapShotDir;
    File bkupdataDir;
    File currentdataDir;
    File currentsnapShotDir;
    
    private static final Logger LOG = LoggerFactory.getLogger(UpgradeMain.class);
    private static final String USAGE = "Usage: UpgradeMain dataDir snapShotDir";
    private static final int LASTVERSION = 1;
    private static final int CURRENTVERSION = FileTxnSnapLog.VERSION;
    private static final String dirName = FileTxnSnapLog.version;
    private static final String manual = "Please take manual steps to " +
    		"sanitize your database.\n Please read the upgrade manual";
    
     /**
     * upgrade class that takes the two file 
     * directories.
     * @param dataDir the directory that contains the 
     * transaction logs
     * @param snapShotDir the directory that contains 
     * the snapshots 
     */
    public UpgradeMain(File dataDir, File snapShotDir) {
        this.snapShotDir = snapShotDir; 
        this.dataDir = dataDir;
        this.bkupdataDir = new File(dataDir, dirName + LASTVERSION);
        this.bkupsnapShotDir = new File(snapShotDir, dirName + LASTVERSION );
        this.currentsnapShotDir = new File(snapShotDir, dirName + CURRENTVERSION);
        this.currentdataDir = new File(dataDir, dirName + CURRENTVERSION);
    }
 
    /**
     * create all the bkup directories and the current
     * database directories
     * @throws IOException
     */
    private void createAllDirs() throws IOException {
        String error = "backup directory " + bkupdataDir + " already exists";
        LOG.info("Creating previous version data dir " + bkupdataDir);
        if (!bkupdataDir.mkdirs()) {
            LOG.error(error);
            LOG.error(manual);
            throw new IOException(error);
        }
        LOG.info("Creating previous version snapshot dir " + bkupdataDir);
        if (!bkupsnapShotDir.mkdirs() && !bkupsnapShotDir.exists()) {
            LOG.error(error);
            LOG.error(manual);
            throw new IOException(error);
        }
        error = "current directory " + currentdataDir + " already exists";
        LOG.info("Creating current data dir " + currentdataDir);
        if (!currentdataDir.mkdirs()) {
            LOG.error(error);
            LOG.error(manual);
            throw new IOException(error);
        }
        LOG.info("Creating current snapshot dir " + currentdataDir);
        if (!currentsnapShotDir.mkdirs() && !currentsnapShotDir.exists()) {
            LOG.error(error);
            LOG.error(manual);
            throw new IOException(error);
        }
    }
    
    /**
     * copy files from srcdir to dstdir that have the string 
     * filter in the srcdir filenames
     * @param srcDir the source directory
     * @param dstDir the destination directory
     * @param filter the filter of filenames that 
     * need to be copied.
     * @throws IOException
     */
    void copyFiles(File srcDir, File dstDir, String filter) throws IOException {
        File[] list = srcDir.listFiles();
        for (File file: list) {
            String name = file.getName();
            if (name.startsWith(filter)) {
                // we need to copy this file
                File dest = new File(dstDir, name);
                LOG.info("Renaming " + file + " to " + dest);
                if (!file.renameTo(dest)) {
                    throw new IOException("Unable to rename " 
                            + file + " to " +  dest);
                }
            }
        }
    }
    
    /**
     * run the upgrade
     * @throws IOException
     */
    public void runUpgrade() throws IOException {
        if (!dataDir.exists()) {
            throw new IOException(dataDir + " does not exist");
        }
        if (!snapShotDir.exists()) {
            throw new IOException(snapShotDir + " does not exist");
        }
        // create the bkup directorya
        createAllDirs();
        //copy all the files for backup
        try {
            copyFiles(dataDir, bkupdataDir, "log");
            copyFiles(snapShotDir, bkupsnapShotDir, "snapshot");
        } catch(IOException io) {
            LOG.error("Failed in backing up.");
            throw io;
        }

        //evrything is backed up
        // read old database and create 
        // an old snapshot
        UpgradeSnapShotV1 upgrade = new UpgradeSnapShotV1(bkupdataDir, 
                bkupsnapShotDir);
        LOG.info("Creating new data tree");
        DataTree dt = upgrade.getNewDataTree();
        FileTxnSnapLog filesnapLog = new FileTxnSnapLog(dataDir, 
                snapShotDir);
        LOG.info("snapshotting the new datatree");
        filesnapLog.save(dt, upgrade.getSessionWithTimeOuts());
        //done saving.
        LOG.info("Upgrade is complete");
    }
    
    public static void main(String[] argv) {
        if (argv.length < 2) {
            LOG.error(USAGE);
            System.exit(-1);
        }
        try {
            UpgradeMain upgrade = new UpgradeMain(new File(argv[0]), new File(argv[1]));
            upgrade.runUpgrade();
        } catch(Throwable th) {
            LOG.error("Upgrade Error: Please read the " +
            		"docs for manual failure recovery ", th);
        }
    }
}
