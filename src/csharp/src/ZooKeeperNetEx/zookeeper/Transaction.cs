using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using org.apache.zookeeper.data;

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
	/// Provides a builder style interface for doing multiple updates. This is
	/// really just a thin layer on top of <see cref="ZooKeeper.multiAsync"/>.
	/// 
	/// @since 3.4.0
	/// 
	/// </summary>
	public class Transaction
	{
		private readonly ZooKeeper zk;
		private readonly List<Op> ops = new List<Op>();

	    internal Transaction(ZooKeeper zk)
		{
			this.zk = zk;
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
        ///     and/or sequential.
        /// </param>
		public Transaction create(string path, byte[] data, List<ACL> acl, CreateMode createMode)
		{
            if (createMode == null) throw new ArgumentNullException(nameof(createMode));
            ops.Add(Op.create(path, data, acl, createMode.toFlag()));
			return this;
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
		public Transaction delete(string path, int version = -1)
		{
			ops.Add(Op.delete(path, version));
			return this;
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
		public Transaction check(string path, int version)
		{
			ops.Add(Op.check(path, version));
			return this;
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
        public Transaction setData(string path, byte[] data, int version = -1)
		{
			ops.Add(Op.setData(path, data, version));
			return this;
		}

        /// <summary>
        /// Commits the transaction.
        /// </summary>
        /// <returns>the results of each op</returns>
        public Task<List<OpResult>> commitAsync()
        {
            return zk.multiAsync(ops);
        }
	}

}