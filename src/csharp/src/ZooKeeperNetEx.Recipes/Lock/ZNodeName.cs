using System;

using org.apache.utils;

// 
// <summary>
// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
// 
// http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>
namespace org.apache.zookeeper.recipes.@lock
{
	/// <summary>
	/// Represents an ephemeral znode name which has an ordered sequence number
	/// and can be sorted in order
	/// 
	/// </summary>
	internal sealed class ZNodeName : IComparable<ZNodeName>
	{
		private readonly string name;
		private readonly string prefix;
		private readonly int sequence = -1;
		private static readonly ILogProducer LOG = TypeLogger<ZNodeName>.Instance;

		public ZNodeName(string id)
		{
			if (id == null)
			{
				throw new ArgumentNullException("id","id cannot be null");
			}
			name = id;
			prefix = name;
			int idx = name.LastIndexOf('-');
			if (idx >= 0)
			{
                prefix = name.Substring(0, idx);
				try
				{
                    sequence = int.Parse(name.Substring(idx + 1));
					// If an exception occurred we misdetected a sequence suffix,
					// so return -1.
				}
				catch (FormatException e)
				{
					LOG.info("Number format exception for " + idx, e);
				}
				catch (IndexOutOfRangeException e)
				{
				   LOG.info("Array out of bounds for " + idx, e);
				}
			}
		}

		public override string ToString()
		{
			return name;
		}

		public override bool Equals(object o)
		{
			ZNodeName seq = (ZNodeName) o;

		    return name == seq.name;
		}

		public override int GetHashCode()
		{
			return name.GetHashCode();
		}

		public int CompareTo(ZNodeName that) 
        {
		    if (that == null) throw new ArgumentNullException(nameof(that));
		    int answer = string.CompareOrdinal(prefix, that.prefix);
			if (answer == 0)
			{
				int s1 = sequence;
				int s2 = that.sequence;
				if (s1 == -1 && s2 == -1) 
                {
				    return string.CompareOrdinal(name, that.name);
				}
				answer = s1 == -1 ? 1 : s2 == -1 ? - 1 : s1 - s2;
			}
			return answer;
		}

		/// <summary>
		/// Returns the name of the znode
		/// </summary>
		public string Name => name;

	    /// <summary>
		/// Returns the sequence number
		/// </summary>
		public int getZNodeName()
		{
			return sequence;
		}

		/// <summary>
		/// Returns the text prefix before the sequence number
		/// </summary>
		public string Prefix => prefix;
	}

}