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

#include <boost/asio.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>

#include <log4cxx/logger.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

using namespace Hedwig;
const int DEFAULT_MESSAGE_CONSUME_RETRY_WAIT_TIME = 5000;
const int DEFAULT_SUBSCRIBER_CONSUME_RETRY_WAIT_TIME = 5000;
const int DEFAULT_MAX_MESSAGE_QUEUE_SIZE = 10;
const int DEFAULT_RECONNECT_SUBSCRIBE_RETRY_WAIT_TIME = 5000;
const bool DEFAULT_SUBSCRIBER_AUTOCONSUME = true;

SubscriberWriteCallback::SubscriberWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void SubscriberWriteCallback::operationComplete() {
  LOG4CXX_DEBUG(logger, "Successfully wrote subscribe transaction: " << data->getTxnId());
}

void SubscriberWriteCallback::operationFailed(const std::exception& exception) {
  LOG4CXX_ERROR(logger, "Error writing to subscriber " << exception.what());
  
  //remove txn from channel pending list
  data->getCallback()->operationFailed(exception);
  client->getSubscriberImpl().closeSubscription(data->getTopic(), data->getSubscriberId());
}

UnsubscribeWriteCallback::UnsubscribeWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void UnsubscribeWriteCallback::operationComplete() {
  LOG4CXX_DEBUG(logger, "Successfully wrote unsubscribe transaction: " << data->getTxnId());
}

void UnsubscribeWriteCallback::operationFailed(const std::exception& exception) {
  data->getCallback()->operationFailed(exception);
}
  
ConsumeWriteCallback::ConsumeWriteCallback(const ClientImplPtr& client, const PubSubDataPtr& data) 
  : client(client), data(data) {
}

ConsumeWriteCallback::~ConsumeWriteCallback() {
}

/* static */ void ConsumeWriteCallback::timerComplete(const ClientImplPtr& client, const PubSubDataPtr& data,
						      const boost::system::error_code& error) {
  if (error) {
    // shutting down
    return;
  }

  client->getSubscriberImpl().consume(data->getTopic(), data->getSubscriberId(), data->getMessageSeqId());
}


void ConsumeWriteCallback::operationComplete() {
  LOG4CXX_DEBUG(logger, "Successfully wrote consume transaction: " << data->getTxnId());
}

void ConsumeWriteCallback::operationFailed(const std::exception& exception) {
  int retrywait = client->getConfiguration().getInt(Configuration::MESSAGE_CONSUME_RETRY_WAIT_TIME, 
						    DEFAULT_MESSAGE_CONSUME_RETRY_WAIT_TIME);
  LOG4CXX_ERROR(logger, "Error writing consume transaction: " << data->getTxnId() << " error: " << exception.what() 
		<< " retrying in " << retrywait << " Microseconds");

  boost::asio::deadline_timer t(client->getService(), boost::posix_time::milliseconds(retrywait));

  t.async_wait(boost::bind(&ConsumeWriteCallback::timerComplete, client, data, boost::asio::placeholders::error));  
}

SubscriberConsumeCallback::SubscriberConsumeCallback(const ClientImplPtr& client, 
						     const SubscriberClientChannelHandlerPtr& handler, 
						     const PubSubDataPtr& data, const PubSubResponsePtr& m) 
  : client(client), handler(handler), data(data), m(m)
{
}

void SubscriberConsumeCallback::operationComplete() {
  LOG4CXX_DEBUG(logger, "ConsumeCallback::operationComplete " << data->getTopic() << " - " << data->getSubscriberId());

  if (client->getConfiguration().getBool(Configuration::SUBSCRIBER_AUTOCONSUME, DEFAULT_SUBSCRIBER_AUTOCONSUME)) {
    client->getSubscriber().consume(data->getTopic(), data->getSubscriberId(), m->message().msgid());
  }
}

/* static */ void SubscriberConsumeCallback::timerComplete(const SubscriberClientChannelHandlerPtr handler, 
							   const PubSubResponsePtr m, 
							   const boost::system::error_code& error) {
  if (error) {
    return;
  }
  handler->messageReceived(handler->getChannel(), m);
}

