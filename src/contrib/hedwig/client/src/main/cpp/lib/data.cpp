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

#include <hedwig/protocol.h>
#include "data.h"

#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

using namespace Hedwig;

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

PubSubData::PubSubData() : shouldClaim(false) {  
}

PubSubData::~PubSubData() {
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

const MessageSeqId PubSubData::getMessageSeqId() const {
  return msgid;
}

const PubSubRequestPtr PubSubData::getRequest() {
  PubSubRequestPtr request(new Hedwig::PubSubRequest());
  request->set_protocolversion(Hedwig::VERSION_ONE);
  request->set_type(type);
  request->set_txnid(txnid);
  if (shouldClaim) {
    request->set_shouldclaim(shouldClaim);
  }
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

  return request;
}

void PubSubData::setShouldClaim(bool shouldClaim) {
  this->shouldClaim = shouldClaim;
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
