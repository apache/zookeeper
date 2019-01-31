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
        super("setquota", "-n|-b val path");
        
        OptionGroup og1 = new OptionGroup();
        og1.addOption(new Option("b", true, "bytes quota"));
        og1.addOption(new Option("n", true, "num quota"));
        og1.setRequired(true);
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
        // get the args
        String path = args[1];

        if (cl.hasOption("b")) {
            // we are setting the bytes quota
            long bytes = Long.parseLong(cl.getOptionValue("b"));
            try {
                createQuota(zk, path, bytes, -1);
            } catch (KeeperException|IOException|InterruptedException ex) {
                throw new CliWrapperException(ex);
            }
        } else if (cl.hasOption("n")) {
            // we are setting the num quota
            int numNodes = Integer.parseInt(cl.getOptionValue("n"));
            try {
                createQuota(zk, path, -1L, numNodes);
            } catch (KeeperException|IOException|InterruptedException ex) {
                throw new CliWrapperException(ex);
            }
        } else {
            throw new MalformedCommandException(getUsageStr());
        }

        return false;
    }

    public static boolean createQuota(ZooKeeper zk, String path,
            long bytes, int numNodes)
            throws KeeperException, IOException, InterruptedException, MalformedPathException {
        // check if the path exists. We cannot create
        // quota for a path that already exists in zookeeper
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
            try {
                zk.create(quotaPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            } catch (KeeperException.NodeExistsException ne) {
                //do nothing
            }
        }
        String statPath = quotaPath + "/" + Quotas.statNode;
        quotaPath = quotaPath + "/" + Quotas.limitNode;
        StatsTrack strack = new StatsTrack(null);
        strack.setBytes(bytes);
        strack.setCount(numNodes);
        try {
            zk.create(quotaPath, strack.toString().getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            StatsTrack stats = new StatsTrack(null);
            stats.setBytes(0L);
            stats.setCount(0);
            zk.create(statPath, stats.toString().getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ne) {
            byte[] data = zk.getData(quotaPath, false, new Stat());
            StatsTrack strackC = new StatsTrack(new String(data));
            if (bytes != -1L) {
                strackC.setBytes(bytes);
            }
            if (numNodes != -1) {
                strackC.setCount(numNodes);
            }
            zk.setData(quotaPath, strackC.toString().getBytes(), -1);
        }
        return true;
    }

    private static void checkIfParentQuota(ZooKeeper zk, String path)
            throws InterruptedException, KeeperException {
        final String[] splits = path.split("/");
        String quotaPath = Quotas.quotaZookeeper;
        for (String str : splits) {
            if (str.length() == 0) {
                // this should only be for the beginning of the path
                // i.e. "/..." - split(path)[0] is empty string before first '/'
                continue;
            }
            quotaPath += "/" + str;
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
