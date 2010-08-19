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

#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

const int MAX_MESSAGE_SIZE = 2*1024*1024; // 2 Meg

using namespace Hedwig;

namespace Hedwig {

  class RunnableThread {  
  public:
    RunnableThread(DuplexChannel& channel, const ChannelHandlerPtr& handler);
    virtual ~RunnableThread();
    virtual void entryPoint() = 0;
    
    void run();
    virtual void kill();
    
  protected:
    DuplexChannel& channel;
    ChannelHandlerPtr handler;
    pthread_t thread;
    pthread_attr_t attr;
  };
  
  typedef std::pair<const PubSubRequest*, OperationCallbackPtr> RequestPair;

  class PacketsAvailableCondition : public WaitConditionBase {
  public:
    PacketsAvailableCondition(std::deque<RequestPair>& queue) : queue(queue), dead(false) {
    }

    ~PacketsAvailableCondition() { wait(); }

    bool isTrue() { return dead || !queue.empty(); }
    void kill() { dead = true; }

  private:
    std::deque<RequestPair>& queue;
    bool dead;
  };

  class WriteThread : public RunnableThread {
  public: 
    WriteThread(DuplexChannel& channel, int socketfd, const ChannelHandlerPtr& handler);
    
    void entryPoint();
    void writeRequest(const PubSubRequest& m, const OperationCallbackPtr& callback);
    virtual void kill();

    ~WriteThread();
    
  private:
    int socketfd;

    PacketsAvailableCondition packetsAvailableWaitCondition;
    Mutex queueMutex;
    std::deque<RequestPair> requestQueue;
    bool dead;
  };
  
  class ReadThread : public RunnableThread {
  public:
    ReadThread(DuplexChannel& channel, int socketfd, const ChannelHandlerPtr& handler);
    
    void entryPoint();
    
    ~ReadThread();
    
  private:    
    int socketfd;
  };
}

DuplexChannel::DuplexChannel(const HostAddress& addr, const Configuration& cfg, const ChannelHandlerPtr& handler)
  : address(addr), handler(handler), writer(NULL), reader(NULL), socketfd(-1), state(UNINITIALISED), txnid2data_lock()
{
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Creating DuplexChannel(" << this << ")";
  }
}

void DuplexChannel::connect() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "DuplexChannel(" << this << ")::connect " << address.getAddressString();
  }


  socketfd = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
  
  if (-1 == socketfd) {
    LOG.errorStream() << "DuplexChannel(" << this << ") Unable to create socket";

    throw CannotCreateSocketException();
  }

  if (-1 == ::connect(socketfd, (const struct sockaddr *)&(address.socketAddress()), sizeof(struct sockaddr_in))) {
    LOG.errorStream() << "DuplexChannel(" << this << ") Could not connect socket";
    close(socketfd);

    throw CannotConnectException();
  }


  int flag = 1;
  int res = 0;
  if ((res = setsockopt(socketfd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(int))) != 0){
    close(socketfd);
    LOG.errorStream() << "Error setting nodelay on (" << this << ") " << res;
    throw ChannelSetupException();
  }

  reader = new ReadThread(*this, socketfd, handler);
  writer = new WriteThread(*this, socketfd, handler);

  reader->run();
  writer->run();

  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "DuplexChannel(" << this << ")::connect successful. Notifying handler.";
  }    
  state = CONNECTED;
  handler->channelConnected(this);
}

const HostAddress& DuplexChannel::getHostAddress() const {
  return address;
}

void DuplexChannel::kill() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Killing duplex channel (" << this << ")";
  }    

  destruction_lock.lock();
  if (state == CONNECTED) {
    state = DEAD;
    
    destruction_lock.unlock();
    
    if (socketfd != -1) {
      shutdown(socketfd, SHUT_RDWR);
    }
    
    if (writer) {
      writer->kill();
      delete writer;
    }
    if (reader) {
      reader->kill();
      delete reader;
    }
    if (socketfd != -1) {
      close(socketfd);
    }
  } else {
    destruction_lock.unlock();
  }
  handler = ChannelHandlerPtr(); // clear the handler in case it ever referenced the channel
}

DuplexChannel::~DuplexChannel() {
  /** If we are going away, fail all transactions that haven't been completed */
  failAllTransactions();
  kill();


  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Destroying DuplexChannel(" << this << ")";
  }    
}

void DuplexChannel::writeRequest(const PubSubRequest& m, const OperationCallbackPtr& callback) {
  if (state != CONNECTED) {
    LOG.errorStream() << "Tried to write transaction [" << m.txnid() << "] to a channel [" << this << "] which is " << (state == DEAD ? "DEAD" : "UNINITIALISED");
    callback->operationFailed(UninitialisedChannelException());
  }
			      
  writer->writeRequest(m, callback);
}

/**
   Store the transaction data for a request.
*/
void DuplexChannel::storeTransaction(const PubSubDataPtr& data) {
  txnid2data_lock.lock();
  txnid2data[data->getTxnId()] = data;
  txnid2data_lock.unlock();;
}

/**
   Give the transaction back to the caller. 
*/
PubSubDataPtr DuplexChannel::retrieveTransaction(long txnid) {
  txnid2data_lock.lock();
  PubSubDataPtr data = txnid2data[txnid];
  txnid2data.erase(txnid);
  txnid2data_lock.unlock();
  return data;
}

void DuplexChannel::failAllTransactions() {
  txnid2data_lock.lock();
  for (TransactionMap::iterator iter = txnid2data.begin(); iter != txnid2data.end(); ++iter) {
    PubSubDataPtr& data = (*iter).second;
    data->getCallback()->operationFailed(ChannelDiedException());
  }
  txnid2data.clear();
  txnid2data_lock.unlock();
}

