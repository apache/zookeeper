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

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
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
        super("setquota", "-n|-b|-N|-B val path");

        OptionGroup og1 = new OptionGroup();
        og1.addOption(new Option("n", true, "num soft quota"));
        og1.addOption(new Option("b", true, "bytes soft quota"));
        og1.addOption(new Option("N", true, "num hard quota"));
        og1.addOption(new Option("B", true, "bytes hard quota"));

        og1.setRequired(true);
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
        // get the args
        String path = args[1];
        if (path.startsWith(Quotas.quotaZookeeper)) {
            err.println("cannot set a quota under the path: " + Quotas.quotaZookeeper);
            return false;
        }

        StatsTrack quota = new StatsTrack();
        quota.setCount(-1);
        quota.setBytes(-1L);
        quota.setCountHardLimit(-1);
        quota.setByteHardLimit(-1L);

        if (!checkOptionValue(quota)) {
            return false;
        }

        boolean flagSet = (cl.hasOption("n") || cl.hasOption("N")
                || cl.hasOption("b") || cl.hasOption("B"));
        if (flagSet) {
            try {
                createQuota(zk, path, quota);
            } catch (IllegalArgumentException ex) {
                throw new MalformedPathException(ex.getMessage());
            } catch (KeeperException | InterruptedException ex) {
                throw new CliWrapperException(ex);
            }
        } else {
            err.println(getUsageStr());
        }

        return false;
    }

    private boolean checkOptionValue(StatsTrack quota) {

        try {
            if (cl.hasOption("n")) {
                // we are setting the num quota
                int count = Integer.parseInt(cl.getOptionValue("n"));
                if (count > 0) {
                    quota.setCount(count);
                } else {
                    err.println("the num quota must be greater than zero");
                    return false;
                }
            }
            if (cl.hasOption("b")) {
                // we are setting the bytes quota
                long bytes = Long.parseLong(cl.getOptionValue("b"));
                if (bytes >= 0) {
                    quota.setBytes(bytes);
                } else {
                    err.println("the bytes quota must be greater than or equal to zero");
                    return false;
                }
            }
            if (cl.hasOption("N")) {
                // we are setting the num hard quota
                int count = Integer.parseInt(cl.getOptionValue("N"));
                if (count > 0) {
                    quota.setCountHardLimit(count);
                } else {
                    err.println("the num quota must be greater than zero");
                    return false;
                }
            }
            if (cl.hasOption("B")) {
                // we are setting the byte hard quota
                long bytes = Long.parseLong(cl.getOptionValue("B"));
                if (bytes >= 0) {
                    quota.setByteHardLimit(bytes);
                } else {
                    err.println("the bytes quota must be greater than or equal to zero");
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            err.println("NumberFormatException happens when parsing the option value");
            return false;
        }

        return true;
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

        //check if the child node has a quota.
        checkIfChildQuota(zk, path);

        //check for any parent that has been quota
        checkIfParentQuota(zk, path);

        // this is valid node for quota
        // start creating all the parents
        if (zk.exists(quotaPath, false) == null) {
            try {
                zk.create(Quotas.procZookeeper, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.create(Quotas.quotaZookeeper, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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
        byte[] data;

        if (zk.exists(quotaPath, false) == null) {
            zk.create(quotaPath, quota.getStatsBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            StatsTrack stats = new StatsTrack();
            stats.setCount(0);
            stats.setBytes(0L);

            zk.create(statPath, stats.getStatsBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            data = zk.getData(quotaPath, false, new Stat());
            StatsTrack quotaStrack = new StatsTrack(data);

            data = zk.getData(statPath, false, new Stat());
            StatsTrack statStrack = new StatsTrack(data);
            checkQuota(quotaStrack, statStrack);

        } else {
            data = zk.getData(quotaPath, false, new Stat());
            StatsTrack quotaStrack = new StatsTrack(data);

            if (quota.getCount() > -1) {
                quotaStrack.setCount(quota.getCount());
            }
            if (quota.getBytes() > -1L) {
                quotaStrack.setBytes(quota.getBytes());
            }
            if (quota.getCountHardLimit() > -1) {
                quotaStrack.setCountHardLimit(quota.getCountHardLimit());
            }
            if (quota.getByteHardLimit() > -1L) {
                quotaStrack.setByteHardLimit(quota.getByteHardLimit());
            }

            data = zk.getData(statPath, false, new Stat());
            StatsTrack statStrack = new StatsTrack(data);
            checkQuota(quotaStrack, statStrack);

            zk.setData(quotaPath, quotaStrack.getStatsBytes(), -1);
        }

        return true;
    }

    private static void checkQuota(StatsTrack quotaStrack, StatsTrack statStrack) {
        if ((quotaStrack.getCount() > -1 && quotaStrack.getCount() < statStrack.getCount()) || (quotaStrack.getCountHardLimit() > -1
                && quotaStrack.getCountHardLimit() < statStrack.getCount())) {
            System.out.println("[Warning]: the count quota you create is less than the existing count:" + statStrack.getCount());
        }
        if ((quotaStrack.getBytes() > -1 && quotaStrack.getBytes() < statStrack.getBytes()) || (quotaStrack.getByteHardLimit() > -1
                && quotaStrack.getByteHardLimit() < statStrack.getBytes())) {
            System.out.println("[Warning]: the bytes quota you create is less than the existing bytes:" + statStrack.getBytes());
        }
    }

    private static void checkIfChildQuota(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        String realPath = Quotas.quotaPath(path);

        try {
            ZKUtil.visitSubTreeDFS(zk, realPath, false, (rc, quotaPath, ctx, name) -> {
                List<String> children = new ArrayList<>();
                try {
                    children = zk.getChildren(quotaPath, false);
                } catch (KeeperException.NoNodeException ne) {
                    LOG.debug("child removed during quota check", ne);
                    return;
                } catch (InterruptedException | KeeperException e) {
                    e.printStackTrace();
                }

                if (children.size() == 0) {
                    return;
                }
                for (String child : children) {
                    if (!quotaPath.equals(Quotas.quotaZookeeper + path) && Quotas.limitNode.equals(child)) {
                        throw new IllegalArgumentException(path + " has a child " + Quotas.trimQuotaPath(quotaPath) + " which has a quota");
                    }
                }
            });
        } catch (KeeperException.NoNodeException ne) {
            // this is fine
        }
    }

    private static void checkIfParentQuota(ZooKeeper zk, String path) throws InterruptedException, KeeperException {
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
                if (!quotaPath.equals(Quotas.quotaPath(path)) && Quotas.limitNode.equals(child)) {
                    throw new IllegalArgumentException(path + " has a parent " + Quotas.trimQuotaPath(quotaPath) + " which has a quota");
                }
            }
        }
    }
}
