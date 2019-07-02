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

import java.io.IOException;
import java.util.List;
import org.apache.commons.cli.*;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**
 * delQuota command for cli
 */
public class DelQuotaCommand extends CliCommand {

    private Options options = new Options();
    private String[] args;
    private CommandLine cl;

    public DelQuotaCommand() {
        super("delquota", "[-n|-b] path");

        OptionGroup og1 = new OptionGroup();
        og1.addOption(new Option("b", false, "bytes quota"));
        og1.addOption(new Option("n", false, "num quota"));
        options.addOptionGroup(og1);
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        Parser parser = new PosixParser();
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        args = cl.getArgs();
        if (args.length < 2) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        //if neither option -n or -b is specified, we delete
        // the quota node for this node.
        String path = args[1];
        try {
            if (cl.hasOption("b")) {
                delQuota(zk, path, true, false);
            } else if (cl.hasOption("n")) {
                delQuota(zk, path, false, true);
            } else if (args.length == 2) {
                // we don't have an option specified.
                // just delete whole quota node
                delQuota(zk, path, true, true);
            }
        } catch (KeeperException|InterruptedException|IOException ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }

    /**
     * this method deletes quota for a node.
     *
     * @param zk the zookeeper client
     * @param path the path to delete quota for
     * @param bytes true if number of bytes needs to be unset
     * @param numNodes true if number of nodes needs to be unset
     * @return true if quota deletion is successful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public static boolean delQuota(ZooKeeper zk, String path,
            boolean bytes, boolean numNodes)
            throws KeeperException, IOException, InterruptedException, MalformedPathException {
        String parentPath = Quotas.quotaZookeeper + path;
        String quotaPath = Quotas.quotaZookeeper + path + "/" + 
                Quotas.limitNode;
        if (zk.exists(quotaPath, false) == null) {
            System.out.println("Quota does not exist for " + path);
            return true;
        }
        byte[] data = null;
        try {
            data = zk.getData(quotaPath, false, new Stat());
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException.NoNodeException ne) {
            System.err.println("quota does not exist for " + path);
            return true;
        }
        StatsTrack strack = new StatsTrack(new String(data));
        if (bytes && !numNodes) {
            strack.setBytes(-1L);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        } else if (!bytes && numNodes) {
            strack.setCount(-1);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        } else if (bytes && numNodes) {
            // delete till you can find a node with more than
            // one child
            List<String> children = zk.getChildren(parentPath, false);
            /// delete the direct children first
            for (String child : children) {
                zk.delete(parentPath + "/" + child, -1);
            }
            // cut the tree till their is more than one child
            trimProcQuotas(zk, parentPath);
        }
        return true;
    }

    /**
     * trim the quota tree to recover unwanted tree elements in the quota's tree
     *
     * @param zk the zookeeper client
     * @param path the path to start from and go up and see if their is any
     * unwanted parent in the path.
     * @return true if successful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    private static boolean trimProcQuotas(ZooKeeper zk, String path)
            throws KeeperException, IOException, InterruptedException {
        if (Quotas.quotaZookeeper.equals(path)) {
            return true;
        }
        List<String> children = zk.getChildren(path, false);
        if (children.size() == 0) {
            zk.delete(path, -1);
            String parent = path.substring(0, path.lastIndexOf('/'));
            return trimProcQuotas(zk, parent);
        } else {
            return true;
        }
    }
}
