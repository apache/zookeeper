/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
using ZooKeeperNet.IO;

namespace ZooKeeperNet
{
    using System.IO;
    using System.Text;
    using System.Threading;
    using log4net;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public class Packet
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(Packet));

        internal RequestHeader header;
        internal string serverPath;
        internal ReplyHeader replyHeader;
        internal IRecord response;
        private bool finished;
        internal ZooKeeper.WatchRegistration watchRegistration;
        internal readonly byte[] data;

        /** Client's view of the path (may differ due to chroot) **/
        internal string clientPath;
        /** Servers's view of the path (may differ due to chroot) **/
        readonly IRecord request;

        internal Packet(RequestHeader header, ReplyHeader replyHeader, IRecord request, IRecord response, byte[] data, ZooKeeper.WatchRegistration watchRegistration, string serverPath, string clientPath)
        {
            this.header = header;
            this.replyHeader = replyHeader;
            this.request = request;
            this.response = response;
            this.serverPath = serverPath;
            this.clientPath = clientPath;
            if (data != null)
            {
                this.data = data;
            } 
            else
            {
                try
                {
                    using (MemoryStream ms = new MemoryStream())
                    using (EndianBinaryWriter writer = new EndianBinaryWriter(EndianBitConverter.Big, ms, Encoding.UTF8))
                    {
                        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);
                        boa.WriteInt(-1, "len"); // We'll fill this in later
                        header.Serialize(boa, "header");
                        if (request != null)
                        {
                            request.Serialize(boa, "request");
                        }
                        ms.Position = 0;
                        writer.Write(ms.ToArray().Length - 4);
                        this.data = ms.ToArray();
                    }
                }
                catch (IOException e)
                {
                    LOG.Warn("Ignoring unexpected exception", e);
                }
            }
            this.watchRegistration = watchRegistration;
        }

        internal bool Finished
        { 
            get
            {
                lock (this)
                {
                    return finished;
                }
            }
            set
            {
                lock (this)
                {
                    Monitor.Enter(this);
                    finished = value;
                    Monitor.PulseAll(this);
                    Monitor.Exit(this);
                }
            }
        }

        public override string ToString()
        {
            StringBuilder sb = new StringBuilder();

            sb.Append("clientPath:" + clientPath);
            sb.Append(" serverPath:" + serverPath);
            sb.Append(" finished:" + finished);

            sb.Append(" header:: " + header);
            sb.Append(" replyHeader:: " + replyHeader);
            sb.Append(" request:: " + request);
            sb.Append(" response:: " + response);

            // jute toString is horrible, remove unnecessary newlines
            return sb.ToString().Replace(@"\r*\n+", " ");
        }
    }
}
