/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zookeeper.cli;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.admin.ZooKeeperAdmin;

/**
 * The CLI command: Snapshot;especially for the backup
 */
public class SnapshotCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;

    public SnapshotCommand() {
        super("snapshot", "[dir]");
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

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        if (args.length > 2) {
            throw new MalformedCommandException(getUsageStr());
        }

        CompletableFuture<Integer> cf = new CompletableFuture<>();
        try {
            String dir = args.length > 1 ? args[1] : null;

            ((ZooKeeperAdmin)zk).takeSnapshot(dir, new AsyncCallback.VoidCallback() {

                public void processResult(int rc, String path, Object ctx) {
                    cf.complete(rc);
                }
            }, null);

            int resultCode = cf.get();
            if (resultCode == 0) {
                out.println("Snapshot is OK");
            } else {
                out.println("Snapshot has failed. rc=" + resultCode);
            }
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliWrapperException(e);
        } catch (ExecutionException e) {
            throw new CliWrapperException(e);
        }

        return false;
    }
}
