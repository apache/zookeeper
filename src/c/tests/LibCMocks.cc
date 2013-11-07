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

#include <cstdlib>
#include <cstdarg>
#include <iostream>
#include <stdarg.h>

#include "Util.h"
#include "LibCMocks.h"

#undef USING_DUMA

using namespace std;

// *****************************************************************************
// gethostbyname

struct hostent* gethostbyname(const char *name) {
    if(!Mock_gethostbyname::mock_)
        return LIBC_SYMBOLS.gethostbyname(name);
    return Mock_gethostbyname::mock_->call(name);
}

Mock_gethostbyname* Mock_gethostbyname::mock_=0;

Mock_gethostbyname::~Mock_gethostbyname(){
    mock_=0;
    for(unsigned int i=0;i<gethostbynameReturns.size();i++)
        delete gethostbynameReturns[i];
}

Mock_gethostbyname::HostEntry& Mock_gethostbyname::addHostEntry(
        const char* hostName, short addrtype) {
    gethostbynameReturns.push_back(new HostEntry(hostName, addrtype));
    return *gethostbynameReturns.back();
}

hostent* Mock_gethostbyname::call(const char* name) {
    assert("Must add one or more mock hostent entries first"&&
            (gethostbynameReturns.size()!=0));
    return gethostbynameReturns[current++ % gethostbynameReturns.size()];
}

static char** appendString(char **list,const char* str,int len=0){
    const int SIZE_INCREMENT=16;
    if(list==0)
        list=(char**)LIBC_SYMBOLS.calloc(SIZE_INCREMENT,sizeof(char*));
    // find the first available slot
    int count=0;
    for(char** ptr=list; *ptr!=0; ptr++,count++);
    if(((count+1)%SIZE_INCREMENT)==0){
        list=(char**)LIBC_SYMBOLS.realloc(list,(count+1+SIZE_INCREMENT)*sizeof(char*));
        memset(list+count+1,0,SIZE_INCREMENT*sizeof(char*));
    }
    if(len==0){
        len=strlen(str)+1;
    }
    char* ptr=(char*)malloc(len);
    memcpy(ptr,str,len);
    list[count]=ptr;
    return list;
}

static void freeList(char **list){
    if(list==0) return;
    for(char** ptr=list; *ptr!=0; ptr++)
        LIBC_SYMBOLS.free((void*)*ptr);
    LIBC_SYMBOLS.free((void*)list);
}

Mock_gethostbyname::HostEntry::HostEntry(const char* hostName, short addrtype) {
    h_name=strdup(hostName);
    h_addrtype=addrtype;
    if(addrtype==AF_INET)
        h_length=4;
    else{
#ifdef AF_INET6
        h_length=6; // TODO: not really sure, verify!
#else
        assert("AF_INET6 not supported yet"&&false);
#endif
    }
    h_aliases=h_addr_list=0;
}

Mock_gethostbyname::HostEntry::~HostEntry(){
    if(h_name) LIBC_SYMBOLS.free((void*)h_name);
    freeList(h_aliases); h_aliases=0;
    freeList(h_addr_list); h_addr_list=0;
}

Mock_gethostbyname::HostEntry& Mock_gethostbyname::HostEntry::addAlias(
        const char* alias) {
    h_aliases=appendString(h_aliases,alias);
    return *this;
}

Mock_gethostbyname::HostEntry& Mock_gethostbyname::HostEntry::addAddress(
        const char* addr4) {
    h_addr_list=appendString(h_addr_list,addr4,4);
    return *this;
}


// *****************************************************************************
// calloc
#ifndef USING_DUMA
DECLARE_WRAPPER(void*,calloc,(size_t p1, size_t p2)){
    if(!Mock_calloc::mock_)
        return CALL_REAL(calloc,(p1,p2));
    return Mock_calloc::mock_->call(p1,p2);
}
#endif

void* Mock_calloc::call(size_t p1, size_t p2){
#ifndef USING_DUMA
    if(counter++ ==callsBeforeFailure){
        counter=0;
        errno=errnoOnFailure;
        return 0;
    }
    return CALL_REAL(calloc,(p1,p2));
#else
    return 0;
#endif
}

Mock_calloc* Mock_calloc::mock_=0;

// *****************************************************************************
// realloc

#ifndef USING_DUMA
void* realloc(void* p, size_t s){
    if(!Mock_realloc::mock_)
        return LIBC_SYMBOLS.realloc(p,s);
    return Mock_realloc::mock_->call(p,s);
}
#endif

Mock_realloc* Mock_realloc::mock_=0;

void* Mock_realloc::call(void* p, size_t s){
    if(counter++ ==callsBeforeFailure){
        counter=0;
        errno=errnoOnFailure;
        return 0;
    }
    return LIBC_SYMBOLS.realloc(p,s);
}

