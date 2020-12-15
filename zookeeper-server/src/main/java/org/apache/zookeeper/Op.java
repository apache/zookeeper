/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.apache.jute.Record;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.CreateTTLRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.GetChildrenRequest;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.EphemeralType;

/**
 * Represents a single operation in a multi-operation transaction.  Each operation can be a create, update,
 * delete, a version check or just read operations like getChildren or getData.
 *
 * Sub-classes of Op each represent each detailed type but should not normally be referenced except via
 * the provided factory methods.
 *
 * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
 * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, Object)
 * @see ZooKeeper#delete(String, int)
 * @see ZooKeeper#setData(String, byte[], int)
 * @see ZooKeeper#getData(String, boolean, Stat)
 * @see ZooKeeper#getChildren(String, boolean)
 */
public abstract class Op {

    public enum OpKind {
        TRANSACTION,
        READ
    }

    private int type;
    private String path;
    private OpKind opKind;

    // prevent untyped construction
    private Op(int type, String path, OpKind opKind) {
        this.type = type;
        this.path = path;
        this.opKind = opKind;
    }

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     * @see CreateMode#fromFlag(int)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param flags
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential but using the integer encoding.
     */
    public static Op create(String path, byte[] data, List<ACL> acl, int flags) {
        return new Create(path, data, acl, flags, /*useCreate2*/ false);
    }

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name
     * but adding an optional ttl
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     * @see CreateMode#fromFlag(int)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param flags
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential but using the integer encoding.
     * @param ttl
     *                optional ttl or 0 (flags must imply a TTL creation mode)
     */
    public static Op create(String path, byte[] data, List<ACL> acl, int flags, long ttl) {
        CreateMode createMode = CreateMode.fromFlag(flags, CreateMode.PERSISTENT);
        if (createMode.isTTL()) {
            return new CreateTTL(path, data, acl, createMode, ttl);
        }
        return new Create(path, data, acl, flags, /*useCreate2*/ false);
    }

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param createMode
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     */
    public static Op create(String path, byte[] data, List<ACL> acl, CreateMode createMode) {
        return new Create(path, data, acl, createMode, /*useCreate2*/ false);
    }

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name
     * but adding an optional ttl
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param createMode
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     * @param ttl
     *                optional ttl or 0 (createMode must imply a TTL)
     */
    public static Op create(String path, byte[] data, List<ACL> acl, CreateMode createMode, long ttl) {
        if (createMode.isTTL()) {
            return new CreateTTL(path, data, acl, createMode, ttl);
        }
        return new Create(path, data, acl, createMode, /*useCreate2*/ false);
    }

    /**
     * Constructs a delete operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#delete(String, int)
     *
     * @param path
     *                the path of the node to be deleted.
     * @param version
     *                the expected node version.
     */
    public static Op delete(String path, int version) {
        return new Delete(path, version);
    }

    /**
     * Constructs an update operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#setData(String, byte[], int)
     *
     * @param path
     *                the path of the node
     * @param data
     *                the data to set
     * @param version
     *                the expected matching version
     */
    public static Op setData(String path, byte[] data, int version) {
        return new SetData(path, data, version);
    }

    /**
     * Constructs an version check operation.  Arguments are as for the ZooKeeper.setData method except that
     * no data is provided since no update is intended.  The purpose for this is to allow read-modify-write
     * operations that apply to multiple znodes, but where some of the znodes are involved only in the read,
     * not the write.  A similar effect could be achieved by writing the same data back, but that leads to
     * way more version updates than are necessary and more writing in general.
     *
     * @param path
     *                the path of the node
     * @param version
     *                the expected matching version
     */
    public static Op check(String path, int version) {
        return new Check(path, version);
    }

    public static Op getChildren(String path) {
        return new GetChildren(path);
    }

    public static Op getData(String path) {
        return new GetData(path);
    }

    /**
     * Gets the integer type code for an Op.  This code should be as from ZooDefs.OpCode
     * @see ZooDefs.OpCode
     * @return The type code.
     */
    public int getType() {
        return type;
    }

    /**
     * Gets the path for an Op.
     * @return The path.
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the kind of an Op.
     * @return The OpKind value.
     */
    public OpKind getKind() {
        return opKind;
    }

    /**
     * Encodes an op for wire transmission.
     * @return An appropriate Record structure.
     */
    public abstract Record toRequestRecord();

    /**
     * Reconstructs the transaction with the chroot prefix.
     * @return transaction with chroot.
     */
    abstract Op withChroot(String addRootPrefix);

    /**
     * Performs client path validations.
     *
     * @throws IllegalArgumentException
     *             if an invalid path is specified
     * @throws KeeperException.BadArgumentsException
     *             if an invalid create mode flag is specified
     */
    void validate() throws KeeperException {
        PathUtils.validatePath(path);
    }

