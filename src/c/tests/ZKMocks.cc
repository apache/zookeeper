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

#include <arpa/inet.h>  // for htonl
#include <memory>

#include <zookeeper.h>
#include <proto.h>

#ifdef THREADED
#include "PthreadMocks.h"
#endif
#include "ZKMocks.h"

using namespace std;

TestClientId testClientId;
const char* TestClientId::PASSWD="1234567890123456";

HandshakeRequest* HandshakeRequest::parse(const std::string& buf) {
    auto_ptr<HandshakeRequest> req(new HandshakeRequest);

    memcpy(&req->protocolVersion,buf.data(), sizeof(req->protocolVersion));
    req->protocolVersion = htonl(req->protocolVersion);

    int offset=sizeof(req->protocolVersion);

    memcpy(&req->lastZxidSeen,buf.data()+offset,sizeof(req->lastZxidSeen));
    req->lastZxidSeen = zoo_htonll(req->lastZxidSeen);
    offset+=sizeof(req->lastZxidSeen);

    memcpy(&req->timeOut,buf.data()+offset,sizeof(req->timeOut));
    req->timeOut = htonl(req->timeOut);
    offset+=sizeof(req->timeOut);

    memcpy(&req->sessionId,buf.data()+offset,sizeof(req->sessionId));
    req->sessionId = zoo_htonll(req->sessionId);
    offset+=sizeof(req->sessionId);

    memcpy(&req->passwd_len,buf.data()+offset,sizeof(req->passwd_len));
    req->passwd_len = htonl(req->passwd_len);
    offset+=sizeof(req->passwd_len);

    memcpy(req->passwd,buf.data()+offset,sizeof(req->passwd));
    offset+=sizeof(req->passwd);

    memcpy(&req->readOnly,buf.data()+offset,sizeof(req->readOnly));

    if(testClientId.client_id==req->sessionId &&
            !memcmp(testClientId.passwd,req->passwd,sizeof(req->passwd)))
        return req.release();
    // the request didn't match -- may not be a handshake request after all

    return 0;
}

// *****************************************************************************
// watcher action implementation
void activeWatcher(zhandle_t *zh,
                   int type, int state, const char *path,void* ctx) {

    if (zh == 0 || ctx == 0)
      return;

    WatcherAction* action = (WatcherAction *)ctx;

    if (type == ZOO_SESSION_EVENT) {
        if (state == ZOO_EXPIRED_SESSION_STATE)
            action->onSessionExpired(zh);
        else if(state == ZOO_CONNECTING_STATE)
            action->onConnectionLost(zh);
        else if(state == ZOO_CONNECTED_STATE)
            action->onConnectionEstablished(zh);
    } else if (type == ZOO_CHANGED_EVENT)
        action->onNodeValueChanged(zh,path);
    else if (type == ZOO_DELETED_EVENT)
        action->onNodeDeleted(zh,path);
    else if (type == ZOO_CHILD_EVENT)
        action->onChildChanged(zh,path);

    // TODO: implement for the rest of the event types

    action->setWatcherTriggered();
}

SyncedBoolCondition WatcherAction::isWatcherTriggered() const {
    return SyncedBoolCondition(triggered_,mx_);
}

// a set of async completion signatures

void asyncCompletion(int rc, ACL_vector *acl,Stat *stat, const void *data){
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->aclCompl(rc,acl,stat);
}

void asyncCompletion(int rc, const char *value, int len, const Stat *stat,
        const void *data) {
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->dataCompl(rc,value,len,stat);
}

void asyncCompletion(int rc, const Stat *stat, const void *data) {
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->statCompl(rc,stat);
}

void asyncCompletion(int rc, const char *value, const void *data) {
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->stringCompl(rc,value);
}

void asyncCompletion(int rc,const String_vector *strings, const void *data) {
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->stringsCompl(rc,strings);
}

void asyncCompletion(int rc, const void *data) {
    assert("Completion data is NULL"&&data);
    static_cast<AsyncCompletion*>((void*)data)->voidCompl(rc);
}

// a predicate implementation
bool IOThreadStopped::operator()() const{
#ifdef THREADED
    adaptor_threads* adaptor=(adaptor_threads*)zh_->adaptor_priv;
    return CheckedPthread::isTerminated(adaptor->io);
#else
    assert("IOThreadStopped predicate is only for use with THREADED client" &&
           false);
    return false;
#endif
}

