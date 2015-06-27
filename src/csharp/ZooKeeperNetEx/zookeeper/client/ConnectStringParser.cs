using System;
using System.Collections.Generic;
using System.Net;
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

namespace org.apache.zookeeper.client
{
    /// <summary>
	/// A parser for ZooKeeper Client connect strings.
	/// 
	/// This class is not meant to be seen or used outside of ZooKeeper itself.
	/// 
	/// The chrootPath member should be replaced by a Path object in issue
	/// ZOOKEEPER-849.
	/// </summary>
    internal sealed class ConnectStringParser
	{
		private const int DEFAULT_PORT = 2181;

		private readonly string chrootPath;

        private readonly List<DnsEndPoint> serverAddresses = new List<DnsEndPoint>();

        private static readonly char[] splitter = {','};

		public ConnectStringParser(string connectString)
		{
			// parse out chroot, if any
			int off = connectString.IndexOf('/');
			if (off >= 0)
			{
				string chPath = connectString.Substring(off);
				// ignore "/" chroot spec, same as null
				if (chPath.Length == 1)
				{
					chrootPath = null;
				}
				else
				{
					PathUtils.validatePath(chPath);
					chrootPath = chPath;
				}
				connectString = connectString.Substring(0, off);
			}
			else
			{
				chrootPath = null;
			}

		    string[] hostsList = connectString.Split(splitter, StringSplitOptions.RemoveEmptyEntries);
			foreach (string host in hostsList)
			{
				int port = DEFAULT_PORT;
				int pidx = host.LastIndexOf(':');
			    string parsedHost = host;
				if (pidx >= 0)
				{
					// otherwise : is at the end of the string, ignore
					if (pidx < host.Length - 1)
					{
						port = int.Parse(host.Substring(pidx + 1));
					}
                    parsedHost = host.Substring(0, pidx);
				}
			    serverAddresses.Add(new DnsEndPoint(parsedHost, port));
			}
		}

        public string getChrootPath()
        {
            return chrootPath;
        }

        public List<DnsEndPoint> getServerAddresses()
        {
                return serverAddresses;
        }
	}
}