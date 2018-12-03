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

import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

/**
 * getAcl command for cli
 */
public class GetAclCommand extends CliCommand {

    private static Options options = new Options();
    private String args[];
    private CommandLine cl;

    {
        options.addOption("s", false, "stats");
    }

    public GetAclCommand() {
        super("getAcl", "[-s] path");
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
        if (args.length < 2) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        String path = args[1];
        Stat stat = new Stat();
        List<ACL> acl;
        try {
           acl = zk.getACL(path, stat);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException|InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        for (ACL a : acl) {
            out.println(a.getId() + ": "
                        + getPermString(a.getPerms()));
        }

        if (cl.hasOption("s")) {
            new StatPrinter(out).print(stat);
        }
        return false;
    }

    private static String getPermString(int perms) {
        StringBuilder p = new StringBuilder();
        if ((perms & ZooDefs.Perms.CREATE) != 0) {
            p.append('c');
        }
        if ((perms & ZooDefs.Perms.DELETE) != 0) {
            p.append('d');
        }
        if ((perms & ZooDefs.Perms.READ) != 0) {
            p.append('r');
        }
        if ((perms & ZooDefs.Perms.WRITE) != 0) {
            p.append('w');
        }
        if ((perms & ZooDefs.Perms.ADMIN) != 0) {
            p.append('a');
        }
        return p.toString();
    }
}
