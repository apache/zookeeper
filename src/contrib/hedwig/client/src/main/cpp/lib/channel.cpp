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
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <iostream>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <vector>
#include <utility>
#include <deque>
#include "channel.h"
#include "util.h"
#include "clientimpl.h"

#include <log4cxx/logger.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

using namespace Hedwig;

DuplexChannel::DuplexChannel(EventDispatcher& dispatcher, const HostAddress& addr, 
			     const Configuration& cfg, const ChannelHandlerPtr& handler)
  : dispatcher(dispatcher), address(addr), handler(handler), 
    socket(dispatcher.getService()), instream(&in_buf), copy_buf(NULL), copy_buf_length(0),
    state(UNINITIALISED), receiving(false), sending(false)
{
  LOG4CXX_DEBUG(logger, "Creating DuplexChannel(" << this << ")");
}

/*static*/ void DuplexChannel::connectCallbackHandler(DuplexChannelPtr channel,
						      const boost::system::error_code& error) {
  LOG4CXX_DEBUG(logger,"DuplexChannel::connectCallbackHandler error(" << error 
		<< ") channel(" << channel.get() << ")");

  if (error) {
    channel->channelDisconnected(ChannelConnectException());
    channel->setState(DEAD);
    return;
  }

  channel->setState(CONNECTED);

  boost::system::error_code ec;
  boost::asio::ip::tcp::no_delay option(true);

  channel->socket.set_option(option, ec);
  if (ec) {
    channel->channelDisconnected(ChannelSetupException());
    channel->setState(DEAD);
    return;
  } 
  
  channel->startSending();
  channel->startReceiving();
}

void DuplexChannel::connect() {  
  setState(CONNECTING);

  boost::asio::ip::tcp::endpoint endp(boost::asio::ip::address_v4(address.ip()), address.port());
  boost::system::error_code error = boost::asio::error::host_not_found;

  socket.async_connect(endp, boost::bind(&DuplexChannel::connectCallbackHandler, 
					 shared_from_this(), 
					 boost::asio::placeholders::error)); 
}

/*static*/ void DuplexChannel::messageReadCallbackHandler(DuplexChannelPtr channel, 
							  std::size_t message_size,
							  const boost::system::error_code& error, 
							  std::size_t bytes_transferred) {
  LOG4CXX_DEBUG(logger, "DuplexChannel::messageReadCallbackHandler " << error << ", " 
		<< bytes_transferred << " channel(" << channel.get() << ")");
		  
  if (error) {
    LOG4CXX_ERROR(logger, "Invalid read error (" << error << ") bytes_transferred (" 
		  << bytes_transferred << ") channel(" << channel.get() << ")");
    channel->channelDisconnected(ChannelReadException());
    return;
  }

  if (channel->copy_buf_length < message_size) {
    channel->copy_buf_length = message_size;
    channel->copy_buf = (char*)realloc(channel->copy_buf, channel->copy_buf_length);
    if (channel->copy_buf == NULL) {
      LOG4CXX_ERROR(logger, "Error allocating buffer. channel(" << channel.get() << ")");
      return;
    }
  }
  
  channel->instream.read(channel->copy_buf, message_size);
  PubSubResponsePtr response(new PubSubResponse());
  bool err = response->ParseFromArray(channel->copy_buf, message_size);


  if (!err) {
    LOG4CXX_ERROR(logger, "Error parsing message. channel(" << channel.get() << ")");

    channel->channelDisconnected(ChannelReadException());
    return;
  } else {
    LOG4CXX_DEBUG(logger,  "channel(" << channel.get() << ") : " << channel->in_buf.size() 
		  << " bytes left in buffer");
  }

  ChannelHandlerPtr h;
  {
    boost::shared_lock<boost::shared_mutex> lock(channel->destruction_lock);
    if (channel->handler.get()) {
      h = channel->handler;
    }
  }
  if (h.get()) {
    h->messageReceived(channel, response);
  }

  DuplexChannel::readSize(channel);
}