//******************************************************************************
//
DECLARE_WRAPPER(int,flush_send_queue,(zhandle_t*zh, int timeout))
{
    if(!Mock_flush_send_queue::mock_)
        return CALL_REAL(flush_send_queue,(zh,timeout));
    return Mock_flush_send_queue::mock_->call(zh,timeout);
}

Mock_flush_send_queue* Mock_flush_send_queue::mock_=0;

//******************************************************************************
//
DECLARE_WRAPPER(int32_t,get_xid,())
{
    if(!Mock_get_xid::mock_)
        return CALL_REAL(get_xid,());
    return Mock_get_xid::mock_->call();
}

Mock_get_xid* Mock_get_xid::mock_=0;

//******************************************************************************
// activateWatcher mock

DECLARE_WRAPPER(void,activateWatcher,(zhandle_t *zh, watcher_registration_t* reg, int rc))
{
    if(!Mock_activateWatcher::mock_){
        CALL_REAL(activateWatcher,(zh, reg,rc));
    }else{
        Mock_activateWatcher::mock_->call(zh, reg,rc);
    }
}
Mock_activateWatcher* Mock_activateWatcher::mock_=0;

class ActivateWatcherWrapper: public Mock_activateWatcher{
public:
    ActivateWatcherWrapper():ctx_(0),activated_(false){}

    virtual void call(zhandle_t *zh, watcher_registration_t* reg, int rc){
        CALL_REAL(activateWatcher,(zh, reg,rc));
        synchronized(mx_);
        if(reg->context==ctx_){
            activated_=true;
            ctx_=0;
        }
    }

    void setContext(void* ctx){
        synchronized(mx_);
        ctx_=ctx;
        activated_=false;
    }

    SyncedBoolCondition isActivated() const{
        return SyncedBoolCondition(activated_,mx_);
    }
    mutable Mutex mx_;
    void* ctx_;
    bool activated_;
};

WatcherActivationTracker::WatcherActivationTracker():
    wrapper_(new ActivateWatcherWrapper)
{
}

WatcherActivationTracker::~WatcherActivationTracker(){
    delete wrapper_;
}

void WatcherActivationTracker::track(void* ctx){
    wrapper_->setContext(ctx);
}

SyncedBoolCondition WatcherActivationTracker::isWatcherActivated() const{
    return wrapper_->isActivated();
}

//******************************************************************************
//
DECLARE_WRAPPER(void,deliverWatchers,(zhandle_t* zh,int type,int state, const char* path, watcher_object_list_t **list))
{
    if(!Mock_deliverWatchers::mock_){
        CALL_REAL(deliverWatchers,(zh,type,state,path, list));
    }else{
        Mock_deliverWatchers::mock_->call(zh,type,state,path, list);
    }
}

Mock_deliverWatchers* Mock_deliverWatchers::mock_=0;

struct RefCounterValue{
    RefCounterValue(zhandle_t* const& zh,int32_t expectedCounter,Mutex& mx):
        zh_(zh),expectedCounter_(expectedCounter),mx_(mx){}
    bool operator()() const{
        {
            synchronized(mx_);
            if(zh_==0)
                return false;
        }
        return inc_ref_counter(zh_,0)==expectedCounter_;
    }
    zhandle_t* const& zh_;
    int32_t expectedCounter_;
    Mutex& mx_;
};


class DeliverWatchersWrapper: public Mock_deliverWatchers{
public:
    DeliverWatchersWrapper(int type,int state,bool terminate):
        type_(type),state_(state),
        allDelivered_(false),terminate_(terminate),zh_(0),deliveryCounter_(0){}
    virtual void call(zhandle_t* zh, int type, int state,
                      const char* path, watcher_object_list **list) {
        {
            synchronized(mx_);
            zh_=zh;
            allDelivered_=false;
        }
        CALL_REAL(deliverWatchers,(zh,type,state,path, list));
        if(type_==type && state_==state){
            if(terminate_){
                // prevent zhandle_t from being prematurely distroyed;
                // this will also ensure that zookeeper_close() cleanups the
                //  thread resources by calling finish_adaptor()
                inc_ref_counter(zh,1);
                terminateZookeeperThreads(zh);
            }
            synchronized(mx_);
            allDelivered_=true;
            deliveryCounter_++;
        }
    }
    SyncedBoolCondition isDelivered() const{
        if(terminate_){
            int i=ensureCondition(RefCounterValue(zh_,1,mx_),1000);
            assert(i<1000);
        }
        return SyncedBoolCondition(allDelivered_,mx_);
    }
    void resetDeliveryCounter(){
        synchronized(mx_);
        deliveryCounter_=0;
    }
    SyncedIntegerEqual deliveryCounterEquals(int expected) const{
        if(terminate_){
            int i=ensureCondition(RefCounterValue(zh_,1,mx_),1000);
            assert(i<1000);
        }
        return SyncedIntegerEqual(deliveryCounter_,expected,mx_);
    }
    int type_;
    int state_;
    mutable Mutex mx_;
    bool allDelivered_;
    bool terminate_;
    zhandle_t* zh_;
    int deliveryCounter_;
};

