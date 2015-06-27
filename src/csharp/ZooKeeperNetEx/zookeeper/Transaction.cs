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
	/// Provides a builder style interface for doing multiple updates.  This is
	/// really just a thin layer on top of Zookeeper.multi().
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

		public Transaction create(string path, byte[] data, List<ACL> acl, CreateMode createMode)
		{
			ops.Add(Op.create(path, data, acl, createMode.toFlag()));
			return this;
		}

		public Transaction delete(string path, int version = -1)
		{
			ops.Add(Op.delete(path, version));
			return this;
		}

		public Transaction check(string path, int version)
		{
			ops.Add(Op.check(path, version));
			return this;
		}

		public Transaction setData(string path, byte[] data, int version = -1)
		{
			ops.Add(Op.setData(path, data, version));
			return this;
		}

        public Task<List<OpResult>> commitAsync()
        {
            return zk.multiAsync(ops);
        }
	}

}