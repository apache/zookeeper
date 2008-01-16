/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ZKMOCKS_H_
#define ZKMOCKS_H_

#include <zookeeper.h>
#include "src/zk_adaptor.h"

#include "MocksBase.h"
#include "Util.h"
#include "LibCMocks.h"

struct CloseFinally{
    CloseFinally(zhandle_t** zh):zh_(zh){}
    ~CloseFinally(){
        if(zh_==0)return;
        zookeeper_close(*zh_);
        *zh_=0;
    }
    void disarm(){zh_=0;}
    zhandle_t ** zh_;
};

struct TestClientId: clientid_t{
    static const int SESSION_ID=123456789;
    static const char* PASSWD;
    TestClientId(){
        client_id=SESSION_ID;
        memcpy(passwd,PASSWD,sizeof(passwd));
    }
};

extern TestClientId testClientId;

struct HandshakeRequest: public connect_req
{
    static HandshakeRequest* parse(const std::string& buf);
    static bool isValid(const std::string& buf){
        // this is just quick and dirty check before we go and parse the request
        return buf.size()==HANDSHAKE_REQ_SIZE;
    }
};

// *****************************************************************************
// flush_send_queue

class Mock_flush_send_queue: public Mock
{
public:
    Mock_flush_send_queue():counter(0),callReturns(ZOK){mock_=this;}
    ~Mock_flush_send_queue(){mock_=0;}
    
    int counter;
    int callReturns;
    virtual int call(zhandle_t* zh, int timeout){
        counter++;
        return callReturns;
    }

    static Mock_flush_send_queue* mock_;
};

// *****************************************************************************
// There is a problem with the existing Sync API: a watcher can be called before
// the init function returned. It makes it impossible to set the watcher context
// properly, if you want to use the zhandle in the watcher. 
// This class is a dirty trick to get the newly allocated zhandle_t pointer 
// before zoookeeper_init returned. 

class GetZHandleBeforInitReturned: public Mock
{
public:
    GetZHandleBeforInitReturned():ptr(0){mock_=this;}
    ~GetZHandleBeforInitReturned(){mock_=0;}
    
    zhandle_t* ptr;
    virtual int call(zhandle_t *zh);

    static GetZHandleBeforInitReturned* mock_;
};

// *****************************************************************************
// a zookeeper Stat wrapper
struct NodeStat: public Stat
{
    NodeStat(){
        czxid=0;
        mzxid=0;
        ctime=0;
        mtime=0;
        version=1;
        cversion=0;
        aversion=0;
        ephemeralOwner=0;
    }
};

// *****************************************************************************
// Abstract server Response
class Response
{
public:
    virtual ~Response(){}
    
    virtual void setXID(int32_t xid) =0;
    virtual std::string toString() const =0;
};

// *****************************************************************************
// Handshake response
class HandshakeResponse: public Response
{
public:
    HandshakeResponse(int64_t sessId=1)
        :protocolVersion(1),timeOut(10000),sessionId(sessId),passwd_len(sizeof(passwd))
    {
        memcpy(passwd,"1234567890123456",sizeof(passwd));
    }
    int32_t protocolVersion;
    int32_t timeOut;
    int64_t sessionId;
    int32_t passwd_len;
    char passwd[16];
    virtual std::string toString() const ;
    virtual void setXID(int32_t xid) {/* no-op */}
};

// zoo_get() response
class ZooGetResponse: public Response
{
public:
    ZooGetResponse(char* data, int len,int rc=ZOK,const Stat& stat=NodeStat())
        :xid_(0),data_(data,len),rc_(rc),stat_(stat)
    {
    }
    virtual std::string toString() const;
    virtual void setXID(int32_t xid) {xid_=xid;}
    
private:
    int32_t xid_;
    std::string data_;
    int rc_;
    Stat stat_;
};

// ****************************************************************************
// Zookeeper server simulator

class ZookeeperServer: public Mock_socket
{
public:
    ZookeeperServer():
        serverDownSkipCount_(-1),sessionExpired(false),connectionLost(false)
    {
        connectReturns=-1;
        connectErrno=EWOULDBLOCK;        
    }
    virtual ~ZookeeperServer(){
        clearRecvQueue();
        clearRespQueue();
    }
    virtual int callClose(int fd){
        if(fd!=FD)
            return LIBC_SYMBOLS.close(fd);
        clearRecvQueue();
        clearRespQueue();
        return Mock_socket::callClose(fd);
    }
    // connection handling
    // what to do when the handshake request comes in?
    int serverDownSkipCount_;
    // this will cause getsockopt(zh->fd,SOL_SOCKET,SO_ERROR,&error,&len) return 
    // a failure after skipCount dropped to zero, thus simulating a server down 
    // condition
    // passing skipCount==-1 will make every connect attempt succeed
    void setServerDown(int skipCount=0){ 
        serverDownSkipCount_=skipCount;
        optvalSO_ERROR=0;            
    }
    virtual void setSO_ERROR(void *optval,socklen_t len){
        if(serverDownSkipCount_!=-1){
            if(serverDownSkipCount_==0)
                optvalSO_ERROR=ECONNREFUSED;
            else
                serverDownSkipCount_--;
        }
        Mock_socket::setSO_ERROR(optval,len);
    }

    // this is a trigger that gets reset back to false
    // a connect request will return a non-matching session id thus causing 
    // the client throw SESSION_EXPIRED
    bool sessionExpired;
    void returnSessionExpired(){ sessionExpired=true; }
    
    // this is a trigger that gets reset back to false
    // next recv call will return 0 length, thus simulating a connecton loss
    bool connectionLost;
    void setConnectionLost() {connectionLost=true;}
    
    // recv
    typedef std::pair<Response*,int> Element;
    typedef std::deque<Element> ResponseList;
    ResponseList recvQueue;
    mutable Mutex recvQMx;
    ZookeeperServer& addRecvResponse(Response* resp, int errnum=0){
        synchronized(recvQMx);
        recvQueue.push_back(Element(resp,errnum));
        return *this;
    }
    ZookeeperServer& addRecvResponse(int errnum){
        synchronized(recvQMx);
        recvQueue.push_back(Element(0,errnum));
        return *this;
    }
    ZookeeperServer& addRecvResponse(const Element& e){
        synchronized(recvQMx);
        recvQueue.push_back(e);
        return *this;
    }
    void clearRecvQueue(){
        synchronized(recvQMx);
        for(unsigned i=0; i<recvQueue.size();i++)
            delete recvQueue[i].first;
        recvQueue.clear();
    }
    virtual ssize_t callRecv(int s,void *buf,size_t len,int flags);
    virtual bool hasMoreRecv() const{
        synchronized(recvQMx);
        return recvQueue.size()!=0;
    }
    
    // send operation doesn't try to match request to the response
    ResponseList respQueue;
    mutable Mutex respQMx;
    ZookeeperServer& addOperationResponse(Response* resp, int errnum=0){
        synchronized(respQMx);
        respQueue.push_back(Element(resp,errnum));
        return *this;
    }
    void clearRespQueue(){
        synchronized(respQMx);
        for(unsigned i=0; i<respQueue.size();i++)
            delete respQueue[i].first;
        respQueue.clear();
    }
    virtual void notifyBufferSent(const std::string& buffer);
};

#endif /*ZKMOCKS_H_*/