WatcherDeliveryTracker::WatcherDeliveryTracker(
        int type,int state,bool terminateCompletionThread):
    deliveryWrapper_(new DeliverWatchersWrapper(
            type,state,terminateCompletionThread)){
}

WatcherDeliveryTracker::~WatcherDeliveryTracker(){
    delete deliveryWrapper_;
}

SyncedBoolCondition WatcherDeliveryTracker::isWatcherProcessingCompleted() const {
    return deliveryWrapper_->isDelivered();
}

void WatcherDeliveryTracker::resetDeliveryCounter(){
    deliveryWrapper_->resetDeliveryCounter();
}

SyncedIntegerEqual WatcherDeliveryTracker::deliveryCounterEquals(int expected) const {
    return deliveryWrapper_->deliveryCounterEquals(expected);
}

//******************************************************************************
//
string HandshakeResponse::toString() const {
    string buf;
    int32_t tmp=htonl(protocolVersion);
    buf.append((char*)&tmp,sizeof(tmp));
    tmp=htonl(timeOut);
    buf.append((char*)&tmp,sizeof(tmp));
    int64_t tmp64=zoo_htonll(sessionId);
    buf.append((char*)&tmp64,sizeof(sessionId));
    tmp=htonl(passwd_len);
    buf.append((char*)&tmp,sizeof(tmp));
    buf.append(passwd,sizeof(passwd));
    buf.append(&readOnly,sizeof(readOnly));
    // finally set the buffer length
    tmp=htonl(buf.size()+sizeof(tmp));
    buf.insert(0,(char*)&tmp, sizeof(tmp));
    return buf;
}

string ZooGetResponse::toString() const{
    oarchive* oa=create_buffer_oarchive();

    ReplyHeader h = {xid_,1,ZOK};
    serialize_ReplyHeader(oa, "hdr", &h);

    GetDataResponse resp;
    char buf[1024];
    assert("GetDataResponse is too long"&&data_.size()<=sizeof(buf));
    resp.data.len=data_.size();
    resp.data.buff=buf;
    data_.copy(resp.data.buff, data_.size());
    resp.stat=stat_;
    serialize_GetDataResponse(oa, "reply", &resp);
    int32_t len=htonl(get_buffer_len(oa));
    string res((char*)&len,sizeof(len));
    res.append(get_buffer(oa),get_buffer_len(oa));

    close_buffer_oarchive(&oa,1);
    return res;
}

string ZooStatResponse::toString() const{
    oarchive* oa=create_buffer_oarchive();

    ReplyHeader h = {xid_,1,rc_};
    serialize_ReplyHeader(oa, "hdr", &h);

    SetDataResponse resp;
    resp.stat=stat_;
    serialize_SetDataResponse(oa, "reply", &resp);
    int32_t len=htonl(get_buffer_len(oa));
    string res((char*)&len,sizeof(len));
    res.append(get_buffer(oa),get_buffer_len(oa));

    close_buffer_oarchive(&oa,1);
    return res;
}

string ZooGetChildrenResponse::toString() const{
    oarchive* oa=create_buffer_oarchive();

    ReplyHeader h = {xid_,1,rc_};
    serialize_ReplyHeader(oa, "hdr", &h);

    GetChildrenResponse resp;
    // populate the string vector
    allocate_String_vector(&resp.children,strings_.size());
    for(int i=0;i<(int)strings_.size();++i)
        resp.children.data[i]=strdup(strings_[i].c_str());
    serialize_GetChildrenResponse(oa, "reply", &resp);
    deallocate_GetChildrenResponse(&resp);

    int32_t len=htonl(get_buffer_len(oa));
    string res((char*)&len,sizeof(len));
    res.append(get_buffer(oa),get_buffer_len(oa));

    close_buffer_oarchive(&oa,1);
    return res;
}

