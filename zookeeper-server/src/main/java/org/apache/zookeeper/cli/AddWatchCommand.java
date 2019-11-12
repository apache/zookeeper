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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.KeeperException;

/**
 * addWatch command for cli.
 * Matches the ZooKeeper API addWatch()
 */
public class AddWatchCommand extends CliCommand {

    private static final Options options = new Options();
    private CommandLine cl;
    private AddWatchMode mode;

    static {
        options.addOption(new Option("d", false, "data change watch"));
        options.addOption(new Option("c", false, "child change watch"));
        options.addOption(new Option("e", false, "exist watch"));
        options.addOption(new Option("p", false, "persistent watch"));
        options.addOption(new Option("R", false, "persistent recursive watch"));
    }

    public AddWatchCommand() {
        super("addWatch", "[-d] [-c] [-e] [-p] [-R] path");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        Parser parser = new PosixParser();
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        if (cl.getArgs().length != 2 || cl.getOptions().length != 1) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        String path = cl.getArgs()[1];
        if (cl.hasOption("d")) {
            mode = AddWatchMode.STANDARD_DATA;
        }
        if (cl.hasOption("c")) {
            mode = AddWatchMode.STANDARD_CHILD;
        }
        if (cl.hasOption("e")) {
            mode = AddWatchMode.STANDARD_EXIST;
        }
        if (cl.hasOption("p")) {
            mode = AddWatchMode.PERSISTENT;
        }
        if (cl.hasOption("R")) {
            mode = AddWatchMode.PERSISTENT_RECURSIVE;
        }

        try {
            zk.addWatch(path, mode);
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;

    }

}
