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

import java.util.Arrays;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.KeeperException;

/**
 * addWatch command for cli.
 * Matches the ZooKeeper API addWatch()
 */
public class AddWatchCommand extends CliCommand {

    private static final Options options = new Options();
    private static final AddWatchMode defaultMode = AddWatchMode.PERSISTENT_RECURSIVE;

    private CommandLine cl;
    private AddWatchMode mode = defaultMode;

    static {
        options.addOption("m", true, "");
    }

    public AddWatchCommand() {
        super("addWatch", "[-m mode] path # optional mode is one of "
                + Arrays.toString(AddWatchMode.values()) + " - default is " + defaultMode.name());
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        DefaultParser parser = new DefaultParser();
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        if (cl.getArgs().length != 2) {
            throw new CliParseException(getUsageStr());
        }

        if (cl.hasOption("m")) {
            try {
                mode = AddWatchMode.valueOf(cl.getOptionValue("m").toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new CliParseException(getUsageStr());
            }
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        String path = cl.getArgs()[1];
        try {
            zk.addWatch(path, mode);
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;

    }

}
