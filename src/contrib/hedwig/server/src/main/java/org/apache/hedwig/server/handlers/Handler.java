/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.handlers;

import org.jboss.netty.channel.Channel;

import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;

public interface Handler {
    
    /**
     * Handle a request synchronously or asynchronously. After handling the
     * request, the appropriate response should be written on the given channel
     * 
     * @param request
     *            The request to handle
     * 
     * @param channel
     *            The channel on which to write the response
     */
    public void handleRequest(final PubSubRequest request, final Channel channel);
}
