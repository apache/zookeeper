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
package org.apache.zookeeper.cli;

import org.apache.commons.cli.*;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**
 * listQuota command for cli
 */
public class ListQuotaCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    
    public ListQuotaCommand() {
        super("listquota", "path");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        Parser parser = new PosixParser();
        CommandLine cl;
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        args = cl.getArgs();
        if(args.length < 2) {
            throw new CliParseException(getUsageStr());
        }
        
        return this;
    }

    @Override
    public boolean exec() throws CliException {
        String path = args[1];
        try {
            listQuota(zk, path);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException.NoNodeException ne) {
            err.println("quota for " + path + " does not exist.");
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;
    }

    // VisibleForTesting
    public boolean listQuota(ZooKeeper zk, String path) throws KeeperException, InterruptedException {

        if (zk.exists(path, null) == null) {
            err.println("the path: " + path + " does not exist.");
            if (zk.exists(Quotas.quotaZookeeper + path, null) != null) {
                try {
                    ZKUtil.deleteRecursive(zk, Quotas.quotaZookeeper + path, 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (KeeperException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }

        String absolutePath = Quotas.quotaZookeeper + path + "/"
                + Quotas.limitNode;
        Stat stat = new Stat();
        byte[] data = zk.getData(absolutePath, false, stat);
        StatsTrack st = new StatsTrack(new String(data));
        out.println("Output quota for " + path + " "
                + st.toString());

        data = zk.getData(Quotas.quotaZookeeper + path + "/"
                + Quotas.statNode, false, stat);
        out.println("Output stat for " + path + " "
                + new StatsTrack(new String(data)).toString());

        return false;
    }
}
