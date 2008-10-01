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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;

public class PurgeTxnLog {

    static void printUsage(){
        System.out.println("PurgeTxnLog dataLogDir [snapDir]");
        System.out.println("\tdataLogDir -- path to the txn log directory");
        System.out.println("\tsnapDir -- path to the snapshot directory");
        System.exit(1);
    }
    /**
     * @param args PurgeTxnLog dataLogDir
     *     dataLogDir -- txn log directory
     */
    public static void main(String[] args) throws IOException {
        if(args.length<1 || args.length>2)
            printUsage();

        File dataDir=new File(args[0]);
        File snapDir=dataDir;
        if(args.length==2){
            snapDir=new File(args[1]);
            }
        FileTxnSnapLog txnLog = new FileTxnSnapLog(dataDir, snapDir);
        
        // found any valid recent snapshots?
        
        // files to exclude from deletion
        Set<File> exc=new HashSet<File>();
        File snapShot = txnLog.findMostRecentSnapshot();
        exc.add(txnLog.findMostRecentSnapshot());
        long zxid = Util.getZxidFromName(snapShot.getName(),"snapshot");
        exc.addAll(Arrays.asList(txnLog.getSnapshotLogs(zxid)));

        final Set<File> exclude=exc;
        class MyFileFilter implements FileFilter{
            private final String prefix;
            MyFileFilter(String prefix){
                this.prefix=prefix;
            }
            public boolean accept(File f){
                if(!f.getName().startsWith(prefix) || exclude.contains(f))
                    return false;
                return true;
            }
        }
        // add all non-excluded log files
        List<File> files=new ArrayList<File>(
                Arrays.asList(dataDir.listFiles(new MyFileFilter("log."))));
        // add all non-excluded snapshot files to the deletion list
        files.addAll(Arrays.asList(snapDir.listFiles(new MyFileFilter("snapshot."))));
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
