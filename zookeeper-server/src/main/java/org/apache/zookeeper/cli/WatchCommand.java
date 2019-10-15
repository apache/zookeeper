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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

/**
 * watch command for CLI
 */
public class WatchCommand extends CliCommand {
    private static Options options = new Options();
    private String[] args;
    private CommandLine cl;

    public WatchCommand() {
        super("watch", "[-b] [-d|-c|-e] path");

        options.addOption(new Option("b", false, "blocking mode"));
        options.addOption(new Option("d", false, "data changed watch"));
        options.addOption(new Option("c", false, "child changed watch"));
        options.addOption(new Option("e", false, "exist watch"));
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
        if (args.length < 2 || cl.getOptions().length > 2 || (cl.getOptions().length == 2 && !cl.hasOption("b"))) {
            throw new CliParseException(getUsageStr());
        }

        return this;
    }

    @Override
    public boolean exec() throws CliException {
        try {
            String path = args[1];
            CountDownLatch latch = new CountDownLatch(1);
            SimpleCLIWatcher cliWatcher = new SimpleCLIWatcher(latch);
            boolean isBlocking = cl.hasOption("b");

            if (cl.getOptions().length == 0 || (isBlocking && cl.getOptions().length == 1) || cl.hasOption("d")) {
                if (isBlocking) {
                    zk.getData(path, cliWatcher, null);
                    latch.await();
                    WatchedEvent watchedEvent = cliWatcher.getEvent();
                    printWatchedEvent(watchedEvent);
                    if (!cliWatcher.isWatched()) {
                        return false;
                    }
                    if (watchedEvent.getType().equals(Watcher.Event.EventType.NodeDataChanged)) {
                        byte[] newData = zk.getData(path, false, null);
                        out.println("new data:" + new String(newData));
                    }
                } else {
                    zk.getData(path, true, null);
                }
            } else if (cl.hasOption("c")) {
                if (isBlocking) {
                    zk.getChildren(path, cliWatcher);
                    latch.await();
                    WatchedEvent watchedEvent = cliWatcher.getEvent();
                    printWatchedEvent(watchedEvent);
                    if (!cliWatcher.isWatched()) {
                        return false;
                    }
                    if ((watchedEvent.getType().equals(Watcher.Event.EventType.NodeChildrenChanged))) {
                        List<String> newChildList = zk.getChildren(path, false);
                        out.println("new child list:" + newChildList.toString());
                    }
                } else {
                    zk.getChildren(path, true);
                }
            } else if (cl.hasOption("e")) {
                if (isBlocking) {
                    zk.exists(path, cliWatcher);
                    latch.await();
                    WatchedEvent watchedEvent = cliWatcher.getEvent();
                    printWatchedEvent(watchedEvent);
                } else {
                    zk.exists(path, true);
                }
            }
        } catch (KeeperException | InterruptedException ex) {
            throw new CliWrapperException(ex);
        }

        return false;
    }

    private void printWatchedEvent(WatchedEvent watchedEvent) {
        out.println("WatchedEvent state:" + watchedEvent.getState());
        out.println("type:" + watchedEvent.getType());
        out.println("path:" + watchedEvent.getPath());
    }

    private static class SimpleCLIWatcher implements Watcher {
        private CountDownLatch latch;
        private WatchedEvent event;
        private boolean watched = false;

        public SimpleCLIWatcher(CountDownLatch latch) {
            this.latch = latch;
        }

        public WatchedEvent getEvent() {
            return event;
        }

        public boolean isWatched() {
            return watched;
        }

        public void process(WatchedEvent e) {
            if (e.getType() != Event.EventType.None
                    && e.getState() == Event.KeeperState.SyncConnected) {
                watched = true;
            }
            event = e;
            latch.countDown();
        }
    }
}
