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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;

@InterfaceAudience.Public
public class ZooDefs {

    public static final String CONFIG_NODE = "/zookeeper/config";

    public static final String ZOOKEEPER_NODE_SUBTREE = "/zookeeper/";

    @InterfaceAudience.Public
    public interface OpCode {

        int notification = 0;

        int create = 1;

        int delete = 2;

        int exists = 3;

        int getData = 4;

        int setData = 5;

        int getACL = 6;

        int setACL = 7;

        int getChildren = 8;

        int sync = 9;

        int ping = 11;

        int getChildren2 = 12;

        int check = 13;

        int multi = 14;

        int create2 = 15;

        int reconfig = 16;

        int checkWatches = 17;

        int removeWatches = 18;

        int createContainer = 19;

        int deleteContainer = 20;

        int createTTL = 21;

        int multiRead = 22;

        int auth = 100;

        int setWatches = 101;

        int sasl = 102;

        int getEphemerals = 103;

        int getAllChildrenNumber = 104;

        int setWatches2 = 105;

        int addWatch = 106;

        int whoAmI = 107;

        int createSession = -10;

        int closeSession = -11;

        int error = -1;

    }

    @InterfaceAudience.Public
    public interface Perms {

        int READ = 1 << 0;

        int WRITE = 1 << 1;

        int CREATE = 1 << 2;

        int DELETE = 1 << 3;

        int ADMIN = 1 << 4;

        int ALL = READ | WRITE | CREATE | DELETE | ADMIN;

    }

    @InterfaceAudience.Public
    public interface Ids {

        /**
         * This Id represents anyone.
         */
        Id ANYONE_ID_UNSAFE = new Id("world", "anyone");

        /**
         * This Id is only usable to set ACLs. It will get substituted with the
         * Id's the client authenticated with.
         */
        Id AUTH_IDS = new Id("auth", "");

        /**
         * This is a completely open ACL .
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        ArrayList<ACL> OPEN_ACL_UNSAFE = new ArrayList<ACL>(Collections.singletonList(new ACL(Perms.ALL, ANYONE_ID_UNSAFE)));

        /**
         * This ACL gives the creators authentication id's all permissions.
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        ArrayList<ACL> CREATOR_ALL_ACL = new ArrayList<ACL>(Collections.singletonList(new ACL(Perms.ALL, AUTH_IDS)));

        /**
         * This ACL gives the world the ability to read.
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        ArrayList<ACL> READ_ACL_UNSAFE = new ArrayList<ACL>(Collections.singletonList(new ACL(Perms.READ, ANYONE_ID_UNSAFE)));

    }

    @InterfaceAudience.Public
    public interface AddWatchModes {
        int persistent = 0; // matches AddWatchMode.PERSISTENT

        int persistentRecursive = 1;  // matches AddWatchMode.PERSISTENT_RECURSIVE
    }

    public static final String[] opNames = {"notification", "create", "delete", "exists", "getData", "setData", "getACL", "setACL", "getChildren", "getChildren2", "getMaxChildren", "setMaxChildren", "ping", "reconfig", "getConfig"};

}
