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
#include "clientimpl.h"
#include "channel.h"
#include "publisherimpl.h"
#include "subscriberimpl.h"
#include <log4cxx/logger.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

using namespace Hedwig;

const std::string DEFAULT_SERVER_DEFAULT_VAL = "";

void SyncOperationCallback::wait() {
  boost::unique_lock<boost::mutex> lock(mut);
  while(response==PENDING) {
    if (cond.timed_wait(lock, boost::posix_time::milliseconds(timeout)) == false) {
      LOG4CXX_ERROR(logger, "Timeout waiting for operation to complete " << this);

      response = TIMEOUT;
    }
  }
}

void SyncOperationCallback::operationComplete() {
  if (response == TIMEOUT) {
    LOG4CXX_ERROR(logger, "operationCompleted successfully after timeout " << this);
    return;
  }

  {
    boost::lock_guard<boost::mutex> lock(mut);
    response = SUCCESS;
  }
  cond.notify_all();
}

void SyncOperationCallback::operationFailed(const std::exception& exception) {
  if (response == TIMEOUT) {
    LOG4CXX_ERROR(logger, "operationCompleted unsuccessfully after timeout " << this);
    return;
  }

  {
    boost::lock_guard<boost::mutex> lock(mut);
    
    if (typeid(exception) == typeid(ChannelConnectException)) {
      response = NOCONNECT;
    } else if (typeid(exception) == typeid(ServiceDownException)) {
      response = SERVICEDOWN;
    } else if (typeid(exception) == typeid(AlreadySubscribedException)) {
      response = ALREADY_SUBSCRIBED;
    } else if (typeid(exception) == typeid(NotSubscribedException)) {
      response = NOT_SUBSCRIBED;
    } else {
      response = UNKNOWN;
    }
  }
  cond.notify_all();
}

void SyncOperationCallback::throwExceptionIfNeeded() {
  switch (response) {
  case SUCCESS:
    break;
  case NOCONNECT:
    throw CannotConnectException();
    break;
  case SERVICEDOWN:
    throw ServiceDownException();
    break;
  case ALREADY_SUBSCRIBED:
    throw AlreadySubscribedException();
    break;
  case NOT_SUBSCRIBED:
    throw NotSubscribedException();
    break;
  case TIMEOUT:
    throw ClientTimeoutException();
    break;
  default:
    throw ClientException();
    break;
  }
}

HedwigClientChannelHandler::HedwigClientChannelHandler(const ClientImplPtr& client) 
  : client(client){
}

void HedwigClientChannelHandler::messageReceived(const DuplexChannelPtr& channel, const PubSubResponsePtr& m) {
  LOG4CXX_DEBUG(logger, "Message received txnid(" << m->txnid() << ") status(" 
		<< m->statuscode() << ")");
  if (m->has_message()) {
    LOG4CXX_ERROR(logger, "Subscription response, ignore for now");
    return;
  }
  
  PubSubDataPtr data = channel->retrieveTransaction(m->txnid()); 
  /* you now have ownership of data, don't leave this funciton without deleting it or 
     palming it off to someone else */

  if (data == NULL) {
    return;
  }

  if (m->statuscode() == NOT_RESPONSIBLE_FOR_TOPIC) {
    client->redirectRequest(channel, data, m);
    return;
  }

  switch (data->getType()) {
  case PUBLISH:
    client->getPublisherImpl().messageHandler(m, data);
    break;
  case SUBSCRIBE:
  case UNSUBSCRIBE:
    client->getSubscriberImpl().messageHandler(m, data);
    break;
  default:
    LOG4CXX_ERROR(logger, "Unimplemented request type " << data->getType());
    break;
  }
}


void HedwigClientChannelHandler::channelConnected(const DuplexChannelPtr& channel) {
  // do nothing 
}

void HedwigClientChannelHandler::channelDisconnected(const DuplexChannelPtr& channel, const std::exception& e) {
  LOG4CXX_ERROR(logger, "Channel disconnected");

  client->channelDied(channel);
}

