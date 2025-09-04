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
 * get command for cli
 */
public class GetCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption("s", false, "Print znode stats additionally");
        options.addOption("w", false, "Watch for changes on the znode");
        options.addOption("b", false, "Output data in base64 format");
        options.addOption("x", false, "Output data in hexdump format");
    }

    public GetCommand() {
        super("get", "[-s] [-w] [-b] [-x] path", options);
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
        // get path [watch]
        if (args.length > 2) {
            // rewrite to option
            cmdArgs[2] = "-w";
            err.println("'get path [watch]' has been deprecated. " + "Please use 'get [-s] [-w] path' instead.");
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
        boolean watch = cl.hasOption("w");
        String path = args[1];
        Stat stat = new Stat();
        byte[] data;
        try {
            data = zk.getData(path, watch, stat);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        OutputFormatter formatter = PlainOutputFormatter.INSTANCE;
        if (cl.hasOption("b")) {
            formatter = Base64OutputFormatter.INSTANCE;
        }
        if (cl.hasOption("x")) {
            formatter = HexDumpOutputFormatter.INSTANCE;
        }
        data = (data == null) ? "null".getBytes() : data;
        out.println(formatter.format(data));
        if (cl.hasOption("s")) {
            new StatPrinter(out).print(stat);
        }
        return watch;
    }

}
