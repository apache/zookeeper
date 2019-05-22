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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;

@InterfaceAudience.Public
public class ZooDefs {
   
   final public static String CONFIG_NODE = "/zookeeper/config";

   @InterfaceAudience.Public
    public interface OpCode {
        public final int notification = 0;

        public final int create = 1;

        public final int delete = 2;

        public final int exists = 3;

        public final int getData = 4;

        public final int setData = 5;

        public final int getACL = 6;

        public final int setACL = 7;

        public final int getChildren = 8;

        public final int sync = 9;

        public final int ping = 11;

        public final int getChildren2 = 12;

        public final int check = 13;

        public final int multi = 14;
        
        public final int create2 = 15;

        public final int reconfig = 16;

        public final int checkWatches = 17;

        public final int removeWatches = 18;

        public final int createContainer = 19;

        public final int deleteContainer = 20;

        public final int createTTL = 21;

        public final int auth = 100;

        public final int setWatches = 101;

        public final int sasl = 102;

        public final int getEphemerals = 103;

        public final int getAllChildrenNumber = 104;

        public final int createSession = -10;

        public final int closeSession = -11;

        public final int error = -1;
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
        public final Id ANYONE_ID_UNSAFE = new Id("world", "anyone");

        /**
         * This Id is only usable to set ACLs. It will get substituted with the
         * Id's the client authenticated with.
         */
        public final Id AUTH_IDS = new Id("auth", "");

        /**
         * This is a completely open ACL .
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        public final ArrayList<ACL> OPEN_ACL_UNSAFE = new ArrayList<ACL>(
                Collections.singletonList(new ACL(Perms.ALL, ANYONE_ID_UNSAFE)));

        /**
         * This ACL gives the creators authentication id's all permissions.
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        public final ArrayList<ACL> CREATOR_ALL_ACL = new ArrayList<ACL>(
                Collections.singletonList(new ACL(Perms.ALL, AUTH_IDS)));

        /**
         * This ACL gives the world the ability to read.
         */
        @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION", justification = "Cannot break API")
        public final ArrayList<ACL> READ_ACL_UNSAFE = new ArrayList<ACL>(
                Collections
                        .singletonList(new ACL(Perms.READ, ANYONE_ID_UNSAFE)));
    }

    final public static String[] opNames = { "notification", "create",
            "delete", "exists", "getData", "setData", "getACL", "setACL",
            "getChildren", "getChildren2", "getMaxChildren", "setMaxChildren", "ping", "reconfig", "getConfig" };
}
