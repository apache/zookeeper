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
import org.apache.zookeeper.data.Stat;
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
    PERSISTENT(0, false, false, false, false),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     *
     * @apiNote This number is 32-bit and unique to the parent znode. It is
     *          formatted with %010d, that is 10 digits with 0 (zero) padding,
     *          to simplify sorting, i.e. "0000000001". The counter used to
     *          store the sequence number is a signed int (4bytes) maintained
     *          by the parent node, it will overflow when incremented beyond
     *          2147483647 (resulting in a name "-2147483648").
     * @implNote The behavior after overflow is undefined.
     */
    PERSISTENT_SEQUENTIAL(2, false, true, false, false),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     *
     * @apiNote This number is 64-bit and unique to the parent znode. It is
     *          formatted with %019d, that is 19 digits with 0 (zero) padding
     *          so to simplify sorting, i.e. "0000000000000000001".
     * @apiNote It is not guaranteed to be consecutive.
     * @implNote The number is {@link Stat#getCzxid()} for created node, so
     *           multiple creations with same prefix path in {@link ZooKeeper#multi(Iterable)}
     *           will fail with {@link KeeperException.NodeExistsException}.
     */
    PERSISTENT_SEQUENTIAL_LONG(7, false, true, false, false, true),
    /**
     * The znode will be deleted upon the client's disconnect.
     */
    EPHEMERAL(1, true, false, false, false),
    /**
     * The znode will be deleted upon the client's disconnect, and its name
     * will be appended with a monotonically increasing number.
     *
     * @apiNote This number is 32-bit and unique to the parent znode. It is
     *          formatted with %010d, that is 10 digits with 0 (zero) padding,
     *          to simplify sorting, i.e. "0000000001". The counter used to
     *          store the sequence number is a signed int (4bytes) maintained
     *          by the parent node, it will overflow when incremented beyond
     *          2147483647 (resulting in a name "-2147483648").
     * @implNote The behavior after overflow is undefined.
     */
    EPHEMERAL_SEQUENTIAL(3, true, true, false, false),
    /**
     * The znode will be deleted upon the client's disconnect, and its name
     * will be appended with a monotonically increasing number.
     *
     * @apiNote This number is 64-bit and unique to the parent znode. It is
     *          formatted with %019d, that is 19 digits with 0 (zero) padding
     *          so to simplify sorting, i.e. "0000000000000000001".
     * @apiNote It is not guaranteed to be consecutive.
     * @implNote The number is {@link Stat#getCzxid()} for created node, so
     *           multiple creations with same prefix path in {@link ZooKeeper#multi(Iterable)}
     *           will fail with {@link KeeperException.NodeExistsException}.
     */
    EPHEMERAL_SEQUENTIAL_LONG(8, true, true, false, false, true),
    /**
     * The znode will be a container node. Container
     * nodes are special purpose nodes useful for recipes such as leader, lock,
     * etc. When the last child of a container is deleted, the container becomes
     * a candidate to be deleted by the server at some point in the future.
     * Given this property, you should be prepared to get
     * {@link org.apache.zookeeper.KeeperException.NoNodeException}
     * when creating children inside of this container node.
     */
    CONTAINER(4, false, false, true, false),
    /**
     * The znode will not be automatically deleted upon client's disconnect.
     * However if the znode has not been modified within the given TTL, it
     * will be deleted once it has no children.
     */
    PERSISTENT_WITH_TTL(5, false, false, false, true),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     * However if the znode has not been modified within the given TTL, it
     * will be deleted once it has no children.
     *
     * @apiNote This number is 32-bit and unique to the parent znode. It is
     *          formatted with %010d, that is 10 digits with 0 (zero) padding,
     *          to simplify sorting, i.e. "0000000001". The counter used to
     *          store the sequence number is a signed int (4bytes) maintained
     *          by the parent node, it will overflow when incremented beyond
     *          2147483647 (resulting in a name "-2147483648").
     * @implNote The behavior after overflow is undefined.
     */
    PERSISTENT_SEQUENTIAL_WITH_TTL(6, false, true, false, true),
    /**
     * The znode will not be automatically deleted upon client's disconnect,
     * and its name will be appended with a monotonically increasing number.
     * However if the znode has not been modified within the given TTL, it
     * will be deleted once it has no children.
     *
     * @apiNote This number is 64-bit and unique to the parent znode. It is
     *          formatted with %019d, that is 19 digits with 0 (zero) padding
     *          so to simplify sorting, i.e. "0000000000000000001".
     * @apiNote It is not guaranteed to be consecutive.
     * @implNote The number is {@link Stat#getCzxid()} for created node, so
     *           multiple creations with same prefix path in {@link ZooKeeper#multi(Iterable)}
     *           will fail with {@link KeeperException.NodeExistsException}.
     */
    PERSISTENT_SEQUENTIAL_LONG_WITH_TTL(9, false, true, false, true, true);

    private static final Logger LOG = LoggerFactory.getLogger(CreateMode.class);

    private final boolean ephemeral;
    private final boolean sequential;
    private final boolean isContainer;
    private final int flag;
    private final boolean isTTL;
    private final boolean isLong;

    CreateMode(int flag, boolean ephemeral, boolean sequential, boolean isContainer, boolean isTTL, boolean isLong) {
        this.flag = flag;
        this.ephemeral = ephemeral;
        this.sequential = sequential;
        this.isContainer = isContainer;
        this.isTTL = isTTL;
        this.isLong = isLong;
    }

    CreateMode(int flag, boolean ephemeral, boolean sequential, boolean isContainer, boolean isTTL) {
        this(flag, ephemeral, sequential, isContainer, isTTL, false);
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public boolean isSequential() {
        return sequential;
    }

    public boolean isLongSequential() {
        return sequential && isLong;
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

        case 7:
            return CreateMode.PERSISTENT_SEQUENTIAL_LONG;

        case 8:
            return CreateMode.EPHEMERAL_SEQUENTIAL_LONG;

        case 9:
            return CreateMode.PERSISTENT_SEQUENTIAL_LONG_WITH_TTL;

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

        case 7:
            return CreateMode.PERSISTENT_SEQUENTIAL_LONG;

        case 8:
            return CreateMode.EPHEMERAL_SEQUENTIAL_LONG;

        case 9:
            return CreateMode.PERSISTENT_SEQUENTIAL_LONG_WITH_TTL;

        default:
            return defaultMode;
        }
    }
}
