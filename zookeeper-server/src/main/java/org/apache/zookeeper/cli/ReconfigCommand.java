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

import java.io.FileInputStream;
import java.util.Properties;

import org.apache.commons.cli.*;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

/**
 * reconfig command for cli
 */
public class ReconfigCommand extends CliCommand {

    private static Options options = new Options();

    /* joining - comma separated list of server config strings for servers to be added to the ensemble.
     * Each entry is identical in syntax as it would appear in a configuration file. Only used for 
     * incremental reconfigurations.
     */
    private String joining;

    /* leaving - comma separated list of server IDs to be removed from the ensemble. Only used for
     * incremental reconfigurations.
     */
    private String leaving;

    /* members - comma separated list of new membership information (e.g., contents of a membership
     * configuration file) - for use only with a non-incremental reconfiguration. This may be specified
     * manually via the -members flag or it will automatically be filled in by reading the contents
     * of an actual configuration file using the -file flag.
     */
    private String members;

    /* version - version of config from which we want to reconfigure - if current config is different
     * reconfiguration will fail. Should be committed from the CLI to disable this option.
     */
    long version = -1;
    private CommandLine cl;

    static {
        options.addOption("s", false, "stats");
        options.addOption("v", true, "required current config version");
        options.addOption("file", true, "path of config file to parse for membership");
        options.addOption("members", true, "comma-separated list of config strings for " +
        		"non-incremental reconfig");
        options.addOption("add", true, "comma-separated list of config strings for " +
        		"new servers");
        options.addOption("remove", true, "comma-separated list of server IDs to remove");
    }

    public ReconfigCommand() {
        super("reconfig", "[-s] " +
        		"[-v version] " +
        		"[[-file path] | " +
        		"[-members serverID=host:port1:port2;port3[,...]*]] | " +
        		"[-add serverId=host:port1:port2;port3[,...]]* " +
        		"[-remove serverId[,...]*]");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        joining = null;
        leaving = null;
        members = null;
        Parser parser = new PosixParser();
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        if (!(cl.hasOption("file") || cl.hasOption("members")) && !cl.hasOption("add") && !cl.hasOption("remove")) {
            throw new CliParseException(getUsageStr());
        }
        if (cl.hasOption("v")) {
            try{ 
                version = Long.parseLong(cl.getOptionValue("v"), 16);
            } catch (NumberFormatException e){
                throw new CliParseException("-v must be followed by a long (configuration version)");
            }
        } else {
            version = -1;
        }

        // Simple error checking for conflicting modes
        if ((cl.hasOption("file") || cl.hasOption("members")) && (cl.hasOption("add") || cl.hasOption("remove"))) {
            throw new CliParseException("Can't use -file or -members together with -add or -remove (mixing incremental" +
            		" and non-incremental modes is not allowed)");
        }
        if (cl.hasOption("file") && cl.hasOption("members")) {
            throw new CliParseException("Can't use -file and -members together (conflicting non-incremental modes)");
        }

        // Set the joining/leaving/members values based on the mode we're in
        if (cl.hasOption("add")) {
           joining = cl.getOptionValue("add").toLowerCase();
        }
        if (cl.hasOption("remove")) {
           leaving = cl.getOptionValue("remove").toLowerCase();
        }
        if (cl.hasOption("members")) {
           members = cl.getOptionValue("members").toLowerCase();
        }
        if (cl.hasOption("file")) {
            try {
                Properties dynamicCfg = new Properties();
                try (FileInputStream inConfig = new FileInputStream(cl.getOptionValue("file"))) {
                    dynamicCfg.load(inConfig);
                }
                //check that membership makes sense; leader will make these checks again
                //don't check for leader election ports since 
                //client doesn't know what leader election alg is used
                members = QuorumPeerConfig.parseDynamicConfig(dynamicCfg, 0, true, false).toString();
            } catch (Exception e) {
                throw new CliParseException("Error processing " + cl.getOptionValue("file") + e.getMessage());
            } 
        }
        return this;
    }

    @Override
    public boolean exec() throws CliException {
        try {
            Stat stat = new Stat();
            if (!(zk instanceof ZooKeeperAdmin)) {
                // This should never happen when executing reconfig command line,
                // because it is guaranteed that we have a ZooKeeperAdmin instance ready
                // to use in CliCommand stack.
                // The only exception would be in test code where clients can directly set
                // ZooKeeper object to ZooKeeperMain.
                return false;
            }

            byte[] curConfig = ((ZooKeeperAdmin)zk).reconfigure(joining,
                    leaving, members, version, stat);
            out.println("Committed new configuration:\n" + new String(curConfig));
            
            if (cl.hasOption("s")) {
                new StatPrinter(out).print(stat);
            }
        } catch (KeeperException|InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }
}