/** 
Entry point for pthread initialisation
*/
void* ThreadEntryPoint(void *obj) {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Thread entered (" << obj << ")";
  }

  RunnableThread* thread = (RunnableThread*) obj;
  thread->entryPoint();
}
 
RunnableThread::RunnableThread(DuplexChannel& channel, const ChannelHandlerPtr& handler) 
  : channel(channel), handler(handler)
{
  //  pthread_cond_init(&deathlock, NULL);
}

void RunnableThread::run() {
  int ret;

  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Running thread (" << this << ")";
  }    
  
  pthread_attr_init(&attr);
  ret = pthread_create(&thread, &attr, ThreadEntryPoint, this);
  if (ret != 0) {
    LOG.errorStream() << "Error creating thread (" << this << "). Notifying handler.";
    handler->exceptionOccurred(&channel, ChannelThreadException());
  }
}

void RunnableThread::kill() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Killing thread (" << this << ")";
  }    

  pthread_cancel(thread);
  pthread_join(thread, NULL);

  pthread_attr_destroy(&attr);
}

RunnableThread::~RunnableThread() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Deleting thread (" << this << ")";
  }    
}
/**
Writer thread
*/
WriteThread::WriteThread(DuplexChannel& channel, int socketfd, const ChannelHandlerPtr& handler) 
  : RunnableThread(channel, handler), socketfd(socketfd), packetsAvailableWaitCondition(requestQueue), queueMutex(), dead(false) {
  
}

// should probably be using a queue here.
void WriteThread::writeRequest(const PubSubRequest& m, const OperationCallbackPtr& callback) {
  #warning "you should validate these inputs"
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Adding message to queue " << &m;
  }
  packetsAvailableWaitCondition.lock();
  queueMutex.lock();
  requestQueue.push_back(RequestPair(&m, callback));
  queueMutex.unlock();;

  packetsAvailableWaitCondition.signalAndUnlock();
}
  
void WriteThread::entryPoint() {
  while (true) {
    packetsAvailableWaitCondition.wait();

    if (dead) {
      if (LOG.isDebugEnabled()) {
	LOG.debugStream() << "returning from thread " << this;
      }
      return;
    }
    while (!requestQueue.empty()) { 
      queueMutex.lock();;
      RequestPair currentRequest = requestQueue.front();;
      requestQueue.pop_front();
      queueMutex.unlock();
      if (LOG.isDebugEnabled()) {
	LOG.debugStream() << "Writing message to socket " << currentRequest.first;
      }
      
      uint32_t size = htonl(currentRequest.first->ByteSize());
      write(socketfd, &size, sizeof(size));
      
      bool res = currentRequest.first->SerializeToFileDescriptor(socketfd);
      
      if (!res || errno != 0) {
	LOG.errorStream() << "Error writing to socket (" << this << ") errno(" << errno << ") res(" << res << "). Disconnected.";
	ChannelWriteException e;
	
	currentRequest.second->operationFailed(e);
	channel.kill(); // make sure it's dead
	handler->channelDisconnected(&channel, e);
	
	return;
      } else {
	currentRequest.second->operationComplete();
      }
    }  
  }
}

void WriteThread::kill() {
  dead = true;
  packetsAvailableWaitCondition.lock();
  packetsAvailableWaitCondition.kill();
  packetsAvailableWaitCondition.signalAndUnlock();
  
  RunnableThread::kill();
}

WriteThread::~WriteThread() {
  queueMutex.unlock();
}

/**
Reader Thread
*/

ReadThread::ReadThread(DuplexChannel& channel, int socketfd, const ChannelHandlerPtr& handler) 
  : RunnableThread(channel, handler), socketfd(socketfd) {
}
  
void ReadThread::entryPoint() {
  PubSubResponse* response = new PubSubResponse();
  uint8_t* dataarray = NULL;//(uint8_t*)malloc(MAX_MESSAGE_SIZE); // shouldn't be allocating every time. check that there's a max size
  int currentbufsize = 0;
  
  while (true) {
    uint32_t size = 0;
    int bytesread = 0;

    bytesread = read(socketfd, &size, sizeof(size));
    size = ntohl(size);
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Start reading packet of size: " << size;
    }
    if (bytesread < 1 || size > MAX_MESSAGE_SIZE) {
      LOG.errorStream() << "Zero read from socket or unreasonable size read, size(" << size << ") errno(" << errno << ") " << strerror(errno);
      channel.kill(); // make sure it's dead
      handler->channelDisconnected(&channel, ChannelReadException());
      break;
    }

    if (currentbufsize < size) {
      dataarray = (uint8_t*)realloc(dataarray, size);
    }
    if (dataarray == NULL) {
      LOG.errorStream() << "Error allocating input buffer of size " << size << " errno(" << errno << ") " << strerror(errno);
      channel.kill(); // make sure it's dead
      handler->channelDisconnected(&channel, ChannelReadException());
      
      break;
    }
    
    memset(dataarray, 0, size);
    bytesread = read(socketfd, dataarray, size);
    bool res = response->ParseFromArray(dataarray, size);


    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "Packet read ";
    }
    
    if (!res && errno != 0 || bytesread < size) {
      LOG.errorStream() << "Error reading from socket (" << this << ") errno(" << errno << ") res(" << res << "). Disconnected.";
      channel.kill(); // make sure it's dead
      handler->channelDisconnected(&channel, ChannelReadException());

      break;
    } else {
      handler->messageReceived(&channel, *response);
    }
  }
  free(dataarray);
  delete response;
}

ReadThread::~ReadThread() {
}
