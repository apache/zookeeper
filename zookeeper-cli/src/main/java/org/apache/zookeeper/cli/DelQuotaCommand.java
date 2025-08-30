/*
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

import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
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
        super("delquota", "[-n|-b|-N|-B] path");

        OptionGroup og1 = new OptionGroup();
        og1.addOption(new Option("n", false, "num soft quota"));
        og1.addOption(new Option("b", false, "bytes soft quota"));
        og1.addOption(new Option("N", false, "num hard quota"));
        og1.addOption(new Option("B", false, "bytes hard quota"));

        options.addOptionGroup(og1);
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        DefaultParser parser = new DefaultParser();
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
        String path = args[1];
        // Use a StatsTrack object to pass in to delQuota which quotas
        // to delete by setting them to 1 as a flag.
        StatsTrack quota = new StatsTrack();
        if (cl.hasOption("n")) {
            quota.setCount(1);
        }
        if (cl.hasOption("b")) {
            quota.setBytes(1);
        }
        if (cl.hasOption("N")) {
            quota.setCountHardLimit(1);
        }
        if (cl.hasOption("B")) {
            quota.setByteHardLimit(1);
        }

        boolean flagSet = (cl.hasOption("n") || cl.hasOption("N")
                || cl.hasOption("b") || cl.hasOption("B"));
        try {
            delQuota(zk, path, flagSet ? quota : null);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException.NoNodeException ne) {
            err.println("quota for " + path + " does not exist.");
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;
    }

    /**
     * this method deletes quota for a node.
     *
     * @param zk the zookeeper client
     * @param path the path to delete quota for
     * @param quota the quotas to delete (set to 1), null to delete all
     * @return true if quota deletion is successful
     * @throws KeeperException
     * @throws MalformedPathException
     * @throws InterruptedException
     */
    public static boolean delQuota(ZooKeeper zk, String path, StatsTrack quota)
            throws KeeperException, InterruptedException, MalformedPathException {
        String parentPath = Quotas.quotaPath(path);
        String quotaPath = Quotas.limitPath(path);
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
            throw new KeeperException.NoNodeException(ne.getMessage());
        }
        StatsTrack strack = new StatsTrack(data);

        if (quota == null) {
            // delete till you can find a node with more than
            // one child
            List<String> children = zk.getChildren(parentPath, false);
            /// delete the direct children first
            for (String child : children) {
                zk.delete(parentPath + "/" + child, -1);
            }
            // cut the tree till their is more than one child
            trimProcQuotas(zk, parentPath);
        } else {
            if (quota.getCount() > 0) {
                strack.setCount(-1);
            }
            if (quota.getBytes() > 0) {
                strack.setBytes(-1L);
            }
            if (quota.getCountHardLimit() > 0) {
                strack.setCountHardLimit(-1);
            }
            if (quota.getByteHardLimit() > 0) {
                strack.setByteHardLimit(-1L);
            }

            zk.setData(quotaPath, strack.getStatsBytes(), -1);
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
     * @throws InterruptedException
     */
    private static boolean trimProcQuotas(ZooKeeper zk, String path)
            throws KeeperException, InterruptedException {
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