/*static*/ void DuplexChannel::sizeReadCallbackHandler(DuplexChannelPtr channel, 
						       const boost::system::error_code& error, 
						       std::size_t bytes_transferred) {
  LOG4CXX_DEBUG(logger, "DuplexChannel::sizeReadCallbackHandler " << error << ", " 
		<< bytes_transferred << " channel(" << channel.get() << ")");

  if (error) {
    LOG4CXX_ERROR(logger, "Invalid read error (" << error << ") bytes_transferred (" 
		  << bytes_transferred << ") channel(" << channel.get() << ")");
    channel->channelDisconnected(ChannelReadException());
    return;
  }
  
  if (channel->in_buf.size() < sizeof(uint32_t)) {
    LOG4CXX_ERROR(logger, "Not enough data in stream. Must have been an error reading. " 
		  << " Closing channel(" << channel.get() << ")");
    channel->channelDisconnected(ChannelReadException());
    return;
  }

  uint32_t size;
  std::istream is(&channel->in_buf);
  is.read((char*)&size, sizeof(uint32_t));
  size = ntohl(size);

  int toread = size - channel->in_buf.size();
  LOG4CXX_DEBUG(logger, " size of incoming message " << size << ", currently in buffer " 
		<< channel->in_buf.size() << " channel(" << channel.get() << ")");
  if (toread <= 0) {
    DuplexChannel::messageReadCallbackHandler(channel, size, error, 0);
  } else {
    boost::asio::async_read(channel->socket, channel->in_buf,
			    boost::asio::transfer_at_least(toread),
			    boost::bind(&DuplexChannel::messageReadCallbackHandler, 
					channel, size,
					boost::asio::placeholders::error, 
					boost::asio::placeholders::bytes_transferred));
  }
}

/*static*/ void DuplexChannel::readSize(DuplexChannelPtr channel) {
  if (!channel->isReceiving()) {
    return;
  }

  int toread = sizeof(uint32_t) - channel->in_buf.size();
  LOG4CXX_DEBUG(logger, " size of incoming message " << sizeof(uint32_t) 
		<< ", currently in buffer " << channel->in_buf.size() 
		<< " channel(" << channel.get() << ")");

  if (toread < 0) {
    DuplexChannel::sizeReadCallbackHandler(channel, boost::system::error_code(), 0);
  } else {
    //  in_buf_size.prepare(sizeof(uint32_t));
    boost::asio::async_read(channel->socket, channel->in_buf, 
			    boost::asio::transfer_at_least(sizeof(uint32_t)),
			    boost::bind(&DuplexChannel::sizeReadCallbackHandler, 
					channel, 
					boost::asio::placeholders::error, 
					boost::asio::placeholders::bytes_transferred));
  }
}

void DuplexChannel::startReceiving() {
  LOG4CXX_DEBUG(logger, "DuplexChannel::startReceiving channel(" << this << ") currently receiving = " << receiving);
  
  boost::lock_guard<boost::mutex> lock(receiving_lock);
  if (receiving) {
    return;
  } 
  receiving = true;
  
  DuplexChannel::readSize(shared_from_this());
}

bool DuplexChannel::isReceiving() {
  return receiving;
}

void DuplexChannel::stopReceiving() {
  LOG4CXX_DEBUG(logger, "DuplexChannel::stopReceiving channel(" << this << ")");
  
  boost::lock_guard<boost::mutex> lock(receiving_lock);
  receiving = false;
}

void DuplexChannel::startSending() {
  {
    boost::shared_lock<boost::shared_mutex> lock(state_lock);
    if (state != CONNECTED) {
      return;
    }
  }

  boost::lock_guard<boost::mutex> lock(sending_lock);
  if (sending) {
    return;
  }
  LOG4CXX_DEBUG(logger, "DuplexChannel::startSending channel(" << this << ")");
  
  WriteRequest w;
  { 
    boost::lock_guard<boost::mutex> lock(write_lock);
    if (write_queue.empty()) {
      return;
    }
    w = write_queue.front();
    write_queue.pop_front();
  }

  sending = true;

  std::ostream os(&out_buf);
  uint32_t size = htonl(w.first->ByteSize());
  os.write((char*)&size, sizeof(uint32_t));
  
  bool err = w.first->SerializeToOstream(&os);
  if (!err) {
    w.second->operationFailed(ChannelWriteException());
    channelDisconnected(ChannelWriteException());
    return;
  }

  boost::asio::async_write(socket, out_buf, 
			   boost::bind(&DuplexChannel::writeCallbackHandler, 
				       shared_from_this(), 
				       w.second,
				       boost::asio::placeholders::error, 
				       boost::asio::placeholders::bytes_transferred));
}


const HostAddress& DuplexChannel::getHostAddress() const {
  return address;
}

