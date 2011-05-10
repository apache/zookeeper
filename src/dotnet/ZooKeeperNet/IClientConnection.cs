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
namespace ZooKeeperNet
{
    using System;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public interface IClientConnection : IStartable, IDisposable
    {
        /// <summary>
        /// Gets or sets the session timeout.
        /// </summary>
        /// <value>The session timeout.</value>
        TimeSpan SessionTimeout { get; }

        /// <summary>
        /// Gets or sets the session password.
        /// </summary>
        /// <value>The session password.</value>
        byte[] SessionPassword { get; }

        /// <summary>
        /// Gets or sets the session id.
        /// </summary>
        /// <value>The session id.</value>
        long SessionId { get; }

        /// <summary>
        /// Gets or sets the chroot path.
        /// </summary>
        /// <value>The chroot path.</value>
        string ChrootPath { get; }

        /// <summary>
        /// Adds the auth info.
        /// </summary>
        /// <param name="scheme">The scheme.</param>
        /// <param name="auth">The auth.</param>
        void AddAuthInfo(string scheme, byte[] auth);

        /// <summary>
        /// Submits the request.
        /// </summary>
        /// <param name="h">The request header.</param>
        /// <param name="request">The request.</param>
        /// <param name="response">The response.</param>
        /// <param name="watchRegistration">The watch registration.</param>
        /// <returns></returns>
        ReplyHeader SubmitRequest(RequestHeader h, IRecord request, IRecord response, ZooKeeper.WatchRegistration watchRegistration);

        /// <summary>
        /// Queues the packet.
        /// </summary>
        /// <param name="h">The request header.</param>
        /// <param name="r">The reply header.</param>
        /// <param name="request">The request.</param>
        /// <param name="response">The response.</param>
        /// <param name="clientPath">The client path.</param>
        /// <param name="serverPath">The server path.</param>
        /// <param name="watchRegistration">The watch registration.</param>
        /// <param name="callback">The callback.</param>
        /// <param name="ctx">The context.</param>
        /// <returns></returns>
        Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, string clientPath, string serverPath, ZooKeeper.WatchRegistration watchRegistration, object callback, object ctx);
    }
}
