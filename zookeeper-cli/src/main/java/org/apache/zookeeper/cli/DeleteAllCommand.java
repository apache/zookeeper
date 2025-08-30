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
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKUtil;

/**
 * deleteAll command for cli
 */
public class DeleteAllCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption(new Option("b", true, "batch size"));
    }

    public DeleteAllCommand() {
        this("deleteall");
    }

    public DeleteAllCommand(String cmdStr) {
        super(cmdStr, "path [-b batch size]");
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
        int batchSize;
        try {
            batchSize = cl.hasOption("b") ? Integer.parseInt(cl.getOptionValue("b")) : 1000;
        } catch (NumberFormatException e) {
            throw new MalformedCommandException("-b argument must be an int value");
        }

        String path = args[1];
        try {
            boolean success = ZKUtil.deleteRecursive(zk, path, batchSize);
            if (!success) {
                err.println("Failed to delete some node(s) in the subtree!");
            }
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }

}
