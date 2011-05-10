/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
﻿namespace ZooKeeperNet
{
    using System.Collections.Generic;
    using Org.Apache.Zookeeper.Data;

    public class Ids
    {
        /**
         * This Id represents anyone.
         */
        public static readonly ZKId ANYONE_ID_UNSAFE = new ZKId("world", "anyone");

        /**
         * This Id is only usable to set ACLs. It will get substituted with the
         * Id's the client authenticated with.
         */
        public static readonly ZKId AUTH_IDS = new ZKId("auth", "");

        /**
         * This is a completely open ACL .
         */
        public static readonly List<ACL> OPEN_ACL_UNSAFE = new List<ACL>(new[] { new ACL(Perms.ALL, ANYONE_ID_UNSAFE) });

        /**
         * This ACL gives the creators authentication id's all permissions.
         */
        public static readonly List<ACL> CREATOR_ALL_ACL = new List<ACL>(new[] { new ACL(Perms.ALL, AUTH_IDS) });

        /**
         * This ACL gives the world the ability to read.
         */
        public static readonly List<ACL> READ_ACL_UNSAFE = new List<ACL>(new[] { new ACL(Perms.READ, ANYONE_ID_UNSAFE) });
    }
}
