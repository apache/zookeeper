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
#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

using namespace Hedwig;


void SyncOperationCallback::operationComplete() {
  lock();
  response = SUCCESS;
  signalAndUnlock();
}

void SyncOperationCallback::operationFailed(const std::exception& exception) {
  lock();
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
  signalAndUnlock();
}
    
bool SyncOperationCallback::isTrue() {
  return response != PENDING;
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
  default:
    throw ClientException();
    break;
  }
}

HedwigClientChannelHandler::HedwigClientChannelHandler(ClientImplPtr& client) 
  : client(client){
}

void HedwigClientChannelHandler::messageReceived(DuplexChannel* channel, const PubSubResponse& m) {
  LOG.debugStream() << "Message received";
  if (m.has_message()) {
    LOG.errorStream() << "Subscription response, ignore for now";
    return;
  }
  
  long txnid = m.txnid();
  PubSubDataPtr data = channel->retrieveTransaction(m.txnid()); 
  /* you now have ownership of data, don't leave this funciton without deleting it or 
     palming it off to someone else */

  if (data == NULL) {
    LOG.errorStream() << "Transaction " << m.txnid() << " doesn't exist in channel " << channel;
    return;
  }

  if (m.statuscode() == NOT_RESPONSIBLE_FOR_TOPIC) {
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
    LOG.errorStream() << "Unimplemented request type " << data->getType();
    break;
  }
}


void HedwigClientChannelHandler::channelConnected(DuplexChannel* channel) {
  // do nothing 
}

void HedwigClientChannelHandler::channelDisconnected(DuplexChannel* channel, const std::exception& e) {
  LOG.errorStream() << "Channel disconnected";

  client->channelDied(channel);
}

void HedwigClientChannelHandler::exceptionOccurred(DuplexChannel* channel, const std::exception& e) {
  LOG.errorStream() << "Exception occurred" << e.what();
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
  mutex.lock();
  long next= ++counter; 
  mutex.unlock();
  return next;
}



PubSubDataPtr PubSubData::forPublishRequest(long txnid, const std::string& topic, const std::string& body, const OperationCallbackPtr& callback) {
  PubSubDataPtr ptr(new PubSubData());
  ptr->type = PUBLISH;
  ptr->txnid = txnid;
  ptr->topic = topic;
  ptr->body = body;
  ptr->callback = callback;
  return ptr;
}

PubSubDataPtr PubSubData::forSubscribeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const OperationCallbackPtr& callback, SubscribeRequest::CreateOrAttach mode) {
  PubSubDataPtr ptr(new PubSubData());
  ptr->type = SUBSCRIBE;
  ptr->txnid = txnid;
  ptr->subscriberid = subscriberid;
  ptr->topic = topic;
  ptr->callback = callback;
  ptr->mode = mode;
  return ptr;  
}

PubSubDataPtr PubSubData::forUnsubscribeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const OperationCallbackPtr& callback) {
  PubSubDataPtr ptr(new PubSubData());
  ptr->type = UNSUBSCRIBE;
  ptr->txnid = txnid;
  ptr->subscriberid = subscriberid;
  ptr->topic = topic;
  ptr->callback = callback;
  return ptr;  
}

PubSubDataPtr PubSubData::forConsumeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const MessageSeqId msgid) {
  PubSubDataPtr ptr(new PubSubData());
  ptr->type = CONSUME;
  ptr->txnid = txnid;
  ptr->subscriberid = subscriberid;
  ptr->topic = topic;
  ptr->msgid = msgid;
  return ptr;  
}

PubSubData::PubSubData() : request(NULL) {  
}

PubSubData::~PubSubData() {
  if (request != NULL) {
    delete request;
  }
}

OperationType PubSubData::getType() const {
  return type;
}

long PubSubData::getTxnId() const {
  return txnid;
}

const std::string& PubSubData::getTopic() const {
  return topic;
}

const std::string& PubSubData::getBody() const {
  return body;
}

