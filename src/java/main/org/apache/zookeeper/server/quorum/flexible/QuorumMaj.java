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

package org.apache.zookeeper.server.quorum.flexible;

import java.util.Set;

//import org.apache.zookeeper.server.quorum.QuorumCnxManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a validator for majority quorums. The 
 * implementation is straightforward.
 *
 */
public class QuorumMaj implements QuorumVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumMaj.class);
    
    int half;
    
    /**
     * Defines a majority to avoid computing it every time.
     * 
     * @param n number of servers
     */
    public QuorumMaj(int n){
        this.half = n/2;
    }
    
    /**
     * Returns weight of 1 by default.
     * 
     * @param id 
     */
    public long getWeight(long id){
        return (long) 1;
    }
    
    /**
     * Verifies if a set is a majority.
     */
    public boolean containsQuorum(Set<Long> set){
        return (set.size() > half);
    }
    
}
