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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * set command for cli
 */
public class SetCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption("s", false, "stats");
        options.addOption("v", true, "version");
        options.addOption("f", true, "input file name");
    }

    public SetCommand() {
        super("set", "[-s] [-v version] path data [-f input_file_name]");
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
        if (!cl.hasOption("f") && args.length < 3) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    private byte[] getData() throws CliException {
        if (cl.hasOption("f")) {
            String fileName = cl.getOptionValue("f");
            try {
                Path path = Paths.get(fileName);
                return Files.readAllBytes(path);
            } catch (IOException ex) {
                throw new CliParseException(ex.getMessage());
            }
        } else {
            return args[2].getBytes(UTF_8);
        }
    }

    @Override
    public boolean exec() throws CliException {
        String path = args[1];

        byte[] data = getData();

        int version;
        if (cl.hasOption("v")) {
            version = Integer.parseInt(cl.getOptionValue("v"));
        } else {
            version = -1;
        }

        try {
            Stat stat = zk.setData(path, data, version);
            if (cl.hasOption("s")) {
                new StatPrinter(out).print(stat);
            }
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }

}
