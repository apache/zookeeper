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
		private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(ZNodeName));

		public ZNodeName(string name)
		{
			if (name == null)
			{
				throw new System.NullReferenceException("id cannot be null");
			}
			this.name = name;
			this.prefix = name;
			int idx = name.LastIndexOf('-');
			if (idx >= 0)
			{
				this.prefix = name.Substring(0, idx);
				try
				{
					this.sequence = int.Parse(name.Substring(idx + 1));
					// If an exception occurred we misdetected a sequence suffix,
					// so return -1.
				}
				catch (System.FormatException e)
				{
					LOG.info("Number format exception for " + idx, e);
				}
				catch (System.IndexOutOfRangeException e)
				{
				   LOG.info("Array out of bounds for " + idx, e);
				}
			}
		}

		public override string ToString()
		{
			return name.ToString();
		}

		public override bool Equals(object o)
		{
			if (this == o)
			{
				return true;
			}
			if (o == null || this.GetType() != o.GetType())
			{
				return false;
			}

			ZNodeName seq = (ZNodeName) o;

			if (!name.Equals(seq.name))
			{
				return false;
			}

			return true;
		}

		public override int GetHashCode()
		{
			return name.GetHashCode() + 37;
		}

		public int CompareTo(ZNodeName that)
		{
			int answer = this.prefix.CompareTo(that.prefix);
			if (answer == 0)
			{
				int s1 = this.sequence;
				int s2 = that.sequence;
				if (s1 == -1 && s2 == -1)
				{
					return this.name.CompareTo(that.name);
				}
				answer = s1 == -1 ? 1 : s2 == -1 ? - 1 : s1 - s2;
			}
			return answer;
		}

		/// <summary>
		/// Returns the name of the znode
		/// </summary>
		public string Name
		{
			get
			{
				return name;
			}
		}

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
		public string Prefix
		{
			get
			{
				return prefix;
			}
		}
	}

}