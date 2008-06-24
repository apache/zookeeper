/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PurgeTxnLog {

    static void printUsage(){
        System.out.println("PurgeTxnLog dataLogDir ");
        System.out.println("\tdataLogDir -- path to the txn log directory");
        System.exit(1);
    }
    /**
     * @param args PurgeTxnLog dataLogDir
     *     dataLogDir -- txn log directory
     */
    public static void main(String[] args) throws IOException {
        if(args.length!=1)
            printUsage();

        File dataDir=new File(args[0]);

        // find the most recent valid snapshot
        long highestZxid = -1;
        for (File f : dataDir.listFiles()) {
            long zxid = ZooKeeperServer.isValidSnapshot(f);
            if (zxid > highestZxid) {
                highestZxid = zxid;
            }
        }
        // found any valid snapshots?
        if(highestZxid==-1)
            return;  // no snapshots

        // files to exclude from deletion
        Set<File> exc=new HashSet<File>();
        exc.add(new File(dataDir, "snapshot."+Long.toHexString(highestZxid)));
        exc.addAll(Arrays.asList(ZooKeeperServer.getLogFiles(dataDir.listFiles(),highestZxid)));

        final Set<File> exclude=exc;
        List<File> files=Arrays.asList(dataDir.listFiles(new FileFilter(){
            public boolean accept(File f){
                if(!f.getName().startsWith("log.") &&
                        !f.getName().startsWith("snapshot."))
                    return false;
                if(exclude.contains(f))
                    return false;
                return true;
            }}));
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
}
