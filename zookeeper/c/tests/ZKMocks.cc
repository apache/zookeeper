#include <arpa/inet.h>  // for htonl
#include <memory>

#include "ZKMocks.h"
#include "Util.h"

#include <zookeeper.jute.h>
#include <proto.h>

using namespace std;

TestClientId testClientId;
const char* TestClientId::PASSWD="1234567890123456";

HandshakeRequest* HandshakeRequest::parse(const std::string& buf){
    auto_ptr<HandshakeRequest> req(new HandshakeRequest);

    memcpy(&req->protocolVersion,buf.data(), sizeof(req->protocolVersion));
    req->protocolVersion = htonl(req->protocolVersion);
    
    int offset=sizeof(req->protocolVersion);
    
    memcpy(&req->lastZxidSeen,buf.data()+offset,sizeof(req->lastZxidSeen));
    req->lastZxidSeen = htonll(req->lastZxidSeen);
    offset+=sizeof(req->lastZxidSeen);
    
    memcpy(&req->timeOut,buf.data()+offset,sizeof(req->timeOut));
    req->timeOut = htonl(req->timeOut);
    offset+=sizeof(req->timeOut);
    
    memcpy(&req->sessionId,buf.data()+offset,sizeof(req->sessionId));
    req->sessionId = htonll(req->sessionId);
    offset+=sizeof(req->sessionId);
    
    memcpy(&req->passwd_len,buf.data()+offset,sizeof(req->passwd_len));
    req->passwd_len = htonl(req->passwd_len);
    offset+=sizeof(req->passwd_len);
    
    memcpy(req->passwd,buf.data()+offset,sizeof(req->passwd));
    if(testClientId.client_id==req->sessionId &&
            !memcmp(testClientId.passwd,req->passwd,sizeof(req->passwd)))
        return req.release();
    // the request didn't match -- may not be a handshake request after all
    return 0;
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
DECLARE_WRAPPER(int,adaptor_init,(zhandle_t* zh))
{
    if(!GetZHandleBeforInitReturned::mock_)
        return CALL_REAL(adaptor_init,(zh));
    return GetZHandleBeforInitReturned::mock_->call(zh);
}

int GetZHandleBeforInitReturned::call(zhandle_t *zh){
    ptr=zh;
    return CALL_REAL(adaptor_init,(zh));
}

GetZHandleBeforInitReturned* GetZHandleBeforInitReturned::mock_=0;

//******************************************************************************
//
string HandshakeResponse::toString() const {
    string buf;
    int32_t tmp=htonl(protocolVersion);
    buf.append((char*)&tmp,sizeof(tmp));
    tmp=htonl(timeOut);
    buf.append((char*)&tmp,sizeof(tmp));
    int64_t tmp64=htonll(sessionId);
    buf.append((char*)&tmp64,sizeof(sessionId));
    tmp=htonl(passwd_len);
    buf.append((char*)&tmp,sizeof(tmp));
    buf.append(passwd,sizeof(passwd));
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
    resp.data.len=data_.size();
    resp.data.buff=(char*)malloc(data_.size());
    data_.copy(resp.data.buff, data_.size());
    resp.stat=stat_;
    serialize_GetDataResponse(oa, "reply", &resp);
    int32_t len=htonl(get_buffer_len(oa));
    string buf((char*)&len,sizeof(len));
    buf.append(get_buffer(oa),get_buffer_len(oa));
    
    close_buffer_oarchive(&oa,1);
    return buf;
}

//******************************************************************************
// Zookeeper server simulator
// 
ssize_t ZookeeperServer::callRecv(int s,void *buf,size_t len,int flags){
    // done transmitting the current buffer?
    if(recvReturnBuffer.size()==0){
        synchronized(recvQMx);
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
    close_buffer_iarchive(&ia);
    if(rh.type==CLOSE_OP)
        return; // no reply for close requests
    // get the next response from the response queue and append it to the receive list
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

