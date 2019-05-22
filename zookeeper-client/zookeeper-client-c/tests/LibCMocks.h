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

#ifndef LIBCMOCKS_H_
#define LIBCMOCKS_H_

#include <string>
#include <vector>
#include <deque>

#include <errno.h>
#include <string.h>

#include "MocksBase.h"
#include "LibCSymTable.h"
#include "ThreadingUtil.h"

// *****************************************************************************
// gethostbyname

class Mock_gethostbyname: public Mock
{
public:
    struct HostEntry: public hostent {
        HostEntry(const char* hostName,short addrtype);
        ~HostEntry();
        HostEntry& addAlias(const char* alias);
        HostEntry& addAddress(const char* addr4);
    };

    Mock_gethostbyname():current(0){mock_=this;}
    virtual ~Mock_gethostbyname();
    HostEntry& addHostEntry(const char* hostName,short addrtype=AF_INET);
    virtual hostent* call(const char* name);

    typedef std::vector<HostEntry*> HostEntryCollection;
    HostEntryCollection gethostbynameReturns;
    int current;
    static Mock_gethostbyname* mock_;
};

class MockFailed_gethostbyname: public Mock_gethostbyname
{
public:
    MockFailed_gethostbyname():h_errnoReturn(HOST_NOT_FOUND) {}

    int h_errnoReturn;
    virtual hostent* call(const char* name) {
        h_errno=h_errnoReturn;
        return 0;
    }
};

// *****************************************************************************
// calloc

class Mock_calloc: public Mock
{
public:
    Mock_calloc():errnoOnFailure(ENOMEM),callsBeforeFailure(-1),counter(0) {
        mock_=this;
    }
    virtual ~Mock_calloc() {mock_=0;}

    int errnoOnFailure;
    int callsBeforeFailure;
    int counter;
    virtual void* call(size_t p1, size_t p2);

    static Mock_calloc* mock_;
};

// *****************************************************************************
// realloc

class Mock_realloc: public Mock
{
public:
    Mock_realloc():errnoOnFailure(ENOMEM),callsBeforeFailure(-1),counter(0) {
        mock_=this;
    }
    virtual ~Mock_realloc() {mock_=0;}

    int errnoOnFailure;
    int callsBeforeFailure;
    int counter;
    virtual void* call(void* p, size_t s);

    static Mock_realloc* mock_;
};

// *****************************************************************************
// random

class Mock_random: public Mock
{
public:
    Mock_random():currentIdx(0) {mock_=this;}
    virtual ~Mock_random() {mock_=0;}

    int currentIdx;
    std::vector<int> randomReturns;
    virtual int call();
    void setSeed(unsigned long){currentIdx=0;}

    static Mock_random* mock_;
};

// *****************************************************************************
// no-op free; keeps track of all deallocation requests
class Mock_free_noop: public Mock
{
    Mutex mx;
    std::vector<void*> requested;
public:
    Mock_free_noop():nested(0),callCounter(0){mock_=this;}
    virtual ~Mock_free_noop(){
        mock_=0;
        freeRequested();
    }
    
    int nested;
    int callCounter;
    virtual void call(void* p);
    void freeRequested();
    void disable(){mock_=0;}
    // returns number of times the pointer was freed
    int getFreeCount(void*);
    bool isFreed(void*);
    
    static Mock_free_noop* mock_;
};

// *****************************************************************************
// socket and related system calls

class Mock_socket: public Mock
{
public:
    static const int FD=63;
    Mock_socket():socketReturns(FD),closeReturns(0),getsocketoptReturns(0),
        optvalSO_ERROR(0),
        setsockoptReturns(0),connectReturns(0),connectErrno(0),
        sendErrno(0),recvErrno(0)
    {
        mock_=this;
    }
    virtual ~Mock_socket(){mock_=0;}

