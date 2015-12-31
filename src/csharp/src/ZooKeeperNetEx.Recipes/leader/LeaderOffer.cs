using System.Collections.Generic;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace org.apache.zookeeper.recipes.leader {
    /// <summary>
    ///     A leader offer is a numeric id / path pair. The id is the sequential node id
    ///     assigned by ZooKeeper where as the path is the absolute path to the ZNode.
    /// </summary>
    internal sealed class LeaderOffer {
        public string HostName;
        public int Id;
        public string NodePath;

        public LeaderOffer() {
            // Default constructor
        }

        public LeaderOffer(int id, string nodePath, string hostName) {
            Id = id;
            NodePath = nodePath;
            HostName = hostName;
        }

        public override string ToString() {
            return "{ id:" + Id + " nodePath:" + NodePath + " hostName:" + HostName + " }";
        }

        /// <summary>
        ///     Compare two instances of <seealso cref="LeaderOffer" /> using only the {code}id{code}
        ///     member.
        /// </summary>
        internal sealed class IdComparator : IComparer<LeaderOffer>
        {
            public int Compare(LeaderOffer o1, LeaderOffer o2) {
                return o1.Id.CompareTo(o2.Id);
            }
        }
    }
}