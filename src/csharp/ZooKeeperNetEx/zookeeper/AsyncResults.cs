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
 * All rights reserved.
 * 
 */
using System.Collections.Generic;
using org.apache.zookeeper.data;

namespace org.apache.zookeeper
{
    /// <summary>
    /// this class is the base class for all return values of zk methods
    /// </summary>
    public abstract class NodeResult {
        public readonly Stat Stat;

        protected NodeResult(Stat stat) {
            Stat = stat;
        }
    }

    /// <summary>
    /// this class is the return value of the public getDataAsync methods
    /// </summary>
    public class DataResult : NodeResult {
        public readonly byte[] Data;

        internal DataResult(byte[] data, Stat stat) : base(stat) {
            Data = data;
        }
    }

    /// <summary>
    /// this class is the return value of the public getChildrenAsync methods
    /// </summary>
    public class ChildrenResult : NodeResult {
        public readonly List<string> Children;

        internal ChildrenResult(List<string> children, Stat stat) : base(stat) {
            {
                Children = children;
            }
        }
    }

    /// <summary>
    /// this class is the return value of the public getACLAsync methods
    /// </summary>
    public class ACLResult: NodeResult {
    
        public readonly List<ACL> Acls;

        internal ACLResult(List<ACL> acls, Stat stat)
            : base(stat)
        {
            Acls = acls;
        }
    }
}
