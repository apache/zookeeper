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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.EphemeralType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/***
 *  CreateMode value determines how the znode is created on ZooKeeper.
 */
@InterfaceAudience.Public
public enum CreateMode {

    /**
     * The znode will not be automatically deleted upon client's disconnect.
     */
    PERSISTENT(0, false, false, false, false, "PERSISTENT"),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     */
    PERSISTENT_SEQUENTIAL(2, false, true, false, false, "PERSISTENT_SEQUENTIAL"),
    /**
     * The znode will be deleted upon the client's disconnect.
     */
    EPHEMERAL(1, true, false, false, false, "EPHEMERAL"),
    /**
     * The znode will be deleted upon the client's disconnect, and its name
     * will be appended with a monotonically increasing number.
     */
    EPHEMERAL_SEQUENTIAL(3, true, true, false, false, "EPHEMERAL_SEQUENTIAL"),
    /**
     * The znode will be a container node. Container
     * nodes are special purpose nodes useful for recipes such as leader, lock,
     * etc. When the last child of a container is deleted, the container becomes
     * a candidate to be deleted by the server at some point in the future.
     * Given this property, you should be prepared to get
     * {@link org.apache.zookeeper.KeeperException.NoNodeException}
     * when creating children inside of this container node.
     */
    CONTAINER(4, false, false, true, false, "CONTAINER"),
    /**
     * The znode will not be automatically deleted upon client's disconnect.
     * However if the znode has not been modified within the given TTL, it
     * will be deleted once it has no children.
     */
    PERSISTENT_WITH_TTL(5, false, false, false, true, "PERSISTENT_WITH_TTL"),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     * However if the znode has not been modified within the given TTL, it
     * will be deleted once it has no children.
     */
    PERSISTENT_SEQUENTIAL_WITH_TTL(6, false, true, false, true, "PERSISTENT_SEQUENTIAL_WITH_TTL");

    private static final Logger LOG = LoggerFactory.getLogger(CreateMode.class);

    private boolean ephemeral;
    private boolean sequential;
    private final boolean isContainer;
    private int flag;
    private boolean isTTL;
    private String msg;

    CreateMode(int flag, boolean ephemeral, boolean sequential, boolean isContainer, boolean isTTL, String msg) {
        this.flag = flag;
        this.ephemeral = ephemeral;
        this.sequential = sequential;
        this.isContainer = isContainer;
        this.isTTL = isTTL;
        this.msg = msg;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public boolean isSequential() {
        return sequential;
    }

    public boolean isContainer() {
        return isContainer;
    }

    public boolean isTTL() {
        return isTTL;
    }

    public int toFlag() {
        return flag;
    }

    /**
     * Map an integer value to a CreateMode value
     */
    public static CreateMode fromFlag(int flag) throws KeeperException {
        switch (flag) {
        case 0:
            return CreateMode.PERSISTENT;

        case 1:
            return CreateMode.EPHEMERAL;

        case 2:
            return CreateMode.PERSISTENT_SEQUENTIAL;

        case 3:
            return CreateMode.EPHEMERAL_SEQUENTIAL;

        case 4:
            return CreateMode.CONTAINER;

        case 5:
            return CreateMode.PERSISTENT_WITH_TTL;

        case 6:
            return CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL;

        default:
            String errMsg = "Received an invalid flag value: " + flag + " to convert to a CreateMode";
            LOG.error(errMsg);
            throw new KeeperException.BadArgumentsException(errMsg);
        }
    }

    /**
     * Map an integer value to a CreateMode value
     */
    public static CreateMode fromFlag(int flag, CreateMode defaultMode) {
        switch (flag) {
        case 0:
            return CreateMode.PERSISTENT;

        case 1:
            return CreateMode.EPHEMERAL;

        case 2:
            return CreateMode.PERSISTENT_SEQUENTIAL;

        case 3:
            return CreateMode.EPHEMERAL_SEQUENTIAL;

        case 4:
            return CreateMode.CONTAINER;

        case 5:
            return CreateMode.PERSISTENT_WITH_TTL;

        case 6:
            return CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL;

        default:
            return defaultMode;
        }
    }

    public static CreateMode getNodeMode(long ephemeralOwner) {
        if (EphemeralType.get(ephemeralOwner) == EphemeralType.NORMAL) {
            return CreateMode.EPHEMERAL;
        } else if (EphemeralType.get(ephemeralOwner) == EphemeralType.CONTAINER) {
            return CreateMode.CONTAINER;
        } else if (EphemeralType.get(ephemeralOwner) == EphemeralType.TTL) {
            return CreateMode.PERSISTENT_WITH_TTL;
        }

        return CreateMode.PERSISTENT;
    }

    public static String getModeName(int flag) {
        for (CreateMode mode : CreateMode.values()) {
            if (mode.toFlag() == flag) {
                return mode.msg;
            }
        }
        return null;
    }
}