    /**
     * "Fluent builder" for {@link Create} operations.
     */
    public static class CreateBuilder {
        private String path;
        private byte[] data;
        private List<ACL> acl;
        private Integer createModeFlag;
        private CreateMode createMode;
        private boolean returnStat = false;
        private Long ttl;

        /**
         * Sets the path for the node.  Must be set before calling
         * {@link #build()}.
         *
         * @param path  the path for the node
         * @return the builder, for chaining.
         */
        public CreateBuilder setPath(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets the initial data for the node, or {@code null}.
         *
         * @param data  the initial data for the node
         * @return the builder, for chaining.
         */
        @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
        public CreateBuilder setData(byte[] data) {
            this.data = data;
            return this;
        }

        /**
         * Sets the acl for the node.  Must be set before calling
         * {@link #build()}.
         *
         * @param acl  the acl for the node
         * @return the builder, for chaining.
         */
        public CreateBuilder setACL(List<ACL> acl) {
            this.acl = acl;
            return this;
        }

        /**
         * Specifyies whether the node to be created is ephemeral,
         * sequential, etc. using the integer encoding.  Either this
         * or {@link #setCreateMode} must be used before calling
         * {@link #build()}.
         *
         * @param createModeFlag  the create mode encoded as an integer
         * @return the builder, for chaining.
         */
        public CreateBuilder setCreateModeFlag(Integer createModeFlag) {
            this.createModeFlag = createModeFlag;
            return this;
        }

        /**
         * Specifyies whether the node to be created is ephemeral,
         * sequential, etc.  Either this or {@link #setCreateModeFlag}
         * must be used before calling {@link #build()}.
         *
         * @param createMode  the create mode as an enum value
         * @return the builder, for chaining.
         */
        public CreateBuilder setCreateMode(CreateMode createMode) {
            this.createMode = createMode;
            return this;
        }

        /**
         * Configures whether to include a {@code Stat} object in the
         * response.  Defaults to {@code false}.
         *
         * <p>Note that this flag has no effect for TTL or container
         * nodes, as those always include a {@code Stat} at the
         * protocol level.
         *
         * @param returnStat  whether node creation should produce a
         *   {@code Stat} object
         * @return the builder, for chaining.
         */
        public CreateBuilder setReturnStat(boolean returnStat) {
            this.returnStat = returnStat;
            return this;
        }

        /**
         * Sets the TTL for the node.  Must be set before calling
         * {@link #build()} when creating TTL nodes.
         *
         * @param ttl  the TTL for the node
         * @return the builder, for chaining.
         */
        public CreateBuilder setTTL(Long ttl) {
            this.ttl = ttl;
            return this;
        }

        /**
         * "Builds" the {@link Create} operation with the values
         * configured using the various {@code set*} methods.
         *
         * @return the {@link Create} or {@link CreateTTL} operation.
         */
        public Create build() {
            final CreateMode resolvedMode;

            if (createModeFlag != null) {
                if (createMode != null) {
                    throw new IllegalStateException(
                        "createModeFlag and createMode are exclusive");
                }
                resolvedMode = CreateMode.fromFlag(
                    createModeFlag, CreateMode.PERSISTENT);
            } else {
                Objects.requireNonNull(createMode,
                    "one of createModeFlag or createMode must be configured");
                resolvedMode = createMode;
            }

            if (resolvedMode.isTTL()) {
                Objects.requireNonNull(ttl,
                    "ttl must not be null for mode " + resolvedMode);
                return new CreateTTL(path, data, acl, resolvedMode, ttl);
            } else {
                return new Create(path, data, acl, resolvedMode, returnStat);
            }
        }
    }

    //////////////////
    // these internal classes are public, but should not generally be referenced.
    //
    public static class Create extends Op {

        protected byte[] data;
        protected List<ACL> acl;
        protected int flags;

        private Create(String path, byte[] data, List<ACL> acl, int flags, boolean useCreate2) {
            this(getOpcode(CreateMode.fromFlag(flags, CreateMode.PERSISTENT), useCreate2), path, data, acl, flags);
        }

        private Create(String path, byte[] data, List<ACL> acl, CreateMode createMode, boolean useCreate2) {
            this(getOpcode(createMode, useCreate2), path, data, acl, createMode.toFlag());
        }

        Create(int type, String path, byte[] data, List<ACL> acl, int flags) {
            super(type, path, OpKind.TRANSACTION);
            this.data = data;
            this.acl = acl;
            this.flags = flags;
        }

        private static int getOpcode(CreateMode createMode, boolean useCreate2) {
            if (createMode.isTTL()) {
                return ZooDefs.OpCode.createTTL;
            }
            if (createMode.isContainer()) {
                return ZooDefs.OpCode.createContainer;
            }
            if (useCreate2) {
                return ZooDefs.OpCode.create2;
            }
            return ZooDefs.OpCode.create;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Create)) {
                return false;
            }

            Create op = (Create) o;

