using System;

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
		public readonly string Name;
	    private readonly int sequence;

	    public ZNodeName(string id)
	    {
            Name = id;
	        sequence = int.Parse(Name.Substring(Name.LastIndexOf('-') + 1));
	    }

		public override string ToString()
		{
			return Name;
		}

		public override bool Equals(object o)
		{
			ZNodeName seq = (ZNodeName) o;

		    return Name == seq.Name;
		}

		public override int GetHashCode()
		{
			return Name.GetHashCode();
		}

	    public int CompareTo(ZNodeName that)
	    {
	        return sequence - that.sequence;
	    }
	}
}