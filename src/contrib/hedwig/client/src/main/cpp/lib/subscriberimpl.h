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

#include <boost/shared_ptr.hpp>
#include <boost/enable_shared_from_this.hpp>
#include <boost/thread/shared_mutex.hpp>

namespace Hedwig {
  class SubscriberWriteCallback : public OperationCallback {
  public:
    SubscriberWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    const ClientImplPtr client;
    const PubSubDataPtr data;
  };
  
  class UnsubscribeWriteCallback : public OperationCallback {
  public:
    UnsubscribeWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    const ClientImplPtr client;
    const PubSubDataPtr data;
  };

  class ConsumeWriteCallback : public OperationCallback {
  public:
    ConsumeWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data);
    ~ConsumeWriteCallback();

    void operationComplete();
    void operationFailed(const std::exception& exception);
    
    static void timerComplete(const ClientImplPtr& client, const PubSubDataPtr& data, const boost::system::error_code& error);
  private:
    const ClientImplPtr client;
    const PubSubDataPtr data;
    };

  class SubscriberReconnectCallback : public OperationCallback {
  public: 
    SubscriberReconnectCallback(const ClientImplPtr& client, const PubSubDataPtr& origData);

    void operationComplete();
    void operationFailed(const std::exception& exception);
  private:
    const ClientImplPtr client;
    const PubSubDataPtr origData;
  };

  class SubscriberClientChannelHandler;
  typedef boost::shared_ptr<SubscriberClientChannelHandler> SubscriberClientChannelHandlerPtr;

  class SubscriberConsumeCallback : public OperationCallback {
  public: 
    SubscriberConsumeCallback(const ClientImplPtr& client, const SubscriberClientChannelHandlerPtr& handler, const PubSubDataPtr& data, const PubSubResponsePtr& m);

    void operationComplete();
    void operationFailed(const std::exception& exception);
    static void timerComplete(const SubscriberClientChannelHandlerPtr handler, 
			      const PubSubResponsePtr m, 
			      const boost::system::error_code& error);

  private:
    const ClientImplPtr client;
    const SubscriberClientChannelHandlerPtr handler;
    
    const PubSubDataPtr data;
    const PubSubResponsePtr m;
  };

  class SubscriberClientChannelHandler : public HedwigClientChannelHandler, 
					 public boost::enable_shared_from_this<SubscriberClientChannelHandler> {
  public: 
    SubscriberClientChannelHandler(const ClientImplPtr& client, SubscriberImpl& subscriber, const PubSubDataPtr& data);
    ~SubscriberClientChannelHandler();

    void messageReceived(const DuplexChannelPtr& channel, const PubSubResponsePtr& m);
    void channelDisconnected(const DuplexChannelPtr& channel, const std::exception& e);

    void startDelivery(const MessageHandlerCallbackPtr& handler);
    void stopDelivery();

    void handoverDelivery(const SubscriberClientChannelHandlerPtr& newHandler);

    void setChannel(const DuplexChannelPtr& channel);
    DuplexChannelPtr& getChannel();

    static void reconnectTimerComplete(const SubscriberClientChannelHandlerPtr handler, const DuplexChannelPtr channel, const std::exception e, 
				       const boost::system::error_code& error);

    void close();
  private:

    SubscriberImpl& subscriber;
    std::deque<PubSubResponsePtr> queue;
    
    MessageHandlerCallbackPtr handler;
    PubSubDataPtr origData;
    DuplexChannelPtr channel;
    bool closed;
    bool should_wait;
  };

  class SubscriberImpl : public Subscriber {
  public:
    SubscriberImpl(const ClientImplPtr& client);
    ~SubscriberImpl();

    void subscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode);
    void asyncSubscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode, const OperationCallbackPtr& callback);
    
    void unsubscribe(const std::string& topic, const std::string& subscriberId);
    void asyncUnsubscribe(const std::string& topic, const std::string& subscriberId, const OperationCallbackPtr& callback);

    void consume(const std::string& topic, const std::string& subscriberId, const MessageSeqId& messageSeqId);

    void startDelivery(const std::string& topic, const std::string& subscriberId, const MessageHandlerCallbackPtr& callback);
    void stopDelivery(const std::string& topic, const std::string& subscriberId);

    void closeSubscription(const std::string& topic, const std::string& subscriberId);

    void messageHandler(const PubSubResponsePtr& m, const PubSubDataPtr& txn);

    void doSubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data, const SubscriberClientChannelHandlerPtr& handler);
    void doUnsubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data);

  private:
    const ClientImplPtr client;
    
    std::tr1::unordered_map<TopicSubscriber, SubscriberClientChannelHandlerPtr, TopicSubscriberHash > topicsubscriber2handler;
    boost::shared_mutex topicsubscriber2handler_lock;	    
  };

};

#endif