const PubSubRequest& PubSubData::getRequest() {
  if (request != NULL) {
    delete request;
    request = NULL;
  }
  request = new Hedwig::PubSubRequest();
  request->set_protocolversion(Hedwig::VERSION_ONE);
  request->set_type(type);
  request->set_txnid(txnid);
  request->set_shouldclaim(shouldClaim);
  request->set_topic(topic);
    
  if (type == PUBLISH) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Creating publish request";
    }
    Hedwig::PublishRequest* pubreq = request->mutable_publishrequest();
    Hedwig::Message* msg = pubreq->mutable_msg();
    msg->set_body(body);
  } else if (type == SUBSCRIBE) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Creating subscribe request";
    }

    Hedwig::SubscribeRequest* subreq = request->mutable_subscriberequest();
    subreq->set_subscriberid(subscriberid);
    subreq->set_createorattach(mode);
  } else if (type == CONSUME) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Creating consume request";
    }

    Hedwig::ConsumeRequest* conreq = request->mutable_consumerequest();
    conreq->set_subscriberid(subscriberid);
    conreq->mutable_msgid()->CopyFrom(msgid);
  } else if (type == UNSUBSCRIBE) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Creating unsubscribe request";
    }
    
    Hedwig::UnsubscribeRequest* unsubreq = request->mutable_unsubscriberequest();
    unsubreq->set_subscriberid(subscriberid);    
  } else {
    LOG.errorStream() << "Tried to create a request message for the wrong type [" << type << "]";
    throw UnknownRequestException();
  }



  return *request;
}

void PubSubData::setShouldClaim(bool shouldClaim) {
  shouldClaim = shouldClaim;
}

void PubSubData::addTriedServer(HostAddress& h) {
  triedservers.insert(h);
}

bool PubSubData::hasTriedServer(HostAddress& h) {
  return triedservers.count(h) > 0;
}

void PubSubData::clearTriedServers() {
  triedservers.clear();
}

OperationCallbackPtr& PubSubData::getCallback() {
  return callback;
}

void PubSubData::setCallback(const OperationCallbackPtr& callback) {
  this->callback = callback;
}

const std::string& PubSubData::getSubscriberId() const {
  return subscriberid;
}

SubscribeRequest::CreateOrAttach PubSubData::getMode() const {
  return mode;
}

ClientImplPtr& ClientImpl::Create(const Configuration& conf) {
  ClientImpl* impl = new ClientImpl(conf);
    if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Creating Clientimpl " << impl;
  }

  return impl->selfptr;
}

void ClientImpl::Destroy() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "destroying Clientimpl " << this;
  }
  allchannels_lock.lock();

  shuttingDownFlag = true;
  for (ChannelMap::iterator iter = allchannels.begin(); iter != allchannels.end(); ++iter ) {
    (*iter).second->kill();
  }  
  allchannels.clear();
  allchannels_lock.unlock();
  /* destruction of the maps will clean up any items they hold */
  
  if (subscriber != NULL) {
    delete subscriber;
    subscriber = NULL;
  }
  if (publisher != NULL) {
    delete publisher;
    publisher = NULL;
  }

  selfptr = ClientImplPtr(); // clear the self pointer
}

ClientImpl::ClientImpl(const Configuration& conf) 
  : selfptr(this), conf(conf), subscriber(NULL), publisher(NULL), counterobj(), shuttingDownFlag(false)
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
    subscribercreate_lock.lock();
    if (subscriber == NULL) {
      subscriber = new SubscriberImpl(selfptr);
    }
    subscribercreate_lock.unlock();
  }
  return *subscriber;
}

PublisherImpl& ClientImpl::getPublisherImpl() {
  if (publisher == NULL) { 
    publishercreate_lock.lock();
    if (publisher == NULL) {
      publisher = new PublisherImpl(selfptr);
    }
    publishercreate_lock.unlock();
  }
  return *publisher;
}

ClientTxnCounter& ClientImpl::counter() {
  return counterobj;
}

