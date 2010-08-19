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

#include "subscriberimpl.h"
#include "util.h"
#include "channel.h"

#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);
const int SUBSCRIBER_RECONNECT_TIME = 3000; // 3 seconds
using namespace Hedwig;

SubscriberWriteCallback::SubscriberWriteCallback(ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void SubscriberWriteCallback::operationComplete() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Successfully wrote subscribe transaction: " << data->getTxnId();
  }
}

void SubscriberWriteCallback::operationFailed(const std::exception& exception) {
  LOG.errorStream() << "Error writing to publisher " << exception.what();
  
  //remove txn from channel pending list
  #warning "Actually do something here"
}

UnsubscribeWriteCallback::UnsubscribeWriteCallback(ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void UnsubscribeWriteCallback::operationComplete() {
  
}

void UnsubscribeWriteCallback::operationFailed(const std::exception& exception) {
  #warning "Actually do something here"
}
  
ConsumeWriteCallback::ConsumeWriteCallback(const PubSubDataPtr& data) 
  : data(data) {
}

ConsumeWriteCallback::~ConsumeWriteCallback() {
}

void ConsumeWriteCallback::operationComplete() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Successfully wrote consume transaction: " << data->getTxnId();
  }
}

void ConsumeWriteCallback::operationFailed(const std::exception& exception) {
  LOG.errorStream() << "Error writing consume transaction: " << data->getTxnId() << " error: " << exception.what();
}

SubscriberConsumeCallback::SubscriberConsumeCallback(ClientImplPtr& client, const std::string& topic, const std::string& subscriberid, const MessageSeqId& msgid) 
  : client(client), topic(topic), subscriberid(subscriberid), msgid(msgid)
{
}

void SubscriberConsumeCallback::operationComplete() {
  LOG.errorStream() << "ConsumeCallback::operationComplete";
  client->getSubscriber().consume(topic, subscriberid, msgid);
}

void SubscriberConsumeCallback::operationFailed(const std::exception& exception) {
  LOG.errorStream() << "ConsumeCallback::operationFailed";
}

SubscriberReconnectCallback::SubscriberReconnectCallback(ClientImplPtr& client, const PubSubDataPtr& origData) 
  : client(client), origData(origData) {
}

void SubscriberReconnectCallback::operationComplete() {
}

void SubscriberReconnectCallback::operationFailed(const std::exception& exception) {
  
}

SubscriberClientChannelHandler::SubscriberClientChannelHandler(ClientImplPtr& client, SubscriberImpl& subscriber, const PubSubDataPtr& data)
  : HedwigClientChannelHandler(client), subscriber(subscriber), origData(data), closed(false)  {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Creating SubscriberClientChannelHandler " << this;
  }
}

SubscriberClientChannelHandler::~SubscriberClientChannelHandler() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Cleaning up SubscriberClientChannelHandler " << this;
  }
}

void SubscriberClientChannelHandler::messageReceived(DuplexChannel* channel, const PubSubResponse& m) {
  if (m.has_message()) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Message received (topic:" << origData->getTopic() << ", subscriberId:" << origData->getSubscriberId() << ")";
    }

    if (this->handler.get()) {
      OperationCallbackPtr callback(new SubscriberConsumeCallback(client, origData->getTopic(), origData->getSubscriberId(), m.message().msgid()));
      this->handler->consume(origData->getTopic(), origData->getSubscriberId(), m.message(), callback);
    } else {
      LOG.debugStream() << "putting in queue";
      queue.push_back(m.message());
    }
  } else {
    HedwigClientChannelHandler::messageReceived(channel, m);
  }
}

void SubscriberClientChannelHandler::close() {
  closed = true;
  if (channel) {
    channel->kill();
  }
}

