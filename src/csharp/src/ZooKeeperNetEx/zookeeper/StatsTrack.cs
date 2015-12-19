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
	/// a class that represents the stats associated with quotas
	/// </summary>
	public sealed class StatsTrack
	{
	    private const string countStr = "count";
	    private const string byteStr = "bytes";

	    /// <summary>
		/// a default constructor for
		/// stats
		/// </summary>
		public StatsTrack() : this(null)
		{
		}
		/// <summary>
		/// the stat string should be of the form count=int,bytes=long
		/// if stats is called with null the count and bytes are initialized
		/// to -1. </summary>
		/// <param name="stats"> the stat string to be initialized with </param>
		public StatsTrack(string stats)
		{
			if (stats == null)
			{
				stats = "count=-1,bytes=-1";
			}
			string[] split = stats.Split(',');
			if (split.Length != 2)
			{
				throw new System.ArgumentException("invalid string " + stats);
			}
		    Count = int.Parse(split[0].Split('=')[1]);
		    Bytes = long.Parse(split[1].Split('=')[1]);
		}


	    /// <summary>
	    /// get the count of nodes allowed as part of quota
	    /// </summary>
	    /// <returns> the count as part of this string </returns>
	    public readonly int Count;


	    /// <summary>
	    /// get the count of bytes allowed as part of quota
	    /// </summary>
	    /// <returns> the bytes as part of this string </returns>
	    public readonly long Bytes;


        /// <summary>
        /// returns the string that maps to this stat tracking.
        /// </summary>
        public override string ToString()
		{
			return countStr + "=" + Count + "," + byteStr + "=" + Bytes;
		}
	}

}