void ClientImpl::redirectRequest(DuplexChannel* channel, PubSubDataPtr& data, const PubSubResponse& response) {
  HostAddress oldhost = channel->getHostAddress();
  data->addTriedServer(oldhost);
  
  HostAddress h = HostAddress::fromString(response.statusmsg());
  if (data->hasTriedServer(h)) {
    LOG.errorStream() << "We've been told to try request [" << data->getTxnId() << "] with [" << h.getAddressString()<< "] by " << channel->getHostAddress().getAddressString() << " but we've already tried that. Failing operation";
    data->getCallback()->operationFailed(InvalidRedirectException());
    return;
  }
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "We've been told  [" << data->getTopic() << "] is on [" << h.getAddressString() << "] by [" << oldhost.getAddressString() << "]. Redirecting request " << data->getTxnId();
  }
  data->setShouldClaim(true);

  setHostForTopic(data->getTopic(), h);
  DuplexChannelPtr newchannel;
  try {
    if (data->getType() == SUBSCRIBE) {
      SubscriberClientChannelHandlerPtr handler(new SubscriberClientChannelHandler(selfptr, this->getSubscriberImpl(), data));
      ChannelHandlerPtr basehandler = handler;
      
      newchannel = createChannelForTopic(data->getTopic(), basehandler);
      handler->setChannel(newchannel);
      
      getSubscriberImpl().doSubscribe(newchannel, data, handler);
    } else {
      newchannel = getChannelForTopic(data->getTopic());
      
      if (data->getType() == PUBLISH) {
	getPublisherImpl().doPublish(newchannel, data);
      } else {
	getSubscriberImpl().doUnsubscribe(newchannel, data);
      }
    }
  } catch (ShuttingDownException& e) {
    return; // no point in redirecting if we're shutting down
  }
}

ClientImpl::~ClientImpl() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "deleting Clientimpl " << this;
  }
}

DuplexChannelPtr ClientImpl::createChannelForTopic(const std::string& topic, ChannelHandlerPtr& handler) {
  // get the host address
  // create a channel to the host
  HostAddress addr = topic2host[topic];
  if (addr.isNullHost()) {
    addr = HostAddress::fromString(conf.getDefaultServer());
  }

  DuplexChannelPtr channel(new DuplexChannel(addr, conf, handler));
  channel->connect();

  allchannels_lock.lock();
  if (shuttingDownFlag) {
    channel->kill();
    allchannels_lock.unlock();
    throw ShuttingDownException();
  }
  allchannels[channel.get()] = channel;
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "(create) All channels size: " << allchannels.size();
  }
  allchannels_lock.unlock();

  return channel;
}

DuplexChannelPtr ClientImpl::getChannelForTopic(const std::string& topic) {
  HostAddress addr = topic2host[topic];
  DuplexChannelPtr channel = host2channel[addr];

  if (channel.get() == 0 || addr.isNullHost()) {
    ChannelHandlerPtr handler(new HedwigClientChannelHandler(selfptr));
    channel = createChannelForTopic(topic, handler);
    host2channel_lock.lock();
    host2channel[addr] = channel;
    host2channel_lock.unlock();
    return channel;
  }

  return channel;
}

void ClientImpl::setHostForTopic(const std::string& topic, const HostAddress& host) {
  topic2host_lock.lock();
  topic2host[topic] = host;
  topic2host_lock.unlock();
}

bool ClientImpl::shuttingDown() const {
  return shuttingDownFlag;
}

/**
   A channel has just died. Remove it so we never give it to any other publisher or subscriber.
   
   This does not delete the channel. Some publishers or subscribers will still hold it and will be errored
   when they try to do anything with it. 
*/
void ClientImpl::channelDied(DuplexChannel* channel) {
  if (shuttingDownFlag) {
    return;
  }

  host2topics_lock.lock();
  host2channel_lock.lock();
  topic2host_lock.lock();
  allchannels_lock.lock();
  // get host
  HostAddress addr = channel->getHostAddress();
  
  for (Host2TopicsMap::iterator iter = host2topics.find(addr); iter != host2topics.end(); ++iter) {
    topic2host.erase((*iter).second);
  }
  host2topics.erase(addr);
  host2channel.erase(addr);

  allchannels.erase(channel); // channel should be deleted here

  allchannels_lock.unlock();
  host2topics_lock.unlock();
  host2channel_lock.unlock();
  topic2host_lock.unlock();
}
