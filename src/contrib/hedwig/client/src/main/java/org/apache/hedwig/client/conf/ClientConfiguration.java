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
package org.apache.hedwig.client.conf;

import java.net.InetSocketAddress;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.log4j.Logger;

import org.apache.hedwig.conf.AbstractConfiguration;
import org.apache.hedwig.util.HedwigSocketAddress;

public class ClientConfiguration extends AbstractConfiguration {
    Logger logger = Logger.getLogger(ClientConfiguration.class);

    // Protected member variables for configuration parameter names
    protected static final String DEFAULT_SERVER_HOST = "default_server_host";
    protected static final String MAX_MESSAGE_SIZE = "max_message_size";
    protected static final String MAX_SERVER_REDIRECTS = "max_server_redirects";
    protected static final String AUTO_SEND_CONSUME_MESSAGE_ENABLED = "auto_send_consume_message_enabled";
    protected static final String CONSUMED_MESSAGES_BUFFER_SIZE = "consumed_messages_buffer_size";
    protected static final String MESSAGE_CONSUME_RETRY_WAIT_TIME = "message_consume_retry_wait_time";
    protected static final String SUBSCRIBE_RECONNECT_RETRY_WAIT_TIME = "subscribe_reconnect_retry_wait_time";
    protected static final String MAX_OUTSTANDING_MESSAGES = "max_outstanding_messages";
    protected static final String SERVER_ACK_RESPONSE_TIMEOUT = "server_ack_response_timeout";
    protected static final String TIMEOUT_THREAD_RUN_INTERVAL = "timeout_thread_run_interval";
    protected static final String SSL_ENABLED = "ssl_enabled";

    // Singletons we want to instantiate only once per ClientConfiguration
    protected HedwigSocketAddress myDefaultServerAddress = null;

    // Getters for the various Client Configuration parameters.
    // This should point to the default server host, or the VIP fronting all of
    // the server hubs. This will return the HedwigSocketAddress which
    // encapsulates both the regular and SSL port connection to the server host.
    protected HedwigSocketAddress getDefaultServerHedwigSocketAddress() {
        if (myDefaultServerAddress == null)
            myDefaultServerAddress = new HedwigSocketAddress(conf.getString(DEFAULT_SERVER_HOST, "localhost:4080:9876"));
        return myDefaultServerAddress;
    }

    // This will get the default server InetSocketAddress based on if SSL is
    // enabled or not.
    public InetSocketAddress getDefaultServerHost() {
        if (isSSLEnabled())
            return getDefaultServerHedwigSocketAddress().getSSLSocketAddress();
        else
            return getDefaultServerHedwigSocketAddress().getSocketAddress();
    }

    public int getMaximumMessageSize() {
        return conf.getInt(MAX_MESSAGE_SIZE, 2 * 1024 * 1024);
    }

    // This parameter is for setting the maximum number of server redirects to
    // allow before we consider it as an error condition. This is to stop
    // infinite redirect loops in case there is a problem with the hub servers
    // topic mastership.
    public int getMaximumServerRedirects() {
        return conf.getInt(MAX_SERVER_REDIRECTS, 2);
    }

    // This parameter is a boolean flag indicating if the client library should
    // automatically send the consume message to the server based on the
    // configured amount of messages consumed by the client app. The client app
    // could choose to override this behavior and instead, manually send the
    // consume message to the server via the client library using its own 
    // logic and policy.
    public boolean isAutoSendConsumeMessageEnabled() {
        return conf.getBoolean(AUTO_SEND_CONSUME_MESSAGE_ENABLED, true);
    }

    // This parameter is to set how many consumed messages we'll buffer up
    // before we send the Consume message to the server indicating that all
    // of the messages up to that point have been successfully consumed by
    // the client.
    public int getConsumedMessagesBufferSize() {
        return conf.getInt(CONSUMED_MESSAGES_BUFFER_SIZE, 5);
    }

    // This parameter is used to determine how long we wait before retrying the
    // client app's MessageHandler to consume a subscribed messages sent to us
    // from the server. The time to wait is in milliseconds.
    public long getMessageConsumeRetryWaitTime() {
        return conf.getLong(MESSAGE_CONSUME_RETRY_WAIT_TIME, 10000);
    }

    // This parameter is used to determine how long we wait before retrying the
    // Subscribe Reconnect request. This is done when the connection to a server
    // disconnects and we attempt to connect to it. We'll keep on trying but
    // in case the server(s) is down for a longer time, we want to throttle
    // how often we do the subscribe reconnect request. The time to wait is in
    // milliseconds.
    public long getSubscribeReconnectRetryWaitTime() {
        return conf.getLong(SUBSCRIBE_RECONNECT_RETRY_WAIT_TIME, 10000);
    }

    // This parameter is for setting the maximum number of outstanding messages
    // the client app can be consuming at a time for topic subscription before
    // we throttle things and stop reading from the Netty Channel.
    public int getMaximumOutstandingMessages() {
        return conf.getInt(MAX_OUTSTANDING_MESSAGES, 10);
    }

    // This parameter is used to determine how long we wait (in milliseconds)
    // before we time out outstanding PubSubRequests that were written to the
    // server successfully but haven't yet received the ack response.
    public long getServerAckResponseTimeout() {
        return conf.getLong(SERVER_ACK_RESPONSE_TIMEOUT, 30000);
    }

    // This parameter is used to determine how often we run the server ack
    // response timeout cleaner thread (in milliseconds).
    public long getTimeoutThreadRunInterval() {
        return conf.getLong(TIMEOUT_THREAD_RUN_INTERVAL, 60000);
    }

    // This parameter is a boolean flag indicating if communication with the
    // server should be done via SSL for encryption. This is needed for
    // cross-colo hub clients listening to non-local servers.
    public boolean isSSLEnabled() {
        return conf.getBoolean(SSL_ENABLED, false);
    }

    // Validate that the configuration properties are valid.
    public void validate() throws ConfigurationException {
        if (isSSLEnabled() && getDefaultServerHedwigSocketAddress().getSSLSocketAddress() == null) {
            throw new ConfigurationException("SSL is enabled but a default server SSL port not given!");
        }
        // Add other validation checks here
    }

}
