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

package org.apache.zookeeper;

import java.util.List;

import jline.Completor;

class JLineZNodeCompletor implements Completor {
    private ZooKeeper zk;

    public JLineZNodeCompletor(ZooKeeper zk) {
        this.zk = zk;
    }

    @SuppressWarnings(value={"unchecked", "rawtypes"})
    public int complete(String buffer, int cursor, List candidates) {
        // Guarantee that the final token is the one we're expanding
        buffer = buffer.substring(0,cursor);
        String token = "";
        if (!buffer.endsWith(" ")) {
            String[] tokens = buffer.split(" ");
            if (tokens.length != 0) {
                token = tokens[tokens.length-1] ;
            }
        }

        if (token.startsWith("/")){
            return completeZNode( buffer, token, candidates);
        }
        return completeCommand(buffer, token, candidates);
    }

    private int completeCommand(String buffer, String token,
            List<String> candidates)
    {
        for (String cmd : ZooKeeperMain.getCommands()) {
            if (cmd.startsWith( token )) {
                candidates.add(cmd);
            }
        }
        return buffer.lastIndexOf(" ")+1;
    }

    private int completeZNode( String buffer, String token,
            List<String> candidates)
    {
        String path = token;
        int idx = path.lastIndexOf("/") + 1;
        String prefix = path.substring(idx);
        try {
            // Only the root path can end in a /, so strip it off every other prefix
            String dir = idx == 1 ? "/" : path.substring(0,idx-1);
            List<String> children = zk.getChildren(dir, false);
            for (String child : children) {
                if (child.startsWith(prefix)) {
                    candidates.add( child );
                }
            }
        } catch( InterruptedException e) {
            return 0;
        }
        catch( KeeperException e) {
            return 0;
        }
        return candidates.size() == 0 ? buffer.length() : buffer.lastIndexOf("/") + 1;
    }
}
