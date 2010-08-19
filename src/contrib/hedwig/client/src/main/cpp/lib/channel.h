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
#ifndef HEDWIG_CHANNEL_H
#define HEDWIG_CHANNEL_H

#include <hedwig/protocol.h>
#include <hedwig/callback.h>
#include <hedwig/client.h>
#include "util.h"
#include "data.h"
#include <tr1/memory>
#include <tr1/unordered_map>

namespace Hedwig {
  class ChannelException : public std::exception { };
  class UninitialisedChannelException : public ChannelException {};

  class ChannelConnectException : public ChannelException {};
  class CannotCreateSocketException : public ChannelConnectException {};
  class ChannelSetupException : public ChannelConnectException {};

  class ChannelDiedException : public ChannelException {};

  class ChannelWriteException : public ChannelException {};
  class ChannelReadException : public ChannelException {};
  class ChannelThreadException : public ChannelException {};


  class ChannelHandler {
  public:
    virtual void messageReceived(DuplexChannel* channel, const PubSubResponse& m) = 0;
    virtual void channelConnected(DuplexChannel* channel) = 0;

    virtual void channelDisconnected(DuplexChannel* channel, const std::exception& e) = 0;
    virtual void exceptionOccurred(DuplexChannel* channel, const std::exception& e) = 0;

    virtual ~ChannelHandler() {}
  };
  typedef std::tr1::shared_ptr<ChannelHandler> ChannelHandlerPtr;

  class WriteThread;
  class ReadThread;

  class DuplexChannel {
  public:
    DuplexChannel(const HostAddress& addr, const Configuration& cfg, const ChannelHandlerPtr& handler);

    void connect();

    void writeRequest(const PubSubRequest& m, const OperationCallbackPtr& callback);
    
    const HostAddress& getHostAddress() const;

    void storeTransaction(const PubSubDataPtr& data);
    PubSubDataPtr retrieveTransaction(long txnid);
    void failAllTransactions();
    
    virtual void kill();

    ~DuplexChannel();
  private:
    HostAddress address;
    ChannelHandlerPtr handler;
    int socketfd;
    WriteThread *writer;
    ReadThread *reader;
    
    enum State { UNINITIALISED, CONNECTED, DEAD };
    State state;
    
    typedef std::tr1::unordered_map<long, PubSubDataPtr> TransactionMap;
    TransactionMap txnid2data;
    Mutex txnid2data_lock;
    Mutex destruction_lock;
  };
  
  typedef std::tr1::shared_ptr<DuplexChannel> DuplexChannelPtr;
};

namespace std 
{
  namespace tr1 
  {
  // defined in util.cpp
  template <> struct hash<Hedwig::DuplexChannelPtr> : public unary_function<Hedwig::DuplexChannelPtr, size_t> {
    size_t operator()(const Hedwig::DuplexChannelPtr& channel) const;
  };
  }
};
#endif