void HedwigClientChannelHandler::exceptionOccurred(const DuplexChannelPtr& channel, const std::exception& e) {
  LOG4CXX_ERROR(logger, "Exception occurred" << e.what());
}

ClientTxnCounter::ClientTxnCounter() : counter(0) 
{
}

ClientTxnCounter::~ClientTxnCounter() {
}

/**
Increment the transaction counter and return the new value.

@returns the next transaction id
*/
long ClientTxnCounter::next() {  // would be nice to remove lock from here, look more into it
  boost::lock_guard<boost::mutex> lock(mutex);

  long next= ++counter; 

  return next;
}

ClientImplPtr ClientImpl::Create(const Configuration& conf) {
  ClientImplPtr impl(new ClientImpl(conf));
  LOG4CXX_DEBUG(logger, "Creating Clientimpl " << impl);

  impl->dispatcher.start();

  return impl;
}

void ClientImpl::Destroy() {
  LOG4CXX_DEBUG(logger, "destroying Clientimpl " << this);

  dispatcher.stop();
  {
    boost::lock_guard<boost::shared_mutex> lock(allchannels_lock);
    
    shuttingDownFlag = true;
    for (ChannelMap::iterator iter = allchannels.begin(); iter != allchannels.end(); ++iter ) {
      (*iter)->kill();
    }  
    allchannels.clear();
  }

  /* destruction of the maps will clean up any items they hold */
  
  if (subscriber != NULL) {
    delete subscriber;
    subscriber = NULL;
  }
  if (publisher != NULL) {
    delete publisher;
    publisher = NULL;
  }
}

ClientImpl::ClientImpl(const Configuration& conf) 
  : conf(conf), publisher(NULL), subscriber(NULL), counterobj(), shuttingDownFlag(false)
{
}

Subscriber& ClientImpl::getSubscriber() {
  return getSubscriberImpl();
}

Publisher& ClientImpl::getPublisher() {
  return getPublisherImpl();
}
    
SubscriberImpl& ClientImpl::getSubscriberImpl() {
  if (subscriber == NULL) {
    boost::lock_guard<boost::mutex> lock(subscribercreate_lock);
    if (subscriber == NULL) {
      subscriber = new SubscriberImpl(shared_from_this());
    }
  }
  return *subscriber;
}

PublisherImpl& ClientImpl::getPublisherImpl() {
  if (publisher == NULL) { 
    boost::lock_guard<boost::mutex> lock(publishercreate_lock);
    if (publisher == NULL) {
      publisher = new PublisherImpl(shared_from_this());
    }
  }
  return *publisher;
}

ClientTxnCounter& ClientImpl::counter() {
  return counterobj;
}

void ClientImpl::redirectRequest(const DuplexChannelPtr& channel, PubSubDataPtr& data, const PubSubResponsePtr& response) {
  HostAddress oldhost = channel->getHostAddress();
  data->addTriedServer(oldhost);
  
  HostAddress h = HostAddress::fromString(response->statusmsg());
  if (data->hasTriedServer(h)) {
    LOG4CXX_ERROR(logger, "We've been told to try request [" << data->getTxnId() << "] with [" 
		  << h.getAddressString()<< "] by " << oldhost.getAddressString() 
		  << " but we've already tried that. Failing operation");
    data->getCallback()->operationFailed(InvalidRedirectException());
    return;
  }
  LOG4CXX_DEBUG(logger, "We've been told  [" << data->getTopic() << "] is on [" << h.getAddressString() 
		<< "] by [" << oldhost.getAddressString() << "]. Redirecting request " << data->getTxnId());
  data->setShouldClaim(true);

  setHostForTopic(data->getTopic(), h);
  DuplexChannelPtr newchannel;
  try {
    if (data->getType() == SUBSCRIBE) {
      SubscriberClientChannelHandlerPtr handler(new SubscriberClientChannelHandler(shared_from_this(), 
										   this->getSubscriberImpl(), data));
      newchannel = createChannel(data->getTopic(), handler);
      handler->setChannel(newchannel);
      getSubscriberImpl().doSubscribe(newchannel, data, handler);
    } else if (data->getType() == PUBLISH) {
      newchannel = getChannel(data->getTopic());
      getPublisherImpl().doPublish(newchannel, data);
    } else {
      newchannel = getChannel(data->getTopic());
      getSubscriberImpl().doUnsubscribe(newchannel, data);
    }
  } catch (ShuttingDownException& e) {
    return; // no point in redirecting if we're shutting down
  }
}

