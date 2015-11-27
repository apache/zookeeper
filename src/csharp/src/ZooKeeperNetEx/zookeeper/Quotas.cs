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

namespace org.apache.zookeeper
{
    /// <summary>
    /// Path Quotas
    /// </summary>
    public static class Quotas {
        /// <summary>
        /// the zookeeper nodes that acts as the management and status node
        /// </summary>
        public const string procZookeeper = "/zookeeper";
        
        /// <summary>
        /// the zookeeper quota node that acts as the quota management node for zookeeper
        /// </summary>
        public const string quotaZookeeper = "/zookeeper/quota";
        
        /// <summary>
        /// the limit node that has the limit of a subtree
        /// </summary>
        public const string limitNode = "zookeeper_limits";
        
        /// <summary>
        /// the stat node that monitors the limit of a subtree.
        /// </summary>
        public const string statNode = "zookeeper_stats";
        
        /// <summary>
        /// return the quota path associated with this prefix.
        /// </summary>
        /// <param name="path">the actual path in zookeeper</param>
        /// <returns>the limit quota path</returns>
        public static string quotaPath(string path) {
            return quotaZookeeper + path +
                   "/" + limitNode;
        }
        
        /// <summary>
        /// return the stat quota path associated with this prefix.
        /// </summary>
        /// <param name="path">the actual path in zookeeper</param>
        /// <returns>the stat quota path</returns>
        public static string statPath(string path) {
            return quotaZookeeper + path + "/" +
                   statNode;
        }
    }
}
