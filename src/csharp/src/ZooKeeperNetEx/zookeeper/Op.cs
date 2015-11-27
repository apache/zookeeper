using System;
using System.Collections.Generic;
using org.apache.jute;
using org.apache.zookeeper.common;
using org.apache.zookeeper.data;
using org.apache.zookeeper.proto;

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

namespace org.apache.zookeeper
{
    /// <summary>
    ///     Represents a single operation in a multi-operation transaction.  Each operation can be a create, update
    ///     or delete or can just be a version check.
    ///     Sub-classes of Op each represent each detailed type but should not normally be referenced except via
    ///     the provided factory methods.
    /// </summary>
    public abstract class Op {
        private readonly string path;
        private readonly int type;
        // prevent untyped construction
        private Op(ZooDefs.OpCode type, string path) {
            this.type = (int) type;
            this.path = path;
        }
        /// <summary>
        ///     Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
        /// </summary>
        /// <param name="path">
        ///     the path for the node
        /// </param>
        /// <param name="data">
        ///     the initial data for the node
        /// </param>
        /// <param name="acl">
        ///     the acl for the node
        /// </param>
        /// <param name="flags">
        ///     specifying whether the node to be created is ephemeral
        ///     and/or sequential but using the integer encoding.
        /// </param>
        internal static Op create(string path, byte[] data, List<ACL> acl, int flags) {
            return new Create(path, data, acl, flags);
        }

        /// <summary>
        ///     Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
        /// </summary>
        /// <param name="path">
        ///     the path for the node
        /// </param>
        /// <param name="data">
        ///     the initial data for the node
        /// </param>
        /// <param name="acl">
        ///     the acl for the node
        /// </param>
        /// <param name="createMode">
        ///     specifying whether the node to be created is ephemeral
        ///     and/or sequential
        /// </param>
        public static Op create(string path, byte[] data, List<ACL> acl, CreateMode createMode) {
            return new Create(path, data, acl, createMode);
        }

        /// <summary>
        ///     Constructs a delete operation.  Arguments are as for the ZooKeeper method of the same name.
        /// </summary>
        /// <param name="path">
        ///     the path of the node to be deleted.
        /// </param>
        /// <param name="version">
        ///     the expected node version.
        /// </param>
        public static Op delete(string path, int version = -1) {
            return new Delete(path, version);
        }

        /// <summary>
        ///     Constructs an update operation.  Arguments are as for the ZooKeeper method of the same name.
        /// </summary>
        /// <param name="path">
        ///     the path of the node
        /// </param>
        /// <param name="data">
        ///     the data to set
        /// </param>
        /// <param name="version">
        ///     the expected matching version
        /// </param>
        public static Op setData(string path, byte[] data, int version = -1) {
            return new SetData(path, data, version);
        }

        /// <summary>
        ///     Constructs an version check operation.  Arguments are as for the ZooKeeper.setData method except that
        ///     no data is provided since no update is intended.  The purpose for this is to allow read-modify-write
        ///     operations that apply to multiple znodes, but where some of the znodes are involved only in the read,
        ///     not the write.  A similar effect could be achieved by writing the same data back, but that leads to
        ///     way more version updates than are necessary and more writing in general.
        /// </summary>
        /// <param name="path">
        ///     the path of the node
        /// </param>
        /// <param name="version">
        ///     the expected matching version
        /// </param>
        public static Op check(string path, int version) {
            return new Check(path, version);
        }

        /**
         * Gets the integer type code for an Op.  This code should be as from ZooDefs.OpCode
         * @see ZooDefs.OpCode
         * @return  The type code.
         */
        public int get_Type() {
            return type;
        }

        /**
         * Gets the path for an Op.
         * @return  The path.
         */
        public string getPath() {
            return path;
        }
        ///     Encodes an op for wire transmission.
        /// <returns> An appropriate Record structure. </returns>
        internal abstract Record toRequestRecord();

        /// <summary>
        ///     Reconstructs the transaction with the chroot prefix.
        /// </summary>
        /// <returns> transaction with chroot. </returns>
        public abstract Op withChroot(string addRootPrefix);

        /// <summary>
        ///     Performs client path validations.
        /// </summary>
        /// <exception cref="InvalidOperationException">
        ///     if an invalid path is specified
        /// </exception>
        /// <exception cref="KeeperException.BadArgumentsException">
        ///     if an invalid create mode flag is specified
        /// </exception>
        internal virtual void validate() {
            PathUtils.validatePath(path);
        }
        private class Create : Op {
            private readonly List<ACL> acl;
            private readonly byte[] data;
            private readonly int flags;

            internal Create(string path, byte[] data, List<ACL> acl, int flags)
                : base(ZooDefs.OpCode.create, path) {
                this.data = data;
                this.acl = acl;
                this.flags = flags;
            }

            internal Create(string path, byte[] data, List<ACL> acl, CreateMode createMode)
                : base(ZooDefs.OpCode.create, path) {
                this.data = data;
                this.acl = acl;
                flags = createMode.toFlag();
            }

            internal override Record toRequestRecord() {
                return new CreateRequest(getPath(), data, acl, flags);
            }

            public override Op withChroot(string p) {
                return new Create(p, data, acl, flags);
            }

            internal override void validate() {
                CreateMode createMode = CreateMode.fromFlag(flags);
                PathUtils.validatePath(getPath(), createMode.isSequential());
            }
        }

        private class Delete : Op {
            private readonly int version;

            internal Delete(string path, int version) : base(ZooDefs.OpCode.delete, path) {
                this.version = version;
            }

            internal override Record toRequestRecord() {
                return new DeleteRequest(getPath(), version);
            }

            public override Op withChroot(string p) {
                return new Delete(p, version);
            }
        }

        private class SetData : Op {
            private readonly byte[] data;
            private readonly int version;

            internal SetData(string path, byte[] data, int version) : base(ZooDefs.OpCode.setData, path) {
                this.data = data;
                this.version = version;
            }

            internal override Record toRequestRecord() {
                return new SetDataRequest(getPath(), data, version);
            }

            public override Op withChroot(string p) {
                return new SetData(p, data, version);
            }
        }

        private class Check : Op {
            private readonly int version;

            internal Check(string path, int version) : base(ZooDefs.OpCode.check, path) {
                this.version = version;
            }

            internal override Record toRequestRecord() {
                return new CheckVersionRequest(getPath(), version);
            }

            public override Op withChroot(string p) {
                return new Check(p, version);
            }
        }
    }
}