ClientImpl::~ClientImpl() {
  LOG4CXX_DEBUG(logger, "deleting Clientimpl " << this);
}

DuplexChannelPtr ClientImpl::createChannel(const std::string& topic, const ChannelHandlerPtr& handler) {
  // get the host address
  // create a channel to the host
  HostAddress addr = topic2host[topic];
  if (addr.isNullHost()) {
    addr = HostAddress::fromString(conf.get(Configuration::DEFAULT_SERVER, DEFAULT_SERVER_DEFAULT_VAL));
    setHostForTopic(topic, addr);
  }

  DuplexChannelPtr channel(new DuplexChannel(dispatcher, addr, conf, handler));

  boost::lock_guard<boost::shared_mutex> lock(allchannels_lock);
  if (shuttingDownFlag) {
    channel->kill();
    throw ShuttingDownException();
  }
  channel->connect();

  allchannels.insert(channel);
  LOG4CXX_DEBUG(logger, "(create) All channels size: " << allchannels.size());

  return channel;
}

DuplexChannelPtr ClientImpl::getChannel(const std::string& topic) {
  HostAddress addr = topic2host[topic];
  if (addr.isNullHost()) {
    addr = HostAddress::fromString(conf.get(Configuration::DEFAULT_SERVER, DEFAULT_SERVER_DEFAULT_VAL));
    setHostForTopic(topic, addr);
  }  
  DuplexChannelPtr channel = host2channel[addr];

  if (channel.get() == 0) {
    LOG4CXX_DEBUG(logger, " No channel for topic, creating new channel.get() " << channel.get() << " addr " << addr.getAddressString());
    ChannelHandlerPtr handler(new HedwigClientChannelHandler(shared_from_this()));
    channel = createChannel(topic, handler);

    boost::lock_guard<boost::shared_mutex> lock(host2channel_lock);
    host2channel[addr] = channel;
  } 

  return channel;
}

void ClientImpl::setHostForTopic(const std::string& topic, const HostAddress& host) {
  boost::lock_guard<boost::shared_mutex> lock(topic2host_lock);
  topic2host[topic] = host;
}

bool ClientImpl::shuttingDown() const {
  return shuttingDownFlag;
}

/**
   A channel has just died. Remove it so we never give it to any other publisher or subscriber.
   
   This does not delete the channel. Some publishers or subscribers will still hold it and will be errored
   when they try to do anything with it. 
*/
void ClientImpl::channelDied(const DuplexChannelPtr& channel) {
  if (shuttingDownFlag) {
    return;
  }

  boost::lock_guard<boost::shared_mutex> h2tlock(host2topics_lock);
  boost::lock_guard<boost::shared_mutex> h2clock(host2channel_lock);
  boost::lock_guard<boost::shared_mutex> t2hlock(topic2host_lock);
  boost::lock_guard<boost::shared_mutex> aclock(allchannels_lock);
  // get host
  HostAddress addr = channel->getHostAddress();
  
  for (Host2TopicsMap::iterator iter = host2topics.find(addr); iter != host2topics.end(); ++iter) {
    topic2host.erase((*iter).second);
  }
  host2topics.erase(addr);
  host2channel.erase(addr);

  allchannels.erase(channel); // channel should be deleted here
}

const Configuration& ClientImpl::getConfiguration() {
  return conf;
}

boost::asio::io_service& ClientImpl::getService() {
  return dispatcher.getService();
}