    int socketReturns;
    virtual int callSocket(int domain, int type, int protocol){
        return socketReturns;
    }
    int closeReturns;
    virtual int callClose(int fd){
        return closeReturns;
    }
    int getsocketoptReturns;
    int optvalSO_ERROR;
    virtual int callGet(int s,int level,int optname,void *optval,socklen_t *len){
        if(level==SOL_SOCKET && optname==SO_ERROR){
            setSO_ERROR(optval,*len);
        }
        return getsocketoptReturns;
    }
    virtual void setSO_ERROR(void *optval,socklen_t len){
        memcpy(optval,&optvalSO_ERROR,len);
    }
    
    int setsockoptReturns;
    virtual int callSet(int s,int level,int optname,const void *optval,socklen_t len){
        return setsockoptReturns;
    }
    int connectReturns;
    int connectErrno;
    virtual int callConnect(int s,const struct sockaddr *addr,socklen_t len){
        errno=connectErrno;
        return connectReturns;
    }
    
    virtual void notifyBufferSent(const std::string& buffer){}
    
    int sendErrno;
    std::string sendBuffer;
    virtual ssize_t callSend(int s,const void *buf,size_t len,int flags){
        if(sendErrno!=0){
            errno=sendErrno;
            return -1;
        }
        // first call to send() is always the length of the buffer to follow
        bool sendingLength=sendBuffer.size()==0;
        // overwrite the length bytes
        sendBuffer.assign((const char*)buf,len);
        if(!sendingLength){
            notifyBufferSent(sendBuffer);
            sendBuffer.erase();
        }
        return len;
    }

    int recvErrno;
    std::string recvReturnBuffer;
    virtual ssize_t callRecv(int s,void *buf,size_t len,int flags){
        if(recvErrno!=0){
            errno=recvErrno;
            return -1;
        }
        int k=std::min(len,recvReturnBuffer.length());
        if(k==0)
            return 0;
        memcpy(buf,recvReturnBuffer.data(),k);
        recvReturnBuffer.erase(0,k);
        return k;
    }
    virtual bool hasMoreRecv() const{
        return recvReturnBuffer.size()!=0;
    }
    static Mock_socket* mock_;
};

// *****************************************************************************
// fcntl
class Mock_fcntl: public Mock
{
public:
    Mock_fcntl():callReturns(0),trapFD(-1){mock_=this;}
    ~Mock_fcntl(){mock_=0;}
    
    int callReturns;
    int trapFD;
    virtual int call(int fd, int cmd, void* arg){
        if(trapFD==-1)
            return LIBC_SYMBOLS.fcntl(fd,cmd,arg);
        return callReturns;
    }

    static Mock_fcntl* mock_;
};

// *****************************************************************************
// select
class Mock_select: public Mock
{
public:
    Mock_select(Mock_socket* s,int fd):sock(s),
        callReturns(0),myFD(fd),timeout(50)
    {
        mock_=this;
    }
    ~Mock_select(){mock_=0;}
    
    Mock_socket* sock;
    int callReturns;
    int myFD;
    int timeout; //in millis
    virtual int call(int nfds,fd_set *rfds,fd_set *wfds,fd_set *efds,struct timeval *tv){
        bool isWritableRequested=(wfds && FD_ISSET(myFD,wfds));
        if(rfds) FD_CLR(myFD,rfds);
        if(wfds) FD_CLR(myFD,wfds);
        // this timeout is only to prevent a tight loop
        timeval myTimeout={0,0};
        if(!isWritableRequested && !isFDReadable()){
            myTimeout.tv_sec=timeout/1000;
            myTimeout.tv_usec=(timeout%1000)*1000;
        }
        LIBC_SYMBOLS.select(nfds,rfds,wfds,efds,&myTimeout);
        // myFD is always writable
        if(isWritableRequested) FD_SET(myFD,wfds);
        // myFD is only readable if the socket has anything to read
        if(isFDReadable() && rfds) FD_SET(myFD,rfds);
        return callReturns;
    }

    virtual bool isFDReadable() const {
        return sock->hasMoreRecv();
    }
    