void SubscriberConsumeCallback::operationFailed(const std::exception& exception) {
  LOG4CXX_ERROR(logger, "ConsumeCallback::operationFailed  " << data->getTopic() << " - " << data->getSubscriberId());
  
  int retrywait = client->getConfiguration().getInt(Configuration::SUBSCRIBER_CONSUME_RETRY_WAIT_TIME,
						    DEFAULT_SUBSCRIBER_CONSUME_RETRY_WAIT_TIME);

  LOG4CXX_ERROR(logger, "Error passing message to client transaction: " << data->getTxnId() << " error: " << exception.what() 
		<< " retrying in " << retrywait << " Microseconds");

  boost::asio::deadline_timer t(client->getService(), boost::posix_time::milliseconds(retrywait));

  t.async_wait(boost::bind(&SubscriberConsumeCallback::timerComplete, handler, m, boost::asio::placeholders::error));  
}

SubscriberReconnectCallback::SubscriberReconnectCallback(const ClientImplPtr& client, const PubSubDataPtr& origData) 
  : client(client), origData(origData) {
}

void SubscriberReconnectCallback::operationComplete() {
}

void SubscriberReconnectCallback::operationFailed(const std::exception& exception) {
  LOG4CXX_ERROR(logger, "Error writing to new subscriber. Channel should pick this up disconnect the channel and try to connect again " << exception.what());

}

SubscriberClientChannelHandler::SubscriberClientChannelHandler(const ClientImplPtr& client, SubscriberImpl& subscriber, const PubSubDataPtr& data)
  : HedwigClientChannelHandler(client), subscriber(subscriber), origData(data), closed(false), should_wait(true)  {
  LOG4CXX_DEBUG(logger, "Creating SubscriberClientChannelHandler " << this);
}

SubscriberClientChannelHandler::~SubscriberClientChannelHandler() {
  LOG4CXX_DEBUG(logger, "Cleaning up SubscriberClientChannelHandler " << this);
}

