using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using org.apache.utils;

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
    internal sealed class ClientCnxnSocketNIO : ClientCnxnSocket
	{
        private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(ClientCnxnSocketNIO));

	    private bool initialized;

		private Socket socket;


        private readonly ThreadSafeInt pendingIncomingDataNotifierState = new ThreadSafeInt(NOTRUNNING);
        //pendingIncomingDataNotifierState states
	    private const int RUNNING = 1;

	    private const int NOTRUNNING = 0;


        private readonly ThreadSafeInt connectingState = new ThreadSafeInt(BEFORECONNECTING);
        //connectingState states
        private const int BEFORECONNECTING = 0;

        private const int CONNECTING = 1;

        private const int PENDINGCONNECTASYNC = 2;

        private const int CONNECTDONE = 3;

        
        readonly AsyncManualResetEvent somethingIsPending = new AsyncManualResetEvent();

	    private readonly Fenced<bool> readEnabled = new Fenced<bool>(false);

        private readonly Fenced<bool> writeEnabled = new Fenced<bool>(false);
        
	    private readonly SocketAsyncEventArgs receiveEventArgs;

        private readonly SocketAsyncEventArgs connectEventArgs;

        internal ClientCnxnSocketNIO(ClientCnxn cnxn) : base(cnxn) 
        {
            receiveEventArgs = new SocketAsyncEventArgs();
            receiveEventArgs.SetBuffer(new byte[0], 0, 0);
            receiveEventArgs.Completed += delegate { ReceiveCompleted(); };
            connectEventArgs = new SocketAsyncEventArgs();
            connectEventArgs.Completed += delegate { ConnectAsyncCompleted(); };
        }

        void ConnectAsyncCompleted() {
	        connectingState.SetValue(CONNECTING, PENDINGCONNECTASYNC);
            wakeupCnxn();
	    }

        void ReceiveCompleted()
        {
            pendingIncomingDataNotifierState.SetValue(RUNNING, NOTRUNNING);
            wakeupCnxn();
        }


	    internal override bool isConnected() {
	        return socket != null;
	    }

        private void doIO()
		{
			var localSock = socket;
			if (localSock == null)
			{
				throw new IOException("Socket is null!");
			}
			if (Readable)
			{
			    try
			    {
			        localSock.read(incomingBuffer);
			    }
				catch(Exception e)
				{
					throw new EndOfStreamException("Unable to read additional data from server sessionid 0x" + sessionId.ToString("x") + ", likely server has closed socket",e);
				}
				if (!incomingBuffer.hasRemaining())
				{
					incomingBuffer.flip();
					if (incomingBuffer == lenBuffer)
					{
						recvCount++;
						readLength();
					}
					else if (!initialized)
					{
						readConnectResult();
						enableRead();
                        if (findSendablePacket() != null)
						{
							enableWrite();
						}
						lenBuffer.clear();
						incomingBuffer = lenBuffer;
						updateLastHeard();
						initialized = true;
					}
					else
					{
						clientCnxn.readResponse(incomingBuffer);
						lenBuffer.clear();
						incomingBuffer = lenBuffer;
						updateLastHeard();
					}
				}
			}
			if (Writable)
			{
                lock (clientCnxn.outgoingQueue)
				{
                    var pNode = findSendablePacket();
				    var p = pNode.Value;
					if (p != null)
					{
						updateLastSend();
						// If we already started writing p, p.bb will already exist
						if (p.bb == null)
						{
							if ((p.requestHeader != null) && (p.requestHeader.get_Type() != (int) ZooDefs.OpCode.ping) && (p.requestHeader.get_Type() != (int) ZooDefs.OpCode.auth)) {
                                p.requestHeader.setXid(clientCnxn.getXid());
							}
							p.createBB();
						}
						localSock.write(p.bb);
						if (!p.bb.hasRemaining())
						{
							sentCount++;
                            clientCnxn.outgoingQueue.Remove(pNode);
							if (p.requestHeader != null && p.requestHeader.get_Type() != (int) ZooDefs.OpCode.ping && p.requestHeader.get_Type() != (int) ZooDefs.OpCode.auth)
							{
                                lock (clientCnxn.pendingQueue)
								{
                                    clientCnxn.pendingQueue.AddLast(p);
								}
							}
						}
					}
                    if (clientCnxn.outgoingQueue.Count == 0)
					{
						// No more packets to send: turn off write interest flag.
						// Will be turned on later by a later call to enableWrite(),
						// from within ZooKeeperSaslClient (if client is configured
						// to attempt SASL authentication), or in either doIO() or
						// in doTransport() if not.
						disableWrite();
					}
					else if (!initialized && p != null && !p.bb.hasRemaining())
					{
						// On initial connection, write the complete connect request
						// packet, but then disable further writes until after
						// receiving a successful connection response.  If the
						// session is expired, then the server sends the expiration
						// response and immediately closes its end of the socket.  If
						// the client is simultaneously writing on its end, then the
						// TCP stack may choose to abort with RST, in which case the
						// client would never receive the session expired event.  See
						// http://docs.oracle.com/javase/6/docs/technotes/guides/net/articles/connection_release.html
						disableWrite();
					}
					else
					{
						// Just in case
						enableWrite();
					}
				}
			}
		}

	   
        
	    private LinkedListNode<ClientCnxn.Packet> findSendablePacket()
		{
            lock (clientCnxn.outgoingQueue)
			{
                if (clientCnxn.outgoingQueue.Count == 0)
                {
                    return null;
                }
                return clientCnxn.outgoingQueue.First;
			}
		}

		internal override async Task cleanup()
		{
		    readEnabled.Value = false;
		    writeEnabled.Value = false;
			if (socket != null)
			{
				try
				{
                    if(socket.Connected)
                        socket.Shutdown(SocketShutdown.Receive);
				}
				catch (Exception e)
				{
					if (LOG.isDebugEnabled())
					{
						LOG.debug("Ignoring exception during shutdown input", e);
					}
				}
				try
				{
                    if (socket.Connected)
                        socket.Shutdown(SocketShutdown.Send);
				}
				catch (Exception e)
				{
					if (LOG.isDebugEnabled())
					{
						LOG.debug("Ignoring exception during shutdown output", e);
					}
				}
				try
				{
                    socket.Dispose();
				}
				catch (Exception e)
				{
					if (LOG.isDebugEnabled())
					{
                        LOG.debug("Ignoring exception during sockKey close", e);
					}
				}
			}
		    await Task.Delay(100).ConfigureAwait(false);
            wakeupCnxn();
			socket = null;
            connectingState.Value = BEFORECONNECTING;
		}


	    /// <summary>
	    /// create a socket channel. </summary>
	    /// <returns> the created socket channel </returns>
	    private static Socket createSock()
	    {
			Socket sock=new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
			sock.Blocking = false;
			sock.LingerState = new LingerOption(false, 0);
			sock.NoDelay=true;
			return sock;
	    }

	    /// <summary>
		/// register with the selection and connect </summary>
        /// <param name="sock"> the <seealso cref="Socket"/> </param>
		/// <param name="addr"> the address of remote host </param>
        private void registerAndConnect(Socket sock, DnsEndPoint addr)
		{
		    socket = sock;
	        connectEventArgs.RemoteEndPoint = addr;
	        connectingState.SetValue(BEFORECONNECTING, CONNECTING);
	        bool isPending = sock.ConnectAsync(connectEventArgs);
            if (!isPending) {
                connectingState.SetValue(CONNECTING, CONNECTDONE);
	            clientCnxn.primeConnection();
	        }
		}

        internal override void connect(DnsEndPoint addr)
		{
			Socket sock = createSock();
            
			try
			{
			   registerAndConnect(sock, addr);
			}
			catch (Exception e)
			{
			    LOG.error("Unable to open socket to " + addr, e);
                sock.Dispose();
				throw;
			}
			initialized = false;

			/*
			 * Reset incomingBuffer
			 */
			lenBuffer.clear();
			incomingBuffer = lenBuffer;
		}

        /// <summary>
        /// Returns the address to which the socket is connected.
        /// </summary>
        /// <returns> ip address of the remote side of the connection or null if not
        ///         connected </returns>
	    internal override EndPoint getRemoteSocketAddress() {
            // a lot could go wrong here, so rather than put in a bunch of code
            // to check for nulls all down the chain let's do it the simple
            // yet bulletproof way
            try
            {
                return connectEventArgs.RemoteEndPoint;
            }
            catch (NullReferenceException)
            {
                return null;
            }
	    }

		/// <summary>
		/// Returns the local address to which the socket is bound.
		/// </summary>
		/// <returns> ip address of the remote side of the connection or null if not
		///         connected </returns>
		internal override EndPoint getLocalSocketAddress()
		{
				// a lot could go wrong here, so rather than put in a bunch of code
				// to check for nulls all down the chain let's do it the simple
				// yet bulletproof way
				try
				{
                    return socket.LocalEndPoint;
				}
				catch (NullReferenceException)
				{
					return null;
				}
		}

		internal override void wakeupCnxn()
		{
            somethingIsPending.Set();
		}

        internal override async Task doTransport(int waitTimeOut) 
        {
            await Task.WhenAny(somethingIsPending.WaitAsync(), Task.Delay(waitTimeOut < 0 ? 0 : waitTimeOut)).ConfigureAwait(false);
            somethingIsPending.Reset();

            // Everything below and until we get back to the select is
			// non blocking, so time is effectively a constant. That is
			// Why we just have to do this once, here
			updateNow();

            if (Connectable)
	        {
	            if (connectEventArgs.SocketError == SocketError.Success)
	            {
	                updateLastSendAndHeard();
	                clientCnxn.primeConnection();
	            }
	            else throw new SocketException((int) connectEventArgs.SocketError);
	        }
	        while (Readable || Writable)
	        {
                doIO();
	        }

            if (pendingIncomingDataNotifierState.TrySetValue(NOTRUNNING, RUNNING))
            {
                if (socket.ReceiveAsync(receiveEventArgs) == false)
                {
                    ReceiveCompleted();
                }
            }

	        if (clientCnxn.getState().isConnected())
			{
                lock (clientCnxn.outgoingQueue)
				{
                    if (findSendablePacket() != null)
				    {
						enableWrite();
				    }
				}
			}
		}

        private bool isConnectDone() {
            return connectingState.Value == CONNECTDONE;
        }

        private void enableWrite()
        {
            writeEnabled.Value = true;
            if (isConnectDone()) wakeupCnxn();
        }

        private void disableWrite()
        {
            writeEnabled.Value = false;
        }

	    private void enableRead()
        {
            readEnabled.Value = true;
        }
	    
        internal override void enableReadWriteOnly()
        {
            enableRead();
            enableWrite();
        }
	
	    private bool Writable
	    {
	        get
	        {
	            try
	            {
                    return writeEnabled.Value && isConnectDone();
	            }
	            catch
	            {
	                return false;
	            }
	        }
	    }

	    private bool Readable
	    {
	        get
	        {
	            try
	            {
                    return socket.Available > 0 && readEnabled.Value && isConnectDone();
	            }
	            catch
	            {
	                return false;
	            }
	        }
	    }

	    private bool Connectable
	    {
            get { return connectingState.TrySetValue(PENDINGCONNECTASYNC, CONNECTDONE); }
	    }
	}

}