// *****************************************************************************
// random
RANDOM_RET_TYPE random(){
    if(!Mock_random::mock_)
        return LIBC_SYMBOLS.random();
    return Mock_random::mock_->call();    
}

void srandom(unsigned long seed){
    if (!Mock_random::mock_)
        LIBC_SYMBOLS.srandom(seed);
    else
        Mock_random::mock_->setSeed(seed);
}

Mock_random* Mock_random::mock_=0;

int Mock_random::call(){
    assert("Must specify one or more random integers"&&(randomReturns.size()!=0));
    return randomReturns[currentIdx++ % randomReturns.size()];
}

// *****************************************************************************
// free
#ifndef USING_DUMA
DECLARE_WRAPPER(void,free,(void* p)){
    if(Mock_free_noop::mock_ && !Mock_free_noop::mock_->nested)
        Mock_free_noop::mock_->call(p);
    else
        CALL_REAL(free,(p));
}
#endif

void Mock_free_noop::call(void* p){
    // on cygwin libc++ is linked statically
    // push_back() may call free(), hence the nesting guards
    synchronized(mx);
    nested++;
    callCounter++;
    requested.push_back(p);
    nested--;
}
void Mock_free_noop::freeRequested(){
#ifndef USING_DUMA
    synchronized(mx);
    for(unsigned i=0; i<requested.size();i++)
        CALL_REAL(free,(requested[i]));
#endif
}

int Mock_free_noop::getFreeCount(void* p){
    int cnt=0;
    synchronized(mx);
    for(unsigned i=0;i<requested.size();i++)
        if(requested[i]==p)cnt++;
    return cnt;
}

bool Mock_free_noop::isFreed(void* p){
    synchronized(mx);
    for(unsigned i=0;i<requested.size();i++)
        if(requested[i]==p)return true;
    return false;
}

Mock_free_noop* Mock_free_noop::mock_=0;

// *****************************************************************************
// socket
int socket(int domain, int type, int protocol){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.socket(domain,type,protocol);
    return Mock_socket::mock_->callSocket(domain,type,protocol);
}

int close(int fd){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.close(fd);
    return Mock_socket::mock_->callClose(fd);
}

int getsockopt(int s,int level,int optname,void *optval,socklen_t *optlen){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.getsockopt(s,level,optname,optval,optlen);
    return Mock_socket::mock_->callGet(s,level,optname,optval,optlen);    
}

int setsockopt(int s,int level,int optname,const void *optval,socklen_t optlen){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.setsockopt(s,level,optname,optval,optlen);
    return Mock_socket::mock_->callSet(s,level,optname,optval,optlen);      
}
int connect(int s,const struct sockaddr *addr,socklen_t len){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.connect(s,addr,len);
    return Mock_socket::mock_->callConnect(s,addr,len);
}
ssize_t send(int s,const void *buf,size_t len,int flags){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.send(s,buf,len,flags);
    return Mock_socket::mock_->callSend(s,buf,len,flags);    
}

ssize_t recv(int s,void *buf,size_t len,int flags){
    if (!Mock_socket::mock_)
        return LIBC_SYMBOLS.recv(s,buf,len,flags);
    return Mock_socket::mock_->callRecv(s,buf,len,flags);        
}

Mock_socket* Mock_socket::mock_=0;

// *****************************************************************************
// fcntl
extern "C" int fcntl(int fd,int cmd,...){
    va_list va;
    va_start(va,cmd);
    void* arg = va_arg(va, void *);
    va_end (va);
    if (!Mock_fcntl::mock_)
        return LIBC_SYMBOLS.fcntl(fd,cmd,arg);
    return Mock_fcntl::mock_->call(fd,cmd,arg);    
}

Mock_fcntl* Mock_fcntl::mock_=0;

// *****************************************************************************
// select
int select(int nfds,fd_set *rfds,fd_set *wfds,fd_set *efds,struct timeval *timeout){
    if (!Mock_select::mock_)
        return LIBC_SYMBOLS.select(nfds,rfds,wfds,efds,timeout);
    return Mock_select::mock_->call(nfds,rfds,wfds,efds,timeout);        
}

Mock_select* Mock_select::mock_=0;

// *****************************************************************************
// poll
Mock_poll* Mock_poll::mock_=0;
int poll(struct pollfd *fds, POLL_NFDS_TYPE nfds, int timeout){
    if (!Mock_poll::mock_)
        return LIBC_SYMBOLS.poll(fds,nfds,timeout);
    return Mock_poll::mock_->call(fds,nfds,timeout);        
    
}

// *****************************************************************************
// gettimeofday
int gettimeofday(struct timeval *tp, GETTIMEOFDAY_ARG2_TYPE tzp){
    if (!Mock_gettimeofday::mock_)
        return LIBC_SYMBOLS.gettimeofday(tp,tzp);
    return Mock_gettimeofday::mock_->call(tp,tzp);            
}

Mock_gettimeofday* Mock_gettimeofday::mock_=0;