void DuplexChannel::channelDisconnected(const std::exception& e) {
  setState(DEAD);
  
  {
    boost::lock_guard<boost::mutex> lock(write_lock);
    while (!write_queue.empty()) {
      WriteRequest w = write_queue.front();
      write_queue.pop_front();
      w.second->operationFailed(e);
    }
  }

  ChannelHandlerPtr h;
  {
    boost::shared_lock<boost::shared_mutex> lock(destruction_lock);
    if (handler.get()) {
      h = handler;
    }
  }
  if (h.get()) {
    h->channelDisconnected(shared_from_this(), e);
  }
}

void DuplexChannel::kill() {
  LOG4CXX_DEBUG(logger, "Killing duplex channel (" << this << ")");
    
  bool connected = false;
  {
    boost::shared_lock<boost::shared_mutex> statelock(state_lock);
    connected = (state == CONNECTING || state == CONNECTED);
  }

  boost::lock_guard<boost::shared_mutex> lock(destruction_lock);
  if (connected) {
    setState(DEAD);
    
    socket.cancel();
    socket.shutdown(boost::asio::ip::tcp::socket::shutdown_both);
    socket.close();
  }
  handler = ChannelHandlerPtr(); // clear the handler in case it ever referenced the channel*/
}

DuplexChannel::~DuplexChannel() {
  /** If we are going away, fail all transactions that haven't been completed */
  failAllTransactions();
  kill();
  free(copy_buf);
  copy_buf = NULL;
  copy_buf_length = 0;

  LOG4CXX_DEBUG(logger, "Destroying DuplexChannel(" << this << ")");
}

/*static*/ void DuplexChannel::writeCallbackHandler(DuplexChannelPtr channel, OperationCallbackPtr callback,
						    const boost::system::error_code& error, 
						    std::size_t bytes_transferred) {
  LOG4CXX_DEBUG(logger, "DuplexChannel::writeCallbackHandler " << error << ", " 
		<< bytes_transferred << " channel(" << channel.get() << ")");

  if (error) {
    callback->operationFailed(ChannelWriteException());
    channel->channelDisconnected(ChannelWriteException());
    return;
  }

  callback->operationComplete();

  channel->out_buf.consume(bytes_transferred);

  {
    boost::lock_guard<boost::mutex> lock(channel->sending_lock);
    channel->sending = false;
  }

  channel->startSending();
}

void DuplexChannel::writeRequest(const PubSubRequestPtr& m, const OperationCallbackPtr& callback) {
  LOG4CXX_DEBUG(logger, "DuplexChannel::writeRequest channel(" << this << ") txnid(" 
		<< m->txnid() << ") shouldClaim("<< m->has_shouldclaim() << ", " 
		<< m->shouldclaim() << ")");

  {
    boost::shared_lock<boost::shared_mutex> lock(state_lock);
    if (state != CONNECTED && state != CONNECTING) {
      LOG4CXX_ERROR(logger,"Tried to write transaction [" << m->txnid() << "] to a channel [" 
		    << this << "] which is " << (state == DEAD ? "DEAD" : "UNINITIALISED"));
      callback->operationFailed(UninitialisedChannelException());
    }
  }

  { 
    boost::lock_guard<boost::mutex> lock(write_lock);
    WriteRequest w(m, callback);
    write_queue.push_back(w);
  }

  startSending();
}

/**
   Store the transaction data for a request.
*/
void DuplexChannel::storeTransaction(const PubSubDataPtr& data) {
  LOG4CXX_DEBUG(logger, "Storing txnid(" << data->getTxnId() << ") for channel(" << this << ")");

  boost::lock_guard<boost::mutex> lock(txnid2data_lock);
  txnid2data[data->getTxnId()] = data;
}

/**
   Give the transaction back to the caller. 
*/
PubSubDataPtr DuplexChannel::retrieveTransaction(long txnid) {
  boost::lock_guard<boost::mutex> lock(txnid2data_lock);

  PubSubDataPtr data = txnid2data[txnid];
  txnid2data.erase(txnid);
  if (data == NULL) {
    LOG4CXX_ERROR(logger, "Transaction txnid(" << txnid 
		  << ") doesn't exist in channel (" << this << ")");
  }

  return data;
}

void DuplexChannel::failAllTransactions() {
  boost::lock_guard<boost::mutex> lock(txnid2data_lock);
  for (TransactionMap::iterator iter = txnid2data.begin(); iter != txnid2data.end(); ++iter) {
    PubSubDataPtr& data = (*iter).second;
    data->getCallback()->operationFailed(ChannelDiedException());
  }
  txnid2data.clear();
}

void DuplexChannel::setState(State s) {
  boost::lock_guard<boost::shared_mutex> lock(state_lock);
  state = s;
}