void SubscriberClientChannelHandler::messageReceived(const DuplexChannelPtr& channel, const PubSubResponsePtr& m) {
  if (m->has_message()) {
    LOG4CXX_DEBUG(logger, "Message received (topic:" << origData->getTopic() << ", subscriberId:" << origData->getSubscriberId() << ")");

    if (this->handler.get()) {
      OperationCallbackPtr callback(new SubscriberConsumeCallback(client, shared_from_this(), origData, m));
      this->handler->consume(origData->getTopic(), origData->getSubscriberId(), m->message(), callback);
    } else {
      queue.push_back(m);
      if (queue.size() >= (std::size_t)client->getConfiguration().getInt(Configuration::MAX_MESSAGE_QUEUE_SIZE,
									 DEFAULT_MAX_MESSAGE_QUEUE_SIZE)) {
	channel->stopReceiving();
      }
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

/*static*/ void SubscriberClientChannelHandler::reconnectTimerComplete(const SubscriberClientChannelHandlerPtr handler,
								       const DuplexChannelPtr channel, const std::exception e, 
								       const boost::system::error_code& error) {
  if (error) {
    return;
  }
  handler->should_wait = false;
  handler->channelDisconnected(channel, e);
}

void SubscriberClientChannelHandler::channelDisconnected(const DuplexChannelPtr& channel, const std::exception& e) {
  // has subscription been closed
  if (closed) {
    return;
  }

  // Clean up the channel from all maps
  client->channelDied(channel);
  if (client->shuttingDown()) {
    return;
  }

  if (should_wait) {
    int retrywait = client->getConfiguration().getInt(Configuration::RECONNECT_SUBSCRIBE_RETRY_WAIT_TIME,
						      DEFAULT_RECONNECT_SUBSCRIBE_RETRY_WAIT_TIME);
    
    boost::asio::deadline_timer t(client->getService(), boost::posix_time::milliseconds(retrywait));
    t.async_wait(boost::bind(&SubscriberClientChannelHandler::reconnectTimerComplete, shared_from_this(), 
			     channel, e, boost::asio::placeholders::error));  
    return;
  }
  should_wait = true;

  // setup pubsub data for reconnection attempt
  origData->clearTriedServers();
  OperationCallbackPtr newcallback(new SubscriberReconnectCallback(client, origData));
  origData->setCallback(newcallback);

  // Create a new handler for the new channel
  SubscriberClientChannelHandlerPtr newhandler(new SubscriberClientChannelHandler(client, subscriber, origData));  
  ChannelHandlerPtr baseptr = newhandler;
  
  DuplexChannelPtr newchannel = client->createChannel(origData->getTopic(), baseptr);
  newhandler->setChannel(newchannel);
  handoverDelivery(newhandler);
  
  // remove record of the failed channel from the subscriber
  client->getSubscriberImpl().closeSubscription(origData->getTopic(), origData->getSubscriberId());
  
  // subscriber
  client->getSubscriberImpl().doSubscribe(newchannel, origData, newhandler);
}

void SubscriberClientChannelHandler::startDelivery(const MessageHandlerCallbackPtr& handler) {
  this->handler = handler;
  
  while (!queue.empty()) {    
    PubSubResponsePtr m = queue.front();
    queue.pop_front();

    OperationCallbackPtr callback(new SubscriberConsumeCallback(client, shared_from_this(), origData, m));

    this->handler->consume(origData->getTopic(), origData->getSubscriberId(), m->message(), callback);
  }
  channel->startReceiving();
}

void SubscriberClientChannelHandler::stopDelivery() {
  channel->stopReceiving();

  this->handler = MessageHandlerCallbackPtr();
}


void SubscriberClientChannelHandler::handoverDelivery(const SubscriberClientChannelHandlerPtr& newHandler) {
  LOG4CXX_DEBUG(logger, "Messages in queue " << queue.size());
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

SubscriberImpl::SubscriberImpl(const ClientImplPtr& client) 
  : client(client) 
{
}

SubscriberImpl::~SubscriberImpl() 
{
  LOG4CXX_DEBUG(logger, "deleting subscriber" << this);
}


void SubscriberImpl::subscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode) {
  SyncOperationCallback* cb = new SyncOperationCallback(client->getConfiguration().getInt(Configuration::SYNC_REQUEST_TIMEOUT, 
											  DEFAULT_SYNC_REQUEST_TIMEOUT));
  OperationCallbackPtr callback(cb);
  asyncSubscribe(topic, subscriberId, mode, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();  
}

void SubscriberImpl::asyncSubscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode, const OperationCallbackPtr& callback) {
  PubSubDataPtr data = PubSubData::forSubscribeRequest(client->counter().next(), subscriberId, topic, callback, mode);

  SubscriberClientChannelHandlerPtr handler(new SubscriberClientChannelHandler(client, *this, data));
  ChannelHandlerPtr baseptr = handler;

  DuplexChannelPtr channel = client->createChannel(topic, handler);
  handler->setChannel(channel);
  doSubscribe(channel, data, handler);
}

void SubscriberImpl::doSubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data, const SubscriberClientChannelHandlerPtr& handler) {
  channel->storeTransaction(data);

  OperationCallbackPtr writecb(new SubscriberWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);

  boost::lock_guard<boost::shared_mutex> lock(topicsubscriber2handler_lock);
  TopicSubscriber t(data->getTopic(), data->getSubscriberId());
  SubscriberClientChannelHandlerPtr oldhandler = topicsubscriber2handler[t];
  if (oldhandler != NULL) {
    oldhandler->handoverDelivery(handler);
  }
  topicsubscriber2handler[t] = handler;
  
  LOG4CXX_DEBUG(logger, "Set topic subscriber for topic(" << data->getTopic() << ") subscriberId(" << data->getSubscriberId() << ") to " << handler.get() << " topicsubscriber2topic(" << &topicsubscriber2handler << ")");
}

void SubscriberImpl::unsubscribe(const std::string& topic, const std::string& subscriberId) {
  SyncOperationCallback* cb = new SyncOperationCallback(client->getConfiguration().getInt(Configuration::SYNC_REQUEST_TIMEOUT, 
											  DEFAULT_SYNC_REQUEST_TIMEOUT));
  OperationCallbackPtr callback(cb);
  asyncUnsubscribe(topic, subscriberId, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();
}

void SubscriberImpl::asyncUnsubscribe(const std::string& topic, const std::string& subscriberId, const OperationCallbackPtr& callback) {
  closeSubscription(topic, subscriberId);

  PubSubDataPtr data = PubSubData::forUnsubscribeRequest(client->counter().next(), subscriberId, topic, callback);
  
  DuplexChannelPtr channel = client->getChannel(topic);
  doUnsubscribe(channel, data);
}

void SubscriberImpl::doUnsubscribe(const DuplexChannelPtr& channel, const PubSubDataPtr& data) {
  channel->storeTransaction(data);
  OperationCallbackPtr writecb(new UnsubscribeWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);
}

void SubscriberImpl::consume(const std::string& topic, const std::string& subscriberId, const MessageSeqId& messageSeqId) {
  TopicSubscriber t(topic, subscriberId);
  
  boost::shared_lock<boost::shared_mutex> lock(topicsubscriber2handler_lock);
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];

  if (handler.get() == 0) {
    LOG4CXX_ERROR(logger, "Cannot consume. Bad handler for topic(" << topic << ") subscriberId(" << subscriberId << ") topicsubscriber2topic(" << &topicsubscriber2handler << ")");
    return;
  }

  DuplexChannelPtr channel = handler->getChannel();
  if (channel.get() == 0) {
    LOG4CXX_ERROR(logger, "Trying to consume a message on a topic/subscriber pair that don't have a channel. Something fishy going on. Topic: " << topic << " SubscriberId: " << subscriberId << " MessageSeqId: " << messageSeqId.localcomponent());
  }
  
  PubSubDataPtr data = PubSubData::forConsumeRequest(client->counter().next(), subscriberId, topic, messageSeqId);  
  OperationCallbackPtr writecb(new ConsumeWriteCallback(client, data));
  channel->writeRequest(data->getRequest(), writecb);
}

void SubscriberImpl::startDelivery(const std::string& topic, const std::string& subscriberId, const MessageHandlerCallbackPtr& callback) {
  TopicSubscriber t(topic, subscriberId);

  boost::shared_lock<boost::shared_mutex> lock(topicsubscriber2handler_lock);
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];

  if (handler.get() == 0) {
    LOG4CXX_ERROR(logger, "Trying to start deliver on a non existant handler topic = " << topic << ", subscriber = " << subscriberId);
  }
  handler->startDelivery(callback);
}

void SubscriberImpl::stopDelivery(const std::string& topic, const std::string& subscriberId) {
  TopicSubscriber t(topic, subscriberId);
  
  boost::shared_lock<boost::shared_mutex> lock(topicsubscriber2handler_lock);
  SubscriberClientChannelHandlerPtr handler = topicsubscriber2handler[t];

  if (handler.get() == 0) {
    LOG4CXX_ERROR(logger, "Trying to start deliver on a non existant handler topic = " << topic << ", subscriber = " << subscriberId);
  }
  handler->stopDelivery();
}

void SubscriberImpl::closeSubscription(const std::string& topic, const std::string& subscriberId) {
  LOG4CXX_DEBUG(logger, "closeSubscription (" << topic << ",  " << subscriberId << ")");

  TopicSubscriber t(topic, subscriberId);

  SubscriberClientChannelHandlerPtr handler;
  {
    boost::lock_guard<boost::shared_mutex> lock(topicsubscriber2handler_lock);
    handler = topicsubscriber2handler[t];
    topicsubscriber2handler.erase(t);
  }
  
  if (handler.get() != 0) {
    handler->close();
  }
}

/**
   takes ownership of txn
*/
void SubscriberImpl::messageHandler(const PubSubResponsePtr& m, const PubSubDataPtr& txn) {
  if (!txn.get()) {
    LOG4CXX_ERROR(logger, "Invalid transaction");
    return;
  }

  LOG4CXX_DEBUG(logger, "message received with status " << m->statuscode());

  switch (m->statuscode()) {
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
