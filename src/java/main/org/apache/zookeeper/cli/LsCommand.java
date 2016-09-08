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
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKUtil;
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
        options.addOption("R", false, "recurse");
    }

    public LsCommand() {
        super("ls", "[-s] [-w] [-R] path");
    }

    private void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("ls [options] path", options);
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
        if (cl.hasOption("?")) {
            printHelp();
        }

        retainCompatibility(cmdArgs);

        return this;
    }

    private void retainCompatibility(String[] cmdArgs) throws CliParseException {
        // get path [watch]
        if (args.length > 2) {
            // rewrite to option
            cmdArgs[2] = "-w";
            err.println("'ls path [watch]' has been deprecated. "
                    + "Please use 'ls [-w] path' instead.");
            Parser parser = new PosixParser();
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
        if (args.length < 2) {
            throw new MalformedCommandException(getUsageStr());
        }

        String path = args[1];
        boolean watch = cl.hasOption("w");
        boolean withStat = cl.hasOption("s");
        boolean recursive = cl.hasOption("R");
        try {
            if (recursive) {
                ZKUtil.visitSubTreeDFS(zk, path, watch, new StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        out.println(path);
                    }
                });
            } else {
                Stat stat = withStat ? new Stat() : null;
                List<String> children = zk.getChildren(path, watch, stat);
                printChildren(children, stat);
            }
        } catch (KeeperException|InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return watch;
    }

    private void printChildren(List<String> children, Stat stat) {
        Collections.sort(children);
        out.append("[");
        boolean first = true;
        for (String child : children) {
            if (!first) {
                out.append(", ");
            } else {
                first = false;
            }
            out.append(child);
        }
        out.append("]");
        if (stat != null) {
            new StatPrinter(out).print(stat);
        }
        out.append("\n");
    }
}
