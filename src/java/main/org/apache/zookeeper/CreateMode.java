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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;

/***
 *  CreateMode value determines how the znode is created on ZooKeeper.
 */
public enum CreateMode {
    
    /**
     * The znode will not be automatically deleted upon client's disconnect.
     */
    PERSISTENT (0, false, false),
    /**
    * The znode will not be automatically deleted upon client's disconnect,
    * and its name will be appended with a monotonically increasing number.
    */
    PERSISTENT_SEQUENTIAL (2, false, true),
    /**
     * The znode will be deleted upon the client's disconnect.
     */
    EPHEMERAL (1, true, false),
    /**
     * The znode will be deleted upon the client's disconnect, and its name
     * will be appended with a monotonically increasing number.
     */
    EPHEMERAL_SEQUENTIAL (3, true, true);

    private static final Logger LOG = LoggerFactory.getLogger(CreateMode.class);

    private boolean ephemeral;
    private boolean sequential;
    private int flag;

    CreateMode(int flag, boolean ephemeral, boolean sequential) {
        this.flag = flag;
        this.ephemeral = ephemeral;
        this.sequential = sequential;
    }

    public boolean isEphemeral() { 
        return ephemeral;
    }

    public boolean isSequential() { 
        return sequential;
    }

    public int toFlag() {
        return flag;
    }

    /**
     * Map an integer value to a CreateMode value
     */
    static public CreateMode fromFlag(int flag) throws KeeperException {
        switch(flag) {
        case 0: return CreateMode.PERSISTENT;

        case 1: return CreateMode.EPHEMERAL;

        case 2: return CreateMode.PERSISTENT_SEQUENTIAL;

        case 3: return CreateMode.EPHEMERAL_SEQUENTIAL ;

        default:
            LOG.error("Received an invalid flag value to convert to a CreateMode");
            throw new KeeperException.BadArgumentsException(); 
        }
    }
}
