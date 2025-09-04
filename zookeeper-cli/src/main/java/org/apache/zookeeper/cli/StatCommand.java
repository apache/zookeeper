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
import org.apache.zookeeper.data.Stat;

/**
 * stat command for cli
 */
public class StatCommand extends CliCommand {

    private static final Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption("w", false, "watch");
    }

    public StatCommand() {
        super("stat", "[-w] path");
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

        retainCompatibility(cmdArgs);

        return this;
    }

    private void retainCompatibility(String[] cmdArgs) throws CliParseException {
        // stat path [watch]
        if (args.length > 2) {
            // rewrite to option
            cmdArgs[2] = "-w";
            err.println("'stat path [watch]' has been deprecated. " + "Please use 'stat [-w] path' instead.");
            DefaultParser parser = new DefaultParser();
            try {
                cl = parser.parse(options, cmdArgs);
            } catch (ParseException ex) {
                throw new CliParseException(ex);
            }
            args = cl.getArgs();
        }
    }

    @Override
    public boolean exec() throws CliException {
        String path = args[1];
        boolean watch = cl.hasOption("w");
        Stat stat;
        try {
            stat = zk.exists(path, watch);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        if (stat == null) {
            throw new CliWrapperException(new KeeperException.NoNodeException(path));
        }
        new StatPrinter(out).print(stat);
        return watch;
    }

}
