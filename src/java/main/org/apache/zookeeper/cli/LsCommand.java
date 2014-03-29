/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zookeeper.cli;

import java.util.Collections;
import java.util.List;
import org.apache.commons.cli.*;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * ls command for cli
 */
public class LsCommand extends CliCommand {

    private static Options options = new Options();
    private String args[];
    private CommandLine cl;

    {
        options.addOption("?", false, "help");
        options.addOption("s", false, "stat");
        options.addOption("w", false, "watch");
    }

    public LsCommand() {
        super("ls", "[-s] [-w] path");
    }

    private void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("ls [options] path", options);
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws ParseException {
        Parser parser = new PosixParser();
        cl = parser.parse(options, cmdArgs);
        args = cl.getArgs();
        if (cl.hasOption("?")) {
            printHelp();
        }

        if (args.length < 2) {
            throw new ParseException(getUsageStr());
        }

        retainCompatibility(cmdArgs);
        
        return this;
    }

    private void retainCompatibility(String[] cmdArgs) throws ParseException {
        // get path [watch]
        if (args.length > 2) {
            // rewrite to option
            cmdArgs[2] = "-w";
            err.println("'ls path [watch]' has been deprecated. "
                    + "Please use 'ls [-w] path' instead.");
            Parser parser = new PosixParser();
            cl = parser.parse(options, cmdArgs);
            args = cl.getArgs();
        }
    }

    @Override
    public boolean exec() throws KeeperException, InterruptedException {
        String path = args[1];
        boolean watch = cl.hasOption("w");
        boolean withStat = cl.hasOption("s");
        try {
            Stat stat = new Stat();
            List<String> children;
            if (withStat) {
                // with stat
                children = zk.getChildren(path, watch, stat);
            } else {
                // without stat
                children = zk.getChildren(path, watch);
            }
            out.println(printChildren(children));
            if (withStat) {
                new StatPrinter(out).print(stat);
            }
        } catch (KeeperException.NoAuthException ex) {
            err.println(ex.getMessage());
            watch = false;
        }
        return watch;
    }

    private String printChildren(List<String> children) {
        Collections.sort(children);
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (String child : children) {
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }
            sb.append(child);
        }
        sb.append("]");
        return sb.toString();
    }
}
