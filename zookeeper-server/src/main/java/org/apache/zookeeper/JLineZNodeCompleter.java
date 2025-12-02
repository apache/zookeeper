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

package org.apache.zookeeper;

import java.util.Collections;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.utils.AttributedString;

class JLineZNodeCompleter implements Completer {

    private final ZooKeeper zk;

    public JLineZNodeCompleter(ZooKeeper zk) {
        this.zk = zk;
    }

    @Override
    public void complete(LineReader lineReader, ParsedLine commandLine, List<Candidate> candidates) {
        // Guarantee that the final token is the one we're expanding
        String token = commandLine.words().get(commandLine.words().size() - 1);

        if (token.startsWith("/")) {
            completeZNode(token, candidates);
        } else {
            completeCommand(token, candidates);
        }
    }

    private void completeCommand(String token, List<Candidate> candidates) {
        for (String cmd : ZooKeeperMain.getCommands()) {
            if (cmd.startsWith(token)) {
                candidates.add(createCandidate(cmd));
            }
        }
    }

    private void completeZNode(String token, List<Candidate> candidates) {
        String path = token;
        int idx = path.lastIndexOf("/") + 1;
        String prefix = path.substring(idx);
        try {
            // Only the root path can end in a /, so strip it off every other prefix
            String dir = idx == 1 ? "/" : path.substring(0, idx - 1);
            List<String> children = zk.getChildren(dir, false);
            for (String child : children) {
                if (child.startsWith(prefix)) {
                    String zNode = dir + (idx == 1 ? "" : "/") + child;
                    candidates.add(createCandidate(zNode));
                }
            }
        } catch (InterruptedException | KeeperException e) {
            return;
        }
        Collections.sort(candidates);
    }

    private static Candidate createCandidate(String cmd) {
        return new Candidate(AttributedString.stripAnsi(cmd), cmd, null, null, null, null, true);
    }
}
