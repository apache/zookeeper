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

import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.EphemeralType;

/**
 * create or set command for cli
 */
public class CreateOrSetCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    static {
        options.addOption(new Option("e", false, "ephemeral"));
        options.addOption(new Option("t", true, "ttl"));
        options.addOption("v", true, "version");
    }

    public CreateOrSetCommand() {
        super("createorset", "[-e] [-t ttl] [-v version] path [data] [acl]");
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
        boolean hasE = cl.hasOption("e");
        boolean hasT = cl.hasOption("t");
        long ttl;
        try {
            ttl = hasT ? Long.parseLong(cl.getOptionValue("t")) : 0;
        } catch (NumberFormatException e) {
            throw new MalformedCommandException("-t argument must be a long value");
        }

        if (hasT && hasE) {
            throw new MalformedCommandException("TTLs cannot be used with Ephemeral znodes");
        }
        
        CreateMode flags;
        if (hasE) {
            flags = CreateMode.EPHEMERAL;
        } else {
            flags = hasT ? CreateMode.PERSISTENT_WITH_TTL : CreateMode.PERSISTENT;
        }
        if (hasT) {
            try {
                EphemeralType.TTL.toEphemeralOwner(ttl);
            } catch (IllegalArgumentException e) {
                throw new MalformedCommandException(e.getMessage());
            }
        }

        String path = args[1];
        byte[] data = null;
        if (args.length > 2) {
            data = args[2].getBytes();
        }
        List<ACL> acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
        if (args.length > 3) {
            acl = AclParser.parse(args[3]);
        }
        
        int version;
        if (cl.hasOption("v")) {
            version = Integer.parseInt(cl.getOptionValue("v"));
        } else {
            version = -1;
        }
        
        try {
            String newPath = hasT
                ? zk.createOrSet(path, data, acl, flags, version, new Stat(), ttl)
                : zk.createOrSet(path, data, acl, flags, version, new Stat());
            err.println("Created Or Set " + newPath);
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (KeeperException.EphemeralOnLocalSessionException e) {
            err.println("Unable to create or set ephemeral node on a local session");
            throw new CliWrapperException(e);
        } catch (KeeperException.InvalidACLException ex) {
            err.println(ex.getMessage());
            throw new CliWrapperException(ex);
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }
        return true;
    }

}
