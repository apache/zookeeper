using System.IO;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using org.apache.jute;
using org.apache.utils;
using org.apache.zookeeper.proto;

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
    /// A ClientCnxnSocket does the lower level communication with a socket
    /// implementation.
    /// 
    /// This code has been moved out of ClientCnxn so that a Netty implementation can
    /// be provided as an alternative to the NIO socket code.
    /// 
    /// </summary>
    internal abstract class ClientCnxnSocket {
        private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(ClientCnxnSocket));

        //This buffer is only used to read the length of the incoming message.
        protected readonly ByteBuffer lenBuffer = ByteBuffer.allocate(4);
        
        //This is the current incoming data buffer
        protected ByteBuffer incomingBuffer;

        // This buffer is only used to read the incoming message contents (not the length) 
        private readonly ByteBuffer messageBuffer = ByteBuffer.allocate(256);

        protected ClientCnxnSocket(ClientCnxn cnxn) {
            incomingBuffer = lenBuffer;
            clientCnxn = cnxn;
        }

        protected long sentCount;
        protected long recvCount;
        private long lastHeard;
        private long lastSend;
        private long now;
        protected readonly ClientCnxn clientCnxn;

        /// <summary>
        /// The sessionId is only available here for Log and Exception messages.
        /// Otherwise the socket doesn't need to know it.
        /// </summary>
        protected internal long sessionId;

        internal void introduce(long sessionid) {
            sessionId = sessionid;
        }

        internal void updateNow() {
            now = TimeHelper.ElapsedMiliseconds;
        }

        internal int getIdleRecv() {
            return (int) (now - lastHeard);
        }

        internal int getIdleSend() {
            return (int) (now - lastSend);
        }

        internal long getSentCount() {
            return sentCount;
        }

        internal long getRecvCount() {
            return recvCount;
        }

        internal void updateLastHeard() {
            lastHeard = now;
        }

        internal void updateLastSend() {
            lastSend = now;
        }

        internal void updateLastSendAndHeard() {
            lastSend = now;
            lastHeard = now;
        }

        protected void readLength() {
            int len = new BigEndianBinaryReader(incomingBuffer.Stream).ReadInt32();

            if (len < 0 || len >= ClientCnxn.packetLen) {
                throw new IOException("Packet len" + len + " is out of range!");
            }
            messageBuffer.clear();
            messageBuffer.Stream.SetLength(len);
            messageBuffer.Stream.Capacity = len;

            incomingBuffer = messageBuffer;
        }

        internal void readConnectResult() {
            if (LOG.isDebugEnabled()) {
                StringBuilder buf = new StringBuilder("0x[");
                foreach (byte b in incomingBuffer.Stream.ToArray()){
                    buf.Append(b.ToHexString() + ",");
                }
                buf.Append("]");
                LOG.debug("readConnectResult " + incomingBuffer.remaining() + " " + buf);
            }

            BigEndianBinaryReader bebr = new BigEndianBinaryReader(incomingBuffer.Stream);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bebr);
            ConnectResponse conRsp = new ConnectResponse();
            ((Record) conRsp).deserialize(bbia, "connect");

            // read "is read-only" flag
            bool isRO = false;
            try {
                isRO = bbia.readBool("readOnly");
            }
            catch (IOException) {
                // this is ok -- just a packet from an old server which
                // doesn't contain readOnly field
                LOG.warn("Connected to an old server; r-o mode will be unavailable");
            }

            sessionId = conRsp.getSessionId();
            clientCnxn.onConnected(conRsp.getTimeOut(), sessionId, conRsp.getPasswd(), isRO);
        }

        internal abstract bool isConnected();

        internal abstract void connect(DnsEndPoint addr);

        internal abstract EndPoint getRemoteSocketAddress();

        internal abstract EndPoint getLocalSocketAddress();

        internal abstract Task cleanup();

        internal abstract void wakeupCnxn();

        internal abstract void enableReadWriteOnly();

        internal abstract Task doTransport(int to);

        internal virtual void close() {
            lenBuffer.Stream.Dispose();
            messageBuffer.Stream.Dispose();
        }
    }
}