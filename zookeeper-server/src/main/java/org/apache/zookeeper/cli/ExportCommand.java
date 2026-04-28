/**
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * export command for cli
 */
public final class ExportCommand extends CliCommand {

    private static Options options = new Options();
    private String args[];
    private CommandLine cl;

    static {
        options.addOption("s", false, "stats");
        options.addOption("w", false, "watch");
    }

    public ExportCommand() {
        super("export", "[-s] [-w] path filepath", options);
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {

        CommandLineParser parser = new DefaultParser();
        try {
            cl = parser.parse(options, cmdArgs);
        } catch (ParseException ex) {
            throw new CliParseException(ex);
        }
        args = cl.getArgs();
        if (args.length < 3) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        boolean watch = cl.hasOption("w");
        String path = args[1];
        String filepath = args[2];
        Stat stat = new Stat();
        byte data[];
        try {
            data = zk.getData(path, watch, stat);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        data = (data == null) ? "null".getBytes() : data;
        try {
            Files.write(Paths.get(filepath), data);
        } catch (IOException ex) {
            throw new CliWrapperException("Unable to write data to file \"" + filepath + "\"", ex);
        }
        if (cl.hasOption("s")) {
            new StatPrinter(out).print(stat);
        }
        return watch;
    }
}