string ZNodeEvent::toString() const{
    oarchive* oa=create_buffer_oarchive();
    struct WatcherEvent evt = {type_,0,(char*)path_.c_str()};
    struct ReplyHeader h = {WATCHER_EVENT_XID,0,ZOK };

    serialize_ReplyHeader(oa, "hdr", &h);
    serialize_WatcherEvent(oa, "event", &evt);

    int32_t len=htonl(get_buffer_len(oa));
    string res((char*)&len,sizeof(len));
    res.append(get_buffer(oa),get_buffer_len(oa));

    close_buffer_oarchive(&oa,1);
    return res;
}

string PingResponse::toString() const{
    oarchive* oa=create_buffer_oarchive();

    ReplyHeader h = {PING_XID,1,ZOK};
    serialize_ReplyHeader(oa, "hdr", &h);

    int32_t len=htonl(get_buffer_len(oa));
    string res((char*)&len,sizeof(len));
    res.append(get_buffer(oa),get_buffer_len(oa));

    close_buffer_oarchive(&oa,1);
    return res;
}

//******************************************************************************
// Zookeeper server simulator
//
bool ZookeeperServer::hasMoreRecv() const{
  return recvHasMore.get()!=0  || connectionLost;
}

ssize_t ZookeeperServer::callRecv(int s,void *buf,size_t len,int flags){
    if(connectionLost){
        recvReturnBuffer.erase();
        return 0;
    }
    // done transmitting the current buffer?
    if(recvReturnBuffer.size()==0){
        synchronized(recvQMx);
        if(recvQueue.empty()){
            recvErrno=EAGAIN;
            return Mock_socket::callRecv(s,buf,len,flags);
        }
        --recvHasMore;
        Element& el=recvQueue.front();
        if(el.first!=0){
            recvReturnBuffer=el.first->toString();
            delete el.first;
        }
        recvErrno=el.second;
        recvQueue.pop_front();
    }
    return Mock_socket::callRecv(s,buf,len,flags);
}

void ZookeeperServer::onMessageReceived(const RequestHeader& rh, iarchive* ia){
    // no-op by default
}

void ZookeeperServer::notifyBufferSent(const std::string& buffer){
    if(HandshakeRequest::isValid(buffer)){
        // could be a connect request
        auto_ptr<HandshakeRequest> req(HandshakeRequest::parse(buffer));
        if(req.get()!=0){
            // handle the handshake
            int64_t sessId=sessionExpired?req->sessionId+1:req->sessionId;
            sessionExpired=false;
            addRecvResponse(new HandshakeResponse(sessId));
            return;
        }
        // not a connect request -- fall thru
    }
    // parse the buffer to extract the request type and its xid
    iarchive *ia=create_buffer_iarchive((char*)buffer.data(), buffer.size());
    RequestHeader rh;
    deserialize_RequestHeader(ia,"hdr",&rh);
    // notify the "server" a client request has arrived
    if (rh.xid == -8) {
        Element e = Element(new ZooStatResponse,0);
        e.first->setXID(-8);
        addRecvResponse(e);
        close_buffer_iarchive(&ia);
        return;
    } else {
        onMessageReceived(rh,ia);
    }
    close_buffer_iarchive(&ia);
    if(rh.type==ZOO_CLOSE_OP){
        ++closeSent;
        return; // no reply for close requests
    }
    // get the next response from the response queue and append it to the
    // receive list
    Element e;
    {
        synchronized(respQMx);
        if(respQueue.empty())
            return;
        e=respQueue.front();
        respQueue.pop_front();
    }
    e.first->setXID(rh.xid);
    addRecvResponse(e);
}

void forceConnected(zhandle_t* zh){
    // simulate connected state
    zh->state=ZOO_CONNECTED_STATE;

    // Simulate we're connected to the first host in our host list
    zh->fd=ZookeeperServer::FD;
    assert(zh->addrs.count > 0);
    zh->addr_cur = zh->addrs.data[0];
    zh->addrs.next++;

    zh->input_buffer=0;
    gettimeofday(&zh->last_recv,0);
    gettimeofday(&zh->last_send,0);
}

void terminateZookeeperThreads(zhandle_t* zh){
    // this will cause the zookeeper threads to terminate
    zh->close_requested=1;
}
