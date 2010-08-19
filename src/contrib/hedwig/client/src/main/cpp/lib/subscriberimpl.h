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
#ifndef SUBSCRIBE_IMPL_H
#define SUBSCRIBE_IMPL_H

#include <hedwig/subscribe.h>
#include <hedwig/callback.h>
#include "clientimpl.h"
#include <utility>
#include <tr1/memory>
#include <deque>

namespace Hedwig {
  class SubscriberWriteCallback : public OperationCallback {
  public:
    SubscriberWriteCallback(ClientImplPtr& client, const PubSubDataPtr& data);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    ClientImplPtr client;
    PubSubDataPtr data;
  };
  
  class UnsubscribeWriteCallback : public OperationCallback {
  public:
    UnsubscribeWriteCallback(ClientImplPtr& client, const PubSubDataPtr& data);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    ClientImplPtr client;
    PubSubDataPtr data;
  };

  class ConsumeWriteCallback : public OperationCallback {
  public:
    ConsumeWriteCallback(const PubSubDataPtr& data);
    ~ConsumeWriteCallback();

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    PubSubDataPtr data;
    };

  class SubscriberReconnectCallback : public OperationCallback {
  public: 
    SubscriberReconnectCallback(ClientImplPtr& client, const PubSubDataPtr& origData);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    ClientImplPtr client;
    PubSubDataPtr origData;
  };

  class SubscriberClientChannelHandler;
  typedef std::tr1::shared_ptr<SubscriberClientChannelHandler> SubscriberClientChannelHandlerPtr;

  class SubscriberConsumeCallback : public OperationCallback {
  public: 
    SubscriberConsumeCallback(ClientImplPtr& client, const std::string& topic, const std::string& subscriberid, const MessageSeqId& msgid);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    ClientImplPtr client;
    const std::string topic;
    const std::string subscriberid;
    MessageSeqId msgid;
  };

  class SubscriberClientChannelHandler : public HedwigClientChannelHandler {
  public: 
    SubscriberClientChannelHandler(ClientImplPtr& client, SubscriberImpl& subscriber, const PubSubDataPtr& data);
    ~SubscriberClientChannelHandler();

    void messageReceived(DuplexChannel* channel, const PubSubResponse& m);
    void channelDisconnected(DuplexChannel* channel, const std::exception& e);

    void startDelivery(const MessageHandlerCallbackPtr& handler);
    void stopDelivery();

    void handoverDelivery(SubscriberClientChannelHandler* newHandler);

    void setChannel(const DuplexChannelPtr& channel);
    DuplexChannelPtr& getChannel();

    void close();
  private:

    SubscriberImpl& subscriber;
#warning "put some limit on this to stop it growing forever"
    std::deque<Message> queue;
    MessageHandlerCallbackPtr handler;
    PubSubDataPtr origData;
    DuplexChannelPtr channel;
    bool closed;
  };

  class SubscriberImpl : public Subscriber {
  public:
    SubscriberImpl(ClientImplPtr& client);
    ~SubscriberImpl();

    void subscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode);
    void asyncSubscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode, const OperationCallbackPtr& callback);
    
    void unsubscribe(const std::string& topic, const std::string& subscriberId);
    void asyncUnsubscribe(const std::string& topic, const std::string& subscriberId, const OperationCallbackPtr& callback);

    void consume(const std::string& topic, const std::string& subscriberId, const MessageSeqId& messageSeqId);

    void startDelivery(const std::string& topic, const std::string& subscriberId, const MessageHandlerCallbackPtr& callback);
    void stopDelivery(const std::string& topic, const std::string& subscriberId);

    void closeSubscription(const std::string& topic, const std::string& subscriberId);

    void messageHandler(const PubSubResponse& m, const PubSubDataPtr& txn);

    void doSubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data, const SubscriberClientChannelHandlerPtr& handler);
    void doUnsubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data);

  private:
    ClientImplPtr client;
    
    std::tr1::unordered_map<TopicSubscriber, SubscriberClientChannelHandlerPtr> topicsubscriber2handler;
    Mutex topicsubscriber2handler_lock;	    
  };

};

#endif
