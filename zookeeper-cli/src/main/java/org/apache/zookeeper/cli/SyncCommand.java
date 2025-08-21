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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * sync command for cli
 */
public class SyncCommand extends CliCommand {

    private static Options options = new Options();
    private String[] args;
    public static final long SYNC_TIMEOUT = TimeUnit.SECONDS.toMillis(30L);

    public SyncCommand() {
        super("sync", "path");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        DefaultParser parser = new DefaultParser();
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
        String path = args[1];
        CompletableFuture<Integer> cf = new CompletableFuture<>();

        try {
            zk.sync(path, (rc, path1, ctx) -> cf.complete(rc), null);

            int resultCode = cf.get(SYNC_TIMEOUT, TimeUnit.MILLISECONDS);
            if (resultCode == 0) {
                out.println("Sync is OK");
            } else {
                out.println("Sync has failed. rc=" + resultCode);
            }
        } catch (IllegalArgumentException ex) {
            throw new MalformedPathException(ex.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CliWrapperException(ie);
        } catch (TimeoutException | ExecutionException ex) {
            throw new CliWrapperException(ex);
        }

        return false;
    }

}
