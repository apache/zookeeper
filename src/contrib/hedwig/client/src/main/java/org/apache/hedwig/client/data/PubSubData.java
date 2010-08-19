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
package org.apache.hedwig.client.data;

import java.util.List;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.util.Callback;

/**
 * Wrapper class to store all of the data points needed to encapsulate all
 * PubSub type of request operations the client will do. This includes knowing
 * all of the information needed if we need to redo the publish/subscribe
 * request in case of a server redirect. This will be used for all sync/async
 * calls, and for all the known types of request messages to send to the server
 * hubs: Publish, Subscribe, Unsubscribe, and Consume.
 * 
 */
public class PubSubData {
    // Static string constants
    protected static final String COMMA = ", ";

    // Member variables needed during object construction time.
    public final ByteString topic;
    public final Message msg;
    public final ByteString subscriberId;
    // Enum to indicate what type of operation this PubSub request data object
    // is for.
    public final OperationType operationType;
    // Enum for subscribe requests to indicate if this is a CREATE, ATTACH, or
    // CREATE_OR_ATTACH subscription request. For non-subscribe requests,
    // this will be null.
    public final CreateOrAttach createOrAttach;
    // These two variables are not final since we might override them
    // in the case of a Subscribe reconnect.
    public Callback<Void> callback;
    public Object context;

    // Member variables used after object has been constructed.
    // List of all servers we've sent the PubSubRequest to successfully.
    // This is to keep track of redirected servers that responded back to us.
    public List<ByteString> triedServers;
    // List of all servers that we've tried to connect or write to but
    // was unsuccessful. We'll retry sending the PubSubRequest but will
    // quit if we're trying to connect or write to a server that we've
    // attempted to previously.
    public List<ByteString> connectFailedServers;
    public List<ByteString> writeFailedServers;
    // Boolean to the hub server indicating if it should claim ownership
    // of the topic the PubSubRequest is for. This is mainly used after
    // a server redirect. Defaults to false.
    public boolean shouldClaim = false;
    // TxnID for the PubSubData if it was sent as a PubSubRequest to the hub
    // server. This is used in the WriteCallback in case of failure. We want
    // to remove it from the ResponseHandler.txn2PubSubData map since the
    // failed PubSubRequest will not get an ack response from the server.
    // This is set later in the PubSub flows only when we write the actual
    // request. Therefore it is not an argument in the constructor.
    public long txnId;
    // Time in milliseconds using the System.currentTimeMillis() call when the
    // PubSubRequest was written on the netty Channel to the server.
    public long requestWriteTime;
    // For synchronous calls, this variable is used to know when the background
    // async process for it has completed, set in the VoidCallback.
    public boolean isDone = false;

    // Constructor for all types of PubSub request data to send to the server
    public PubSubData(final ByteString topic, final Message msg, final ByteString subscriberId,
            final OperationType operationType, final CreateOrAttach createOrAttach, final Callback<Void> callback,
            final Object context) {
        this.topic = topic;
        this.msg = msg;
        this.subscriberId = subscriberId;
        this.operationType = operationType;
        this.createOrAttach = createOrAttach;
        this.callback = callback;
        this.context = context;
    }

    // Clear all of the stored servers we've contacted or attempted to in this
    // request.
    public void clearServersList() {
        if (triedServers != null)
            triedServers.clear();
        if (connectFailedServers != null)
            connectFailedServers.clear();
        if (writeFailedServers != null)
            writeFailedServers.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (topic != null)
            sb.append("Topic: " + topic.toStringUtf8());
        if (msg != null)
            sb.append(COMMA).append("Message: " + msg);
        if (subscriberId != null)
            sb.append(COMMA).append("SubscriberId: " + subscriberId.toStringUtf8());
        if (operationType != null)
            sb.append(COMMA).append("Operation Type: " + operationType.toString());
        if (createOrAttach != null)
            sb.append(COMMA).append("Create Or Attach: " + createOrAttach.toString());
        if (triedServers != null && triedServers.size() > 0) {
            sb.append(COMMA).append("Tried Servers: ");
            for (ByteString triedServer : triedServers) {
                sb.append(triedServer.toStringUtf8()).append(COMMA);
            }
        }
        if (connectFailedServers != null && connectFailedServers.size() > 0) {
            sb.append(COMMA).append("Connect Failed Servers: ");
            for (ByteString connectFailedServer : connectFailedServers) {
                sb.append(connectFailedServer.toStringUtf8()).append(COMMA);
            }
        }
        if (writeFailedServers != null && writeFailedServers.size() > 0) {
            sb.append(COMMA).append("Write Failed Servers: ");
            for (ByteString writeFailedServer : writeFailedServers) {
                sb.append(writeFailedServer.toStringUtf8()).append(COMMA);
            }
        }
        sb.append(COMMA).append("Should Claim: " + shouldClaim);
        if (txnId != 0)
            sb.append(COMMA).append("TxnID: " + txnId);
        if (requestWriteTime != 0)
            sb.append(COMMA).append("Request Write Time: " + requestWriteTime);
        sb.append(COMMA).append("Is Done: " + isDone);
        return sb.toString();
    }

}
