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
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
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
        DefaultParser parser = new DefaultParser();
        CommandLine cl;
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
        String absolutePath = Quotas.limitPath(path);
        try {
            err.println("absolute path is " + absolutePath);
            List<StatsTrack> statsTracks = listQuota(zk, path);
            for (int i = 0; i < statsTracks.size(); i++) {
                StatsTrack st = statsTracks.get(i);
                if (i == 0) {
                    out.println("Output quota for " + path + " " + st.toString());
                } else {
                    out.println("Output stat for " + path + " " + st.toString());
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException.NoNodeException ne) {
            err.println("quota for " + path + " does not exist.");
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;
    }

    // @VisibleForTesting
    public static List<StatsTrack> listQuota(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        List<StatsTrack> statsTracks = new ArrayList<>();
        Stat stat = new Stat();
        byte[] data = zk.getData(Quotas.limitPath(path), false, stat);
        StatsTrack st = new StatsTrack(data);
        statsTracks.add(st);

        data = zk.getData(Quotas.statPath(path), false, stat);
        st = new StatsTrack(data);
        statsTracks.add(st);

        return statsTracks;
    }

}
