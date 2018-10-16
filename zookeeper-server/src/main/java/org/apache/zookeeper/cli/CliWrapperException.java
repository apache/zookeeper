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

import org.apache.zookeeper.KeeperException;

@SuppressWarnings("serial")
public class CliWrapperException extends CliException {
    public CliWrapperException(Throwable cause) {
        super(getMessage(cause), cause);
    }
    
    private static String getMessage(Throwable cause) {
        if (cause instanceof  KeeperException) {
            KeeperException keeperException = (KeeperException) cause;
            if (keeperException instanceof KeeperException.NoNodeException) {
                return "Node does not exist: " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.NoChildrenForEphemeralsException) {
                return "Ephemerals cannot have children: " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.NodeExistsException) {
                return "Node already exists: " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.NotEmptyException) {
                return "Node not empty: " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.NotReadOnlyException) {
                return "Not a read-only call: " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.InvalidACLException) {
                return "Acl is not valid : " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.NoAuthException) {
                return "Authentication is not valid : " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.BadArgumentsException) {
                return "Arguments are not valid : " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.BadVersionException) {
                return "version No is not valid : " + keeperException.getPath();
            } else if (keeperException instanceof KeeperException.ReconfigInProgress) {
                return "Another reconfiguration is in progress -- concurrent " +
                        "reconfigs not supported (yet)";
            } else if (keeperException instanceof KeeperException.NewConfigNoQuorum) {
                return "No quorum of new config is connected and " +
                        "up-to-date with the leader of last commmitted config - try invoking reconfiguration after " +
                        "new servers are connected and synced";
            }
        }
        return cause.getMessage();
    }
}