    static Mock_select* mock_;
};

// *****************************************************************************
// poll
// the last element of the pollfd array is expected to be test FD
class Mock_poll: public Mock
{
public:
    Mock_poll(Mock_socket* s,int fd):sock(s),
        callReturns(1),myFD(fd),timeout(50)
    {
        mock_=this;
    }
    ~Mock_poll(){mock_=0;}
    
    Mock_socket* sock;
    int callReturns;
    int myFD;
    int timeout; //in millis
    virtual int call(struct pollfd *fds, POLL_NFDS_TYPE nfds, int to) {
        pollfd* myPoll=0;
        if(fds[nfds-1].fd==myFD)
            myPoll=&fds[nfds-1];
        bool isWritableRequested=false;
        if(myPoll!=0){
            isWritableRequested=myPoll->events&POLLOUT;
            nfds--;
        }
        LIBC_SYMBOLS.poll(fds,nfds,(!isWritableRequested&&!isFDReadable())?timeout:0);
        if(myPoll!=0){
            // myFD is always writable if requested
            myPoll->revents=isWritableRequested?POLLOUT:0;
            // myFD is only readable if the socket has anything to read
            myPoll->revents|=isFDReadable()?POLLIN:0;
        }
        return callReturns;
    }

    virtual bool isFDReadable() const {
        return sock->hasMoreRecv();
    }
    
    static Mock_poll* mock_;
};

// *****************************************************************************
// gettimeofday
class Mock_gettimeofday: public Mock
{
public:
    Mock_gettimeofday(){
        LIBC_SYMBOLS.gettimeofday(&tv,0);
        mock_=this;
    }
    Mock_gettimeofday(const Mock_gettimeofday& other):tv(other.tv){}
    Mock_gettimeofday(int32_t sec,int32_t usec){
        tv.tv_sec=sec;
        tv.tv_usec=usec;
    }
    ~Mock_gettimeofday(){mock_=0;}
    
    timeval tv;
    virtual int call(struct timeval *tp, GETTIMEOFDAY_ARG2_TYPE tzp){
        *tp=tv;
        return 0;
    }
    operator timeval() const{
        return tv;
    }
    // advance secs
    virtual void tick(int howmuch=1){tv.tv_sec+=howmuch;}
    // advance milliseconds
    // can move the clock forward as well as backward by providing a negative
    // number
    virtual void millitick(int howmuch=1){
        int ms=tv.tv_usec/1000+howmuch;
        tv.tv_sec+=ms/1000;
        // going backward?
        if(ms<0){
            ms=1000-(-ms%1000); //wrap millis around
        }
        tv.tv_usec=(ms%1000)*1000;
    }
    virtual void tick(const timeval& howmuch){
        // add milliseconds (discarding microsecond portion)
        long ms=tv.tv_usec/1000+howmuch.tv_usec/1000;
        tv.tv_sec+=howmuch.tv_sec+ms/1000;
        tv.tv_usec=(ms%1000)*1000;
    }
    static Mock_gettimeofday* mock_;
};

// discard microseconds!
inline bool operator==(const timeval& lhs, const timeval& rhs){
    return rhs.tv_sec==lhs.tv_sec && rhs.tv_usec/1000==lhs.tv_usec/1000;
}

// simplistic implementation: no normalization, assume lhs >= rhs,
// discarding microseconds
inline timeval operator-(const timeval& lhs, const timeval& rhs){
    timeval res;
    res.tv_sec=lhs.tv_sec-rhs.tv_sec;
    res.tv_usec=(lhs.tv_usec/1000-rhs.tv_usec/1000)*1000;
    if(res.tv_usec<0){
        res.tv_sec--;
        res.tv_usec=1000000+res.tv_usec%1000000; // wrap the millis around
    }
    return res;
}

inline int32_t toMilliseconds(const timeval& tv){
    return tv.tv_sec*1000+tv.tv_usec/1000;    
}

#endif /*LIBCMOCKS_H_*/
