using System.Collections.Generic;
using System.Threading.Tasks;
using org.apache.utils;
using org.apache.zookeeper.common;

// <summary>
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//     http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>
namespace org.apache.zookeeper
{
    /// <summary>
    /// Some ZooKeeper Utilities
    /// </summary>
    public class ZKUtil
	{
        private ZKUtil(){}
        private static readonly ILogProducer LOG = TypeLogger<ZKUtil>.Instance;
		/// <summary>
		/// Recursively delete the node with the given path. 
		/// <para>
		/// Important: All versions, of all nodes, under the given node are deleted.
		/// </para>
		/// <para>
		/// If there is an error with deleting one of the sub-nodes in the tree, 
		/// this operation would abort and would be the responsibility of the app to handle the same.
		/// 
		/// </para>
		/// </summary>
		public static async Task deleteRecursiveAsync(ZooKeeper zk, string pathRoot)
		{
			PathUtils.validatePath(pathRoot);

			List<string> tree = await listSubTreeBFS(zk, pathRoot).ConfigureAwait(false);
			LOG.debug("Deleting " + tree);
			LOG.debug("Deleting " + tree.Count + " subnodes ");
            Transaction t = new Transaction(zk);
            
			for (int i = tree.Count - 1; i >= 0 ; --i)
			{
				//Delete the leaves first and eventually get rid of the root
				t.delete(tree[i]); //Delete all versions of the node with -1.
			}
		    await t.commitAsync().ConfigureAwait(false);
		}

		/// <summary>
		/// BFS Traversal of the system under pathRoot, with the entries in the list, in the 
		/// same order as that of the traversal.
		/// <para>
		/// <b>Important:</b> This is <i>not an atomic snapshot</i> of the tree ever, but the
		///  state as it exists across multiple RPCs from zkClient to the ensemble.
		/// For practical purposes, it is suggested to bring the clients to the ensemble 
		/// down (i.e. prevent writes to pathRoot) to 'simulate' a snapshot behavior.   
		/// 
		/// </para>
		/// </summary>
		/// <param name="zk"> the zookeeper handle </param>
		/// <param name="pathRoot"> The znode path, for which the entire subtree needs to be listed. </param>
		/// <exception cref="KeeperException">  </exception>
		public static async Task<List<string>> listSubTreeBFS(ZooKeeper zk, string pathRoot)
		{
            Queue<string> queue = new Queue<string>();
			List<string> tree = new List<string>();
			queue.Enqueue(pathRoot);
			tree.Add(pathRoot);
			while (true) {
			    string node;
			    if (queue.Count == 0) {
			        break;
			    }
			    node = queue.Dequeue();
			    IList<string> children = (await zk.getChildrenAsync(node).ConfigureAwait(false)).Children;
				foreach (string child in children)
				{
					string childPath = node + "/" + child;
					queue.Enqueue(childPath);
					tree.Add(childPath);
				}
			}
			return tree;
		}

	}
}