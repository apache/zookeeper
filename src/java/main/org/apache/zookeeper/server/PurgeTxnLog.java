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

package org.apache.zookeeper.server;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;

/**
 * this class is used to clean up the 
 * snapshot and data log dir's. This is usually
 * run as a cronjob on the zookeeper server machine.
 * Invocation of this class will clean up the datalogdir
 * files and snapdir files keeping the last "-n" snapshot files
 * and the corresponding logs.
 */
public class PurgeTxnLog {
    static void printUsage(){
        System.out.println("PurgeTxnLog dataLogDir [snapDir] -n count");
        System.out.println("\tdataLogDir -- path to the txn log directory");
        System.out.println("\tsnapDir -- path to the snapshot directory");
        System.out.println("\tcount -- the number of old snaps/logs you want to keep");
        System.exit(1);
    }

    private static final String PREFIX_SNAPSHOT = "snapshot";
    private static final String PREFIX_LOG = "log";

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
    public static void purge(File dataDir, File snapDir, int num) throws IOException {
        if (num < 3) {
            throw new IllegalArgumentException("count should be greater than 3");
        }

        FileTxnSnapLog txnLog = new FileTxnSnapLog(dataDir, snapDir);

        List<File> snaps = txnLog.findNRecentSnapshots(num);
        retainNRecentSnapshots(txnLog, snaps);
    }

    // VisibleForTesting
    static void retainNRecentSnapshots(FileTxnSnapLog txnLog, List<File> snaps) {
        // found any valid recent snapshots?
        if (snaps.size() == 0)
            return;
        File snapShot = snaps.get(snaps.size() -1);
        final long leastZxidToBeRetain = Util.getZxidFromName(
                snapShot.getName(), PREFIX_SNAPSHOT);

        class MyFileFilter implements FileFilter{
            private final String prefix;
            MyFileFilter(String prefix){
                this.prefix=prefix;
            }
            public boolean accept(File f){
                if(!f.getName().startsWith(prefix + "."))
                    return false;
                long fZxid = Util.getZxidFromName(f.getName(), prefix);
                if (fZxid >= leastZxidToBeRetain) {
                    return false;
                }
                return true;
            }
        }
        // add all non-excluded log files
        List<File> files = new ArrayList<File>(Arrays.asList(txnLog
                .getDataDir().listFiles(new MyFileFilter(PREFIX_LOG))));
        // add all non-excluded snapshot files to the deletion list
        files.addAll(Arrays.asList(txnLog.getSnapDir().listFiles(
                new MyFileFilter(PREFIX_SNAPSHOT))));
        // remove the old files
        for(File f: files)
        {
            System.out.println("Removing file: "+
                DateFormat.getDateTimeInstance().format(f.lastModified())+
                "\t"+f.getPath());
            if(!f.delete()){
                System.err.println("Failed to remove "+f.getPath());
            }
        }

    }
    
    /**
     * @param args PurgeTxnLog dataLogDir
     *     dataLogDir -- txn log directory
     *     -n num (number of snapshots to keep)
     */
    public static void main(String[] args) throws IOException {
        if(args.length<3 || args.length>4)
            printUsage();
        int i = 0;
        File dataDir=new File(args[0]);
        File snapDir=dataDir;
        if(args.length==4){
            i++;
            snapDir=new File(args[i]);
        }
        i++; i++;
        int num = Integer.parseInt(args[i]);
        purge(dataDir, snapDir, num);
    }
}