void SubscriberClientChannelHandler::channelDisconnected(DuplexChannel* channel, const std::exception& e) {
  // has subscription been closed
  if (closed) {
    return;
  }

  // Clean up the channel from all maps
  client->channelDied(channel);
  if (client->shuttingDown()) {
    return;
  }
  
  // setup pubsub data for reconnection attempt
  origData->clearTriedServers();
  OperationCallbackPtr newcallback(new SubscriberReconnectCallback(client, origData));
  origData->setCallback(newcallback);

  // Create a new handler for the new channel
  SubscriberClientChannelHandlerPtr handler(new SubscriberClientChannelHandler(client, subscriber, origData));  
  ChannelHandlerPtr baseptr = handler;
  // if there is an error createing the channel, sleep for SUBSCRIBER_RECONNECT_TIME and try again
  DuplexChannelPtr newchannel;
  while (true) {
    try {
      newchannel = client->createChannelForTopic(origData->getTopic(), baseptr);
      handler->setChannel(newchannel);
      break;
    } catch (ShuttingDownException& e) {
      LOG.errorStream() << "Shutting down, don't try to reconnect";
      return; 
    } catch (ChannelException& e) {
      LOG.errorStream() << "Couldn't acquire channel, sleeping for " << SUBSCRIBER_RECONNECT_TIME << " before trying again";
      usleep(SUBSCRIBER_RECONNECT_TIME);
    }
  } 
  handoverDelivery(handler.get());
  
  // remove record of the failed channel from the subscriber
  subscriber.closeSubscription(origData->getTopic(), origData->getSubscriberId());

  // subscriber
  subscriber.doSubscribe(newchannel, origData, handler);
}

void SubscriberClientChannelHandler::startDelivery(const MessageHandlerCallbackPtr& handler) {
  this->handler = handler;
  
  while (!queue.empty()) {    
    LOG.debugStream() << "Taking from queue";
    Message m = queue.front();
    queue.pop_front();

    OperationCallbackPtr callback(new SubscriberConsumeCallback(client, origData->getTopic(), origData->getSubscriberId(), m.msgid()));

    this->handler->consume(origData->getTopic(), origData->getSubscriberId(), m, callback);
  }
}

void SubscriberClientChannelHandler::stopDelivery() {
  this->handler = MessageHandlerCallbackPtr();
}


void SubscriberClientChannelHandler::handoverDelivery(SubscriberClientChannelHandler* newHandler) {
  LOG.debugStream() << "Messages in queue " << queue.size();
  MessageHandlerCallbackPtr handler = this->handler;
  stopDelivery(); // resets old handler
  newHandler->startDelivery(handler);
}

void SubscriberClientChannelHandler::setChannel(const DuplexChannelPtr& channel) {
  this->channel = channel;
}

DuplexChannelPtr& SubscriberClientChannelHandler::getChannel() {
  return channel;
}

SubscriberImpl::SubscriberImpl(ClientImplPtr& client) 
  : client(client) 
{
}

SubscriberImpl::~SubscriberImpl() 
{
  LOG.debugStream() << "deleting subscriber" << this;
}


void SubscriberImpl::subscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode) {
  SyncOperationCallback* cb = new SyncOperationCallback();
  OperationCallbackPtr callback(cb);
  asyncSubscribe(topic, subscriberId, mode, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();  
}

void SubscriberImpl::asyncSubscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode, const OperationCallbackPtr& callback) {
  PubSubDataPtr data = PubSubData::forSubscribeRequest(client->counter().next(), subscriberId, topic, callback, mode);

  SubscriberClientChannelHandlerPtr handler(new SubscriberClientChannelHandler(client, *this, data));  
  ChannelHandlerPtr baseptr = handler;
  DuplexChannelPtr channel = client->createChannelForTopic(topic, baseptr);
  
  handler->setChannel(channel);

  doSubscribe(channel, data, handler);
}

void SubscriberImpl::doSubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data, const SubscriberClientChannelHandlerPtr& handler) {
  LOG.debugStream() << "doSubscribe";
  channel->storeTransaction(data);

  OperationCallbackPtr writecb(new SubscriberWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);

  topicsubscriber2handler_lock.lock();
  TopicSubscriber t(data->getTopic(), data->getSubscriberId());
  SubscriberClientChannelHandlerPtr oldhandler = topicsubscriber2handler[t];
  if (oldhandler != NULL) {
    oldhandler->handoverDelivery(handler.get());
  }
  topicsubscriber2handler[t] = handler;
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Set topic subscriber for topic(" << data->getTopic() << ") subscriberId(" << data->getSubscriberId() << ") to " << handler.get() << " topicsubscriber2topic(" << &topicsubscriber2handler << ")";
  }
  topicsubscriber2handler_lock.unlock();;
}