            boolean aclEquals = true;
            Iterator<ACL> i = op.acl.iterator();
            for (ACL acl : op.acl) {
                boolean hasMoreData = i.hasNext();
                if (!hasMoreData) {
                    aclEquals = false;
                    break;
                }
                ACL otherAcl = i.next();
                if (!acl.equals(otherAcl)) {
                    aclEquals = false;
                    break;
                }
            }
            return !i.hasNext()
                   && getType() == op.getType()
                   && Arrays.equals(data, op.data)
                   && flags == op.flags
                   && aclEquals;
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode() + Arrays.hashCode(data);
        }

        @Override
        public Record toRequestRecord() {
            return new CreateRequest(getPath(), data, acl, flags);
        }

        @Override
        Op withChroot(String path) {
            return new Create(getType(), path, data, acl, flags);
        }

        @Override
        void validate() throws KeeperException {
            CreateMode createMode = CreateMode.fromFlag(flags);
            PathUtils.validatePath(getPath(), createMode.isSequential());
            EphemeralType.validateTTL(createMode, -1);
        }

    }

    public static class CreateTTL extends Create {

        private final long ttl;

        private CreateTTL(String path, byte[] data, List<ACL> acl, int flags, long ttl) {
            super(path, data, acl, flags, /*useCreate2*/ false);
            this.ttl = ttl;
        }

        private CreateTTL(String path, byte[] data, List<ACL> acl, CreateMode createMode, long ttl) {
            super(path, data, acl, createMode, /*useCreate2*/ false);
            this.ttl = ttl;
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && (o instanceof CreateTTL) && (ttl == ((CreateTTL) o).ttl);
        }

        @Override
        public int hashCode() {
            return super.hashCode() + (int) (ttl ^ (ttl >>> 32));
        }

        @Override
        public Record toRequestRecord() {
            return new CreateTTLRequest(getPath(), data, acl, flags, ttl);
        }

        @Override
        Op withChroot(String path) {
            return new CreateTTL(path, data, acl, flags, ttl);
        }

        @Override
        void validate() throws KeeperException {
            CreateMode createMode = CreateMode.fromFlag(flags);
            PathUtils.validatePath(getPath(), createMode.isSequential());
            EphemeralType.validateTTL(createMode, ttl);
        }

    }

    public static class Delete extends Op {

        private int version;

        private Delete(String path, int version) {
            super(ZooDefs.OpCode.delete, path, OpKind.TRANSACTION);
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Delete)) {
                return false;
            }

            Delete op = (Delete) o;

            return getType() == op.getType() && version == op.version && getPath().equals(op.getPath());
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode() + version;
        }

        @Override
        public Record toRequestRecord() {
            return new DeleteRequest(getPath(), version);
        }

        @Override
        Op withChroot(String path) {
            return new Delete(path, version);
        }

    }

    public static class SetData extends Op {

        private byte[] data;
        private int version;

        private SetData(String path, byte[] data, int version) {
            super(ZooDefs.OpCode.setData, path, OpKind.TRANSACTION);
            this.data = data;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SetData)) {
                return false;
            }

            SetData op = (SetData) o;

            return getType() == op.getType()
                   && version == op.version
                   && getPath().equals(op.getPath())
                   && Arrays.equals(data, op.data);
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode() + Arrays.hashCode(data) + version;
        }

        @Override
        public Record toRequestRecord() {
            return new SetDataRequest(getPath(), data, version);
        }

        @Override
        Op withChroot(String path) {
            return new SetData(path, data, version);
        }

    }

    public static class Check extends Op {

        private int version;

        private Check(String path, int version) {
            super(ZooDefs.OpCode.check, path, OpKind.TRANSACTION);
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Check)) {
                return false;
            }

            Check op = (Check) o;

            return getType() == op.getType() && getPath().equals(op.getPath()) && version == op.version;
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode() + version;
        }

        @Override
        public Record toRequestRecord() {
            return new CheckVersionRequest(getPath(), version);
        }

        @Override
        Op withChroot(String path) {
            return new Check(path, version);
        }

    }

    public static class GetChildren extends Op {

        GetChildren(String path) {
            super(ZooDefs.OpCode.getChildren, path, OpKind.READ);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GetChildren)) {
                return false;
            }

            GetChildren op = (GetChildren) o;

            return getType() == op.getType() && getPath().equals(op.getPath());
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode();
        }

        @Override
        public Record toRequestRecord() {
            return new GetChildrenRequest(getPath(), false);
        }

        @Override
        Op withChroot(String path) {
            return new GetChildren(path);
        }

    }

    public static class GetData extends Op {

        GetData(String path) {
            super(ZooDefs.OpCode.getData, path, OpKind.READ);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GetData)) {
                return false;
            }

            GetData op = (GetData) o;

            return getType() == op.getType() && getPath().equals(op.getPath());
        }

        @Override
        public int hashCode() {
            return getType() + getPath().hashCode();
        }

        @Override
        public Record toRequestRecord() {
            return new GetDataRequest(getPath(), false);
        }

        @Override
        Op withChroot(String path) {
            return new GetData(path);
        }

    }

}
