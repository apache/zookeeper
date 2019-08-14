/*
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

import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * ls2 command for cli
 */
public class Ls2Command extends CliCommand {

    private static Options options = new Options();
    private String[] args;

    public Ls2Command() {
        super("ls2", "path [watch]");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        Parser parser = new PosixParser();
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
        err.println("'ls2' has been deprecated. " + "Please use 'ls [-s] path' instead.");
        String path = args[1];
        boolean watch = args.length > 2;
        Stat stat = new Stat();
        List<String> children;
        try {
            children = zk.getChildren(path, watch, stat);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        out.println(children);
        new StatPrinter(out).print(stat);
        return watch;
    }

}
