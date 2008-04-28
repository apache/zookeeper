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

#include "Util.h"
#include "LibCMocks.h"
#include "MocksBase.h"

// *****************************************************************************
// sets internal zhandle_t members to certain values to simulate the client 
// connected state. This function should only be used with the single-threaded
// Async API tests!
void forceConnected(zhandle_t* zh); 

// *****************************************************************************
// Abstract watcher action
struct SyncedBoolCondition;

class WatcherAction{
public:
    WatcherAction():triggered_(false){}
    virtual ~WatcherAction(){}
    
    virtual void onSessionExpired(zhandle_t*){}
    virtual void onNodeValueChanged(zhandle_t*,const char* path){}
    
    SyncedBoolCondition isWatcherTriggered() const;
    void setWatcherTriggered(){
        synchronized(mx_);
        triggered_=true;
    }

protected:
    mutable Mutex mx_;
    bool triggered_;
};
// zh->context is a pointer to a WatcherAction instance
// based on the event type and state, the watcher calls a specific watcher 
// action method
void activeWatcher(zhandle_t *zh, int type, int state, const char *path);

// *****************************************************************************
// a set of async completion signatures
class AsyncCompletion{
public:
    virtual ~AsyncCompletion(){}
    virtual void aclCompl(int rc, ACL_vector *acl,Stat *stat){}
    virtual void dataCompl(int rc, const char *value, int len, const Stat *stat){}
    virtual void statCompl(int rc, const Stat *stat){}
    virtual void stringCompl(int rc, const char *value){}
    virtual void stringsCompl(int rc,const String_vector *strings){}
    virtual void voidCompl(int rc){}
};
void asyncCompletion(int rc, ACL_vector *acl,Stat *stat, const void *data);
void asyncCompletion(int rc, const char *value, int len, const Stat *stat, 
        const void *data);
void asyncCompletion(int rc, const Stat *stat, const void *data);
void asyncCompletion(int rc, const char *value, const void *data);
void asyncCompletion(int rc,const String_vector *strings, const void *data);
void asyncCompletion(int rc, const void *data);

// *****************************************************************************
// some common predicates to use with ensureCondition():
// checks if the connection is established
struct ClientConnected{
    ClientConnected(zhandle_t* zh):zh_(zh){}
    bool operator()() const{
        return zoo_state(zh_)==CONNECTED_STATE;
    }
    zhandle_t* zh_;
};
// check in the session expired
struct SessionExpired{
    SessionExpired(zhandle_t* zh):zh_(zh){}
    bool operator()() const{
        return zoo_state(zh_)==EXPIRED_SESSION_STATE;
    }
    zhandle_t* zh_;
};
// checks if the IO thread has stopped; CheckedPthread must be active
struct IOThreadStopped{
    IOThreadStopped(zhandle_t* zh):zh_(zh){}
    bool operator()() const;
    zhandle_t* zh_;
};

struct SyncedBoolCondition{
    SyncedBoolCondition(const bool& cond,Mutex& mx):cond_(cond),mx_(mx){}
    bool operator()() const{
        synchronized(mx_);
        return cond_;
    }
    const bool& cond_;
    Mutex& mx_;
};
// *****************************************************************************
// make sure to call zookeeper_close() even in presence of exceptions 
struct CloseFinally{
    CloseFinally(zhandle_t** zh):zh_(zh){}
    ~CloseFinally(){
        execute();
    }
    int execute(){
        if(zh_==0)return ZOK;
        zhandle_t* lzh=*zh_;
        *zh_=0;
        disarm();
        return zookeeper_close(lzh);
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

// *****************************************************************************
// special client id recongnized by the ZK server simulator 
extern TestClientId testClientId;
#define TEST_CLIENT_ID &testClientId

// *****************************************************************************
//
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
// get_xid

class Mock_get_xid: public Mock
{
public:
    static const int32_t XID=123456;
    Mock_get_xid(int retValue=XID):callReturns(retValue){mock_=this;}
    ~Mock_get_xid(){mock_=0;}
    
    int callReturns;
    virtual int call(){
        return callReturns;
    }

    static Mock_get_xid* mock_;
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
    NodeStat(const Stat& other){
        memcpy(this,&other,sizeof(*this));
    }
};

// *****************************************************************************
// Abstract server Response
class Response
{
public:
    virtual ~Response(){}
    
    virtual void setXID(int32_t xid){}
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
};

// zoo_get() response
class ZooGetResponse: public Response
{
public:
    ZooGetResponse(const char* data, int len,int32_t xid=0,int rc=ZOK,const Stat& stat=NodeStat())
        :xid_(xid),data_(data,len),rc_(rc),stat_(stat)
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

// PING response
class PingResponse: public Response
{
public:
    virtual std::string toString() const;    
};

// watcher znode event
class ZNodeEvent: public Response
{
public:
    ZNodeEvent(int type,const char* path):type_(type),path_(path){}
    
    virtual std::string toString() const;
    
private:
    int type_;
    std::string path_;
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
    // this queue is used for server responses: client's recv() system call 
    // returns next available message from this queue
    typedef std::pair<Response*,int> Element;
    typedef std::deque<Element> ResponseList;
    ResponseList recvQueue;
    mutable Mutex recvQMx;
    AtomicInt recvHasMore;
    ZookeeperServer& addRecvResponse(Response* resp, int errnum=0){
        synchronized(recvQMx);
        recvQueue.push_back(Element(resp,errnum));
        ++recvHasMore;
        return *this;
    }
    ZookeeperServer& addRecvResponse(int errnum){
        synchronized(recvQMx);
        recvQueue.push_back(Element(0,errnum));
        ++recvHasMore;
        return *this;
    }
    ZookeeperServer& addRecvResponse(const Element& e){
        synchronized(recvQMx);
        recvQueue.push_back(e);
        ++recvHasMore;
        return *this;
    }
    void clearRecvQueue(){
        synchronized(recvQMx);
        recvHasMore=0;
        for(unsigned i=0; i<recvQueue.size();i++)
            delete recvQueue[i].first;
        recvQueue.clear();
    }

    virtual ssize_t callRecv(int s,void *buf,size_t len,int flags);
    virtual bool hasMoreRecv() const;
    
    // the operation response queue holds zookeeper operation responses till the
    // operation request has been sent to the server. After that, the operation
    // response gets moved on to the recv queue (see above) ready to be read by 
    // the next recv() system call
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
    AtomicInt closeSent;
    virtual void notifyBufferSent(const std::string& buffer);
    // simulates an arrival of a client request
    // a callback to be implemented by subclasses (no-op by default)
    virtual void onMessageReceived(const RequestHeader& rh, iarchive* ia);
};

#endif /*ZKMOCKS_H_*/
