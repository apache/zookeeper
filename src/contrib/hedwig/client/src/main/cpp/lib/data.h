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

#ifndef DATA_H
#define DATA_H

#include <hedwig/protocol.h>
#include <hedwig/callback.h>

#include <pthread.h>
#include <tr1/unordered_set>
#include "util.h"
#include <boost/shared_ptr.hpp>
#include <boost/thread/mutex.hpp>

namespace Hedwig {
  /**
     Simple counter for transaction ids from the client
  */
  class ClientTxnCounter {
  public:
    ClientTxnCounter();
    ~ClientTxnCounter();
    long next();
    
  private:
    long counter;
    boost::mutex mutex;
  };

  class PubSubData;
  typedef boost::shared_ptr<PubSubData> PubSubDataPtr;
  typedef boost::shared_ptr<PubSubRequest> PubSubRequestPtr;
  typedef boost::shared_ptr<PubSubResponse> PubSubResponsePtr;

  /**
     Data structure to hold information about requests and build request messages.
     Used to store requests which may need to be resent to another server. 
   */
  class PubSubData {
  public:
    // to be used for publish
    static PubSubDataPtr forPublishRequest(long txnid, const std::string& topic, const std::string& body, const OperationCallbackPtr& callback);
    static PubSubDataPtr forSubscribeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const OperationCallbackPtr& callback, SubscribeRequest::CreateOrAttach mode);
    static PubSubDataPtr forUnsubscribeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const OperationCallbackPtr& callback);
    static PubSubDataPtr forConsumeRequest(long txnid, const std::string& subscriberid, const std::string& topic, const MessageSeqId msgid);

    ~PubSubData();

    OperationType getType() const;
    long getTxnId() const;
    const std::string& getSubscriberId() const;
    const std::string& getTopic() const;
    const std::string& getBody() const;
    const MessageSeqId getMessageSeqId() const;

    void setShouldClaim(bool shouldClaim);

    const PubSubRequestPtr getRequest();
    void setCallback(const OperationCallbackPtr& callback);
    OperationCallbackPtr& getCallback();
    SubscribeRequest::CreateOrAttach getMode() const;

    void addTriedServer(HostAddress& h);
    bool hasTriedServer(HostAddress& h);
    void clearTriedServers();
  private:

    PubSubData();
    
    OperationType type;
    long txnid;
    std::string subscriberid;
    std::string topic;
    std::string body;
    bool shouldClaim;
    OperationCallbackPtr callback;
    SubscribeRequest::CreateOrAttach mode;
    MessageSeqId msgid;
    std::tr1::unordered_set<HostAddress, HostAddressHash > triedservers;
  };
  
};
#endif