void SubscriberImpl::unsubscribe(const std::string& topic, const std::string& subscriberId) {
  SyncOperationCallback* cb = new SyncOperationCallback();
  OperationCallbackPtr callback(cb);
  asyncUnsubscribe(topic, subscriberId, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();
}

void SubscriberImpl::asyncUnsubscribe(const std::string& topic, const std::string& subscriberId, const OperationCallbackPtr& callback) {
  closeSubscription(topic, subscriberId);

  PubSubDataPtr data = PubSubData::forUnsubscribeRequest(client->counter().next(), subscriberId, topic, callback);
  
  DuplexChannelPtr channel = client->getChannelForTopic(topic);
  if (channel.get() == 0) {
    LOG.errorStream() << "Trying to unsubscribe from (" << topic << ", " << subscriberId << ") but channel is dead";
    callback->operationFailed(InvalidStateException());
    return;
  }
  
  doUnsubscribe(channel, data);  
}

void SubscriberImpl::doUnsubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data) {
  channel->storeTransaction(data);
  OperationCallbackPtr writecb(new UnsubscribeWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);
}

void SubscriberImpl::consume(const std::string& topic, const std::string& subscriberId, const MessageSeqId& messageSeqId) {
  TopicSubscriber t(topic, subscriberId);

  topicsubscriber2handler_lock.lock();
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];
  topicsubscriber2handler_lock.unlock();

  if (handler.get() == 0) {
    LOG.errorStream() << "Cannot consume. Bad handler for topic(" << topic << ") subscriberId(" << subscriberId << ") topicsubscriber2topic(" << &topicsubscriber2handler << ")";
    return;
  }

  DuplexChannelPtr channel = handler->getChannel();
  if (channel.get() == 0) {
    LOG.errorStream() << "Trying to consume a message on a topic/subscriber pair that don't have a channel. Something fishy going on. Topic: " << topic << " SubscriberId: " << subscriberId << " MessageSeqId: " << messageSeqId.localcomponent();
  }
  
  PubSubDataPtr data = PubSubData::forConsumeRequest(client->counter().next(), subscriberId, topic, messageSeqId);  
  OperationCallbackPtr writecb(new ConsumeWriteCallback(data));
  channel->writeRequest(data->getRequest(), writecb);
}

void SubscriberImpl::startDelivery(const std::string& topic, const std::string& subscriberId, const MessageHandlerCallbackPtr& callback) {
  TopicSubscriber t(topic, subscriberId);

  topicsubscriber2handler_lock.lock();
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];
  topicsubscriber2handler_lock.unlock();

  if (handler.get() == 0) {
    LOG.errorStream() << "Trying to start deliver on a non existant handler topic = " << topic << ", subscriber = " << subscriberId;
  }
  handler->startDelivery(callback);
}

void SubscriberImpl::stopDelivery(const std::string& topic, const std::string& subscriberId) {
  TopicSubscriber t(topic, subscriberId);

  topicsubscriber2handler_lock.lock();
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];
  topicsubscriber2handler_lock.unlock();

  if (handler.get() == 0) {
    LOG.errorStream() << "Trying to start deliver on a non existant handler topic = " << topic << ", subscriber = " << subscriberId;
  }
  handler->stopDelivery();
}

void SubscriberImpl::closeSubscription(const std::string& topic, const std::string& subscriberId) {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "closeSubscription (" << topic << ",  " << subscriberId << ")";
  }
  TopicSubscriber t(topic, subscriberId);

  topicsubscriber2handler_lock.lock();;
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];
  topicsubscriber2handler.erase(t);
  topicsubscriber2handler_lock.unlock();;
  if (handler) {
    handler->close();
  }
}

/**
   takes ownership of txn
*/
void SubscriberImpl::messageHandler(const PubSubResponse& m, const PubSubDataPtr& txn) {
  if (!txn.get()) {
    LOG.errorStream() << "Invalid transaction";
    return;
  }

  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "message received with status " << m.statuscode();
  }
  switch (m.statuscode()) {
  case SUCCESS:
    txn->getCallback()->operationComplete();
    break;
  case SERVICE_DOWN:
    txn->getCallback()->operationFailed(ServiceDownException());
    break;
  case CLIENT_ALREADY_SUBSCRIBED:
  case TOPIC_BUSY:
    txn->getCallback()->operationFailed(AlreadySubscribedException());
    break;
  case CLIENT_NOT_SUBSCRIBED:
    txn->getCallback()->operationFailed(NotSubscribedException());
    break;
  default:
    txn->getCallback()->operationFailed(UnexpectedResponseException());
    break;
  }
}
