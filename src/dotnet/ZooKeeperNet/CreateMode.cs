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
    public sealed class CreateMode
    {
        public static readonly CreateMode Persistent = new CreateMode(0, false, false);
        public static readonly CreateMode PersistentSequential = new CreateMode(2, false, true);
        public static readonly CreateMode Ephemeral = new CreateMode(1, true, false);
        public static readonly CreateMode EphemeralSequential = new CreateMode(3, true, true);

        private readonly int flag;
        private readonly bool ephemeral;
        private readonly bool sequential;

        private CreateMode(int flag, bool ephemeral, bool sequential)
        {
            this.flag = flag;
            this.ephemeral = ephemeral;
            this.sequential = sequential;
        }

        public int Flag
        {
            get { return flag; }
        }

        public bool IsEphemeral
        {
            get { return ephemeral; }
        }

        public bool Sequential
        {
            get { return sequential; }
        }
    }
}
