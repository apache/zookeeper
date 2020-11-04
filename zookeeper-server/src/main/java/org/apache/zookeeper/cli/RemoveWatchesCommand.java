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
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher.WatcherType;

/**
 * Remove watches command for cli
 */
public class RemoveWatchesCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption("c", false, "child watcher type");
        options.addOption("d", false, "data watcher type");
        options.addOption("a", false, "any watcher type");
        options.addOption("l", false, "remove locally when there is no server connection");
    }

    public RemoveWatchesCommand() {
        super("removewatches", "path [-c|-d|-a] [-l]");
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
    public boolean exec() throws CliWrapperException, MalformedPathException {
        String path = args[1];
        WatcherType wtype = WatcherType.Any;
        // if no matching option -c or -d or -a is specified, we remove
        // the watches of the given node by choosing WatcherType.Any
        if (cl.hasOption("c")) {
            wtype = WatcherType.Children;
        } else if (cl.hasOption("d")) {
            wtype = WatcherType.Data;
        } else if (cl.hasOption("a")) {
            wtype = WatcherType.Any;
        }
        // whether to remove the watches locally
        boolean local = cl.hasOption("l");

        try {
            zk.removeAllWatches(path, wtype, local);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return true;
    }

}
