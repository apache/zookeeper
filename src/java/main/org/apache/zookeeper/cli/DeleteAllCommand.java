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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKUtil;

/**
 * deleteAll command for cli
 */
public class DeleteAllCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;

    public DeleteAllCommand() {
        this("deleteall");
    }

    public DeleteAllCommand(String cmdStr) {
        super(cmdStr, "path");
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
        printDeprecatedWarning();
        
        String path = args[1];
        try {
            ZKUtil.deleteRecursive(zk, path);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException|InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }
    
    private void printDeprecatedWarning() {
        if("rmr".equals(args[0])) {
            err.println("The command 'rmr' has been deprecated. " +
                  "Please use 'deleteall' instead.");
        }
    }
}
