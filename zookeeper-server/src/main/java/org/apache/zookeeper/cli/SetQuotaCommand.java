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

import java.util.List;
import org.apache.commons.cli.*;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * setQuota command for cli
 */
public class SetQuotaCommand extends CliCommand {

    private static final Logger LOG = LoggerFactory.getLogger(SetQuotaCommand.class);
    private Options options = new Options();
    private String[] args;
    private CommandLine cl;

    public SetQuotaCommand() {
        super("setquota", "-n|-b|-r|-N|-B|-R val path");

        OptionGroup og1 = new OptionGroup();
        og1.addOption(new Option("n", false, "num soft quota"));
        og1.addOption(new Option("b", false, "bytes soft quota"));
        og1.addOption(new Option("r", false, "bytespersec rate soft quota"));
        og1.addOption(new Option("N", false, "num hard quota"));
        og1.addOption(new Option("B", false, "bytes hard quota"));
        og1.addOption(new Option("R", false, "bytespersec rate hard quota"));

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
    public boolean exec() throws CliException, KeeperException, InterruptedException {
        // get the args
        String path = args[1];

        StatsTrack quota = new StatsTrack(null);
        quota.setCount(-1);
        quota.setBytes(-1L);
        quota.setBytesPerSec(-1L);
        quota.setCountHardLimit(-1);
        quota.setByteHardLimit(-1L);
        quota.setBytesPerSecHardLimit(-1L);

        if (cl.hasOption("n")) {
            // we are setting the num quota
            int count = Integer.parseInt(cl.getOptionValue("n"));
            quota.setCount(count);
        }
        if (cl.hasOption("b")) {
            // we are setting the bytes quota
            long bytes = Long.parseLong(cl.getOptionValue("b"));
            quota.setBytes(bytes);
        }
        if (cl.hasOption("r")) {
            // we are setting the bytes-per-sec quota
            long bytesPerSec = Long.parseLong(cl.getOptionValue("r"));
            quota.setBytesPerSec(bytesPerSec);
        }
        if (cl.hasOption("N")) {
            // we are setting the num hard quota
            int count = Integer.parseInt(cl.getOptionValue("n"));
            quota.setCountHardLimit(count);
        }
        if (cl.hasOption("B")) {
            // we are setting the byte hard quota
            long bytes = Long.parseLong(cl.getOptionValue("B"));
            quota.setByteHardLimit(bytes);
        }
        if (cl.hasOption("R")) {
            // we are setting the bytes-per-sec hard quota
            long bytesPerSec = Long.parseLong(cl.getOptionValue("R"));
            quota.setBytesPerSecHardLimit(bytesPerSec);
        }
        boolean flagSet = (cl.hasOption("n") || cl.hasOption("N") ||
                cl.hasOption("b") || cl.hasOption("B") ||
                cl.hasOption("r") || cl.hasOption("R"));
        if (flagSet) {
            createQuota(zk, path, quota);
        } else {
            err.println(getUsageStr());
        }

        return false;
    }

    /**
     * this method creates a quota node for the path
     * @param zk the ZooKeeper client
     * @param path the path for which quota needs to be created
     * @param quota the quotas
     * @return true if its successful and false if not.
     */
    public static boolean createQuota(ZooKeeper zk, String path, StatsTrack quota)
            throws KeeperException, InterruptedException, MalformedPathException {
        // check if the path exists. We cannot create
        // quota for a path that doesn't exist in zookeeper
        // for now.
        Stat initStat;
        try {
            initStat = zk.exists(path, false);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        }
        if (initStat == null) {
            throw new IllegalArgumentException(path + " does not exist.");
        }
        // now check if their is already existing
        // parent or child that has quota

        String quotaPath = Quotas.quotaZookeeper;
        // check for more than 2 children --
        // if zookeeper_stats and zookeeper_quotas
        // are not the children then this path
        // is an ancestor of some path that
        // already has quota
        String realPath = Quotas.quotaZookeeper + path;
        try {
            List<String> children = zk.getChildren(realPath, false);
            for (String child : children) {
                if (!child.startsWith("zookeeper_")) {
                    throw new IllegalArgumentException(path + " has child "
                            + child + " which has a quota");
                }
            }
        } catch (KeeperException.NoNodeException ne) {
            // this is fine
        }

        //check for any parent that has been quota
        checkIfParentQuota(zk, path);

        // this is valid node for quota
        // start creating all the parents
        if (zk.exists(quotaPath, false) == null) {
            try {
                zk.create(Quotas.procZookeeper, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                zk.create(Quotas.quotaZookeeper, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            } catch (KeeperException.NodeExistsException ne) {
                // do nothing
            }
        }

        // now create the direct children
        // and the stat and quota nodes
        String[] splits = path.split("/");
        StringBuilder sb = new StringBuilder();
        sb.append(quotaPath);
        for (int i = 1; i < splits.length; i++) {
            sb.append("/").append(splits[i]);
            quotaPath = sb.toString();
            if (zk.exists(quotaPath, false) == null) {
                try {
                    zk.create(quotaPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                } catch (KeeperException.NodeExistsException ne) {
                    //do nothing
                }
            }
        }
        String statPath = quotaPath + "/" + Quotas.statNode;
        quotaPath = quotaPath + "/" + Quotas.limitNode;

        if (zk.exists(quotaPath, false) == null) {
            // throws a NodeExistsException back to user if race
            zk.create(quotaPath, quota.toString().getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            StatsTrack stats = new StatsTrack(null);

            stats.setCount(0);
            stats.setBytes(0L);
            stats.setBytesPerSecBytes(0L);
            zk.create(statPath, stats.toString().getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } else {
            byte[] data = zk.getData(quotaPath, false, new Stat());
            StatsTrack strackC = new StatsTrack(new String(data));
            if (quota.getCount() != -1) {
                strackC.setCount(quota.getCount());
            }
            if (quota.getBytes() != -1L) {
                strackC.setBytes(quota.getBytes());
            }
            if (quota.getBytesPerSec() != -1L) {
                strackC.setBytesPerSec(quota.getBytesPerSec());
            }
            if (quota.getCountHardLimit() != -1) {
                strackC.setCountHardLimit(quota.getCountHardLimit());
            }
            if (quota.getByteHardLimit() != -1L) {
                strackC.setByteHardLimit(quota.getByteHardLimit());
            }
            if (quota.getBytesPerSecHardLimit() != -1L) {
                strackC.setBytesPerSecHardLimit(quota.getBytesPerSecHardLimit());
            }
            zk.setData(quotaPath, strackC.toString().getBytes(), -1);
        }

        return true;
    }

    private static void checkIfParentQuota(ZooKeeper zk, String path)
            throws InterruptedException, KeeperException {
        final String[] splits = path.split("/");
        String quotaPath = Quotas.quotaZookeeper;

        StringBuilder sb = new StringBuilder();
        sb.append(quotaPath);
        for (int i = 1; i < splits.length - 1; i++) {
            sb.append("/");
            sb.append(splits[i]);
            quotaPath = sb.toString();
            List<String> children = null;
            try {
                children = zk.getChildren(quotaPath, false);
            } catch (KeeperException.NoNodeException ne) {
                LOG.debug("child removed during quota check", ne);
                return;
            }
            if (children.size() == 0) {
                return;
            }
            for (String child : children) {
                if (Quotas.limitNode.equals(child)) {
                    throw new IllegalArgumentException(path + " has a parent "
                            + quotaPath + " which has a quota");
                }
            }
        }
    }
}
