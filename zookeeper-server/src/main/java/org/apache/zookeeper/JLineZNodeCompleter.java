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

class JLineZNodeCompleter implements Completer {

    private ZooKeeper zk;

    public JLineZNodeCompleter(ZooKeeper zk) {
        this.zk = zk;
    }

    @Override
    public void complete(LineReader lineReader, ParsedLine parsedLine, List<Candidate> list) {
        String buffer = lineReader.getBuffer().substring(0, parsedLine.cursor());
        String token = "";
        if (!buffer.endsWith(" ")) {
            String[] tokens = buffer.split(" ");
            if (tokens.length != 0) {
                token = tokens[tokens.length - 1];
            }
        }

        if (token.startsWith("/")) {
            completeZNode(buffer, token, list);
        }
        completeCommand(buffer, token, list);
    }

    private void completeCommand(String buffer, String token, List<Candidate> candidates) {
        for (String cmd : ZooKeeperMain.getCommands()) {
            if (cmd.startsWith(token)) {
                candidates.add(new Candidate(cmd));
            }
        }
    }

    private void completeZNode(String buffer, String token, List<Candidate> candidates) {
        int idx = token.lastIndexOf("/") + 1;
        String prefix = token.substring(idx);
        try {
            // Only the root path can end in a /, so strip it off every other prefix
            String dir = idx == 1 ? "/" : token.substring(0, idx - 1);
            List<String> children = zk.getChildren(dir, false);
            for (String child : children) {
                if (child.startsWith(prefix)) {
                    candidates.add(new Candidate(child));
                }
            }
        } catch (InterruptedException | KeeperException e) {
            return;
        }
        Collections.sort(candidates);
    }

}
