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

#include <cppunit/extensions/HelperMacros.h>

#include "ZKMocks.h"

#ifdef THREADED
#include "PthreadMocks.h"
#endif

using namespace std;

class Zookeeper_close : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_close);
#ifdef THREADED
    CPPUNIT_TEST(testIOThreadStoppedOnExpire);
#endif
    CPPUNIT_TEST(testCloseUnconnected);
    CPPUNIT_TEST(testCloseUnconnected1);
    CPPUNIT_TEST(testCloseConnected1);
    CPPUNIT_TEST(testCloseFromWatcher1);
    CPPUNIT_TEST_SUITE_END();
    zhandle_t *zh;
    static void watcher(zhandle_t *, int, int, const char *,void*){}
    FILE *logfile;
public: 

    Zookeeper_close() {
      logfile = openlogfile("Zookeeper_close");
    }

    ~Zookeeper_close() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);

        zoo_deterministic_conn_order(0);
        zh=0;
    }
    
    void tearDown()
    {
        zookeeper_close(zh);
    }

    class CloseOnSessionExpired: public WatcherAction{
    public:
        CloseOnSessionExpired(bool callClose=true):
            callClose_(callClose),rc(ZOK){}
        virtual void onSessionExpired(zhandle_t* zh){
            memcpy(&lzh,zh,sizeof(lzh));
            if(callClose_)
                rc=zookeeper_close(zh);
        }
        zhandle_t lzh;
        bool callClose_;
        int rc;
    };
    
#ifndef THREADED
    void testCloseUnconnected()
    {       
        zh=zookeeper_init("localhost:2121",watcher,10000,0,0,0);       
        CPPUNIT_ASSERT(zh!=0);
        
        // do not actually free the memory while in zookeeper_close()
        Mock_free_noop freeMock;
        // make a copy of zhandle before close() overwrites some of 
        // it members with NULLs
        zhandle_t lzh;
        memcpy(&lzh,zh,sizeof(lzh));
        int rc=zookeeper_close(zh);
        zhandle_t* savezh=zh; zh=0;
        freeMock.disable(); // disable mock's fake free()- use libc's free() instead
        
        // verify that zookeeper_close has done its job
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // memory
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.hostname));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.addrs.data));
        // This cannot be maintained properly CPPUNIT_ASSERT_EQUAL(9,freeMock.callCounter);
    }
    void testCloseUnconnected1()
    {
        zh=zookeeper_init("localhost:2121",watcher,10000,0,0,0);       
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state 
        zh->fd=ZookeeperServer::FD;
        zh->state=ZOO_CONNECTED_STATE;
        Mock_flush_send_queue zkMock;
        // do not actually free the memory while in zookeeper_close()
        Mock_free_noop freeMock;
        // make a copy of zhandle before close() overwrites some of 
        // it members with NULLs
        zhandle_t lzh;
        memcpy(&lzh,zh,sizeof(lzh));
        int rc=zookeeper_close(zh);
        zhandle_t* savezh=zh; zh=0;
        freeMock.disable(); // disable mock's fake free()- use libc's free() instead

        // verify that zookeeper_close has done its job
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // memory
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.hostname));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.addrs.data));
        // the close request sent?
        CPPUNIT_ASSERT_EQUAL(1,zkMock.counter);
    }
    void testCloseConnected1()
    {
        ZookeeperServer zkServer;
        // poll() will called from zookeeper_close()
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);

        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        CPPUNIT_ASSERT_EQUAL(ZOO_NOTCONNECTED_STATE, zoo_state(zh));

        Mock_gettimeofday timeMock;
        
        int fd=0;
        int interest=0;
        timeval tv;
        int rc=zookeeper_interest(zh,&fd,&interest,&tv);
        
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTING_STATE,zoo_state(zh));
        CPPUNIT_ASSERT_EQUAL(ZOOKEEPER_READ|ZOOKEEPER_WRITE,interest);
        
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE,zoo_state(zh));
        
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE,zoo_state(zh));
        // do not actually free the memory while in zookeeper_close()
        Mock_free_noop freeMock;
        // make a copy of zhandle before close() overwrites some of 
        // it members with NULLs
        zhandle_t lzh;
        memcpy(&lzh,zh,sizeof(lzh));
        zookeeper_close(zh);
        zhandle_t* savezh=zh; zh=0;
        freeMock.disable(); // disable mock's fake free()- use libc's free() instead
        // memory
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.hostname));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.addrs.data));
        // the close request sent?
        CPPUNIT_ASSERT_EQUAL(1,(int)zkServer.closeSent);
    }
    void testCloseFromWatcher1()
    {
        Mock_gettimeofday timeMock;

        ZookeeperServer zkServer;
        // make the server return a non-matching session id
        zkServer.returnSessionExpired();
        // poll() will called from zookeeper_close()
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);

        CloseOnSessionExpired closeAction;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,
                TEST_CLIENT_ID,&closeAction,0);
        CPPUNIT_ASSERT(zh!=0);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // initiate connection
        int rc=zookeeper_interest(zh,&fd,&interest,&tv);        
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTING_STATE,zoo_state(zh));
        CPPUNIT_ASSERT_EQUAL(ZOOKEEPER_READ|ZOOKEEPER_WRITE,interest);
        rc=zookeeper_process(zh,interest);
        // make sure the handshake in progress 
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE,zoo_state(zh));
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // do not actually free the memory while in zookeeper_close()
        Mock_free_noop freeMock;
        // should call the watcher with ZOO_EXPIRED_SESSION_STATE state
        rc=zookeeper_process(zh,interest);
        zhandle_t* savezh=zh; zh=0;
        freeMock.disable(); // disable mock's fake free()- use libc's free() instead
        
        CPPUNIT_ASSERT_EQUAL(ZOO_EXPIRED_SESSION_STATE,zoo_state(savezh));
        // memory
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.hostname));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.addrs.data));
        // make sure the close request NOT sent
        CPPUNIT_ASSERT_EQUAL(0,(int)zkServer.closeSent);
    }
#else
    void testCloseUnconnected()
    {
        // disable threading
        MockPthreadZKNull pthreadMock;
        zh=zookeeper_init("localhost:2121",watcher,10000,0,0,0); 
        
        CPPUNIT_ASSERT(zh!=0);
        adaptor_threads* adaptor=(adaptor_threads*)zh->adaptor_priv;
        CPPUNIT_ASSERT(adaptor!=0);

        // do not actually free the memory while in zookeeper_close()
        Mock_free_noop freeMock;
        // make a copy of zhandle before close() overwrites some of 
        // it members with NULLs
        zhandle_t lzh;
        memcpy(&lzh,zh,sizeof(lzh));
        int rc=zookeeper_close(zh);
        zhandle_t* savezh=zh; zh=0;
        // we're done, disable mock's fake free(), use libc's free() instead
        freeMock.disable();
        
        // verify that zookeeper_close has done its job
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // memory
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.hostname));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.addrs.data));
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(adaptor));
        // Cannot be maintained accurately: CPPUNIT_ASSERT_EQUAL(10,freeMock.callCounter);
        // threads
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(adaptor->io));
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(adaptor->completion));
        // mutexes
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->to_process.lock));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->to_process.lock));
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->to_send.lock));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->to_send.lock));
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->sent_requests.lock));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->sent_requests.lock));
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->completions_to_process.lock));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->completions_to_process.lock));
        // conditionals
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->sent_requests.cond));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->sent_requests.cond));
        CPPUNIT_ASSERT_EQUAL(1,MockPthreadsNull::getDestroyCounter(&savezh->completions_to_process.cond));
        CPPUNIT_ASSERT_EQUAL(0,MockPthreadsNull::getInvalidAccessCounter(&savezh->completions_to_process.cond));
    }
    void testCloseUnconnected1()
    {
        for(int i=0; i<100;i++){
            zh=zookeeper_init("localhost:2121",watcher,10000,0,0,0); 
            CPPUNIT_ASSERT(zh!=0);
            adaptor_threads* adaptor=(adaptor_threads*)zh->adaptor_priv;
            CPPUNIT_ASSERT(adaptor!=0);
            int rc=zookeeper_close(zh);
            zh=0;
            CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        }
    }
    void testCloseConnected1()
    {
        // frozen time -- no timeouts and no pings
        Mock_gettimeofday timeMock;

        for(int i=0;i<100;i++){
            ZookeeperServer zkServer;
            Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
            // use a checked version of pthread calls
            CheckedPthread threadMock;
            // do not actually free the memory while in zookeeper_close()
            Mock_free_noop freeMock;
            
            zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0); 
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
            // make a copy of zhandle before close() overwrites some of 
            // its members with NULLs
            zhandle_t lzh;
            memcpy(&lzh,zh,sizeof(lzh));
            int rc=zookeeper_close(zh);
            zhandle_t* savezh=zh; zh=0;
            // we're done, disable mock's fake free(), use libc's free() instead
            freeMock.disable();
            
            CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);            
            adaptor_threads* adaptor=(adaptor_threads*)lzh.adaptor_priv;
            // memory
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.hostname));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh.addrs.data));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(adaptor));
            // threads
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->io));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->completion));
            // mutexes
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->completions_to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->completions_to_process.lock));
            // conditionals
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&savezh->completions_to_process.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&savezh->completions_to_process.cond));
        }
    }
    
    struct PointerFreed{
        PointerFreed(Mock_free_noop& freeMock,void* ptr):
            freeMock_(freeMock),ptr_(ptr){}
        bool operator()() const{return freeMock_.isFreed(ptr_); }
        Mock_free_noop& freeMock_;
        void* ptr_;
    };
    // test if zookeeper_close may be called from a watcher callback on
    // SESSION_EXPIRED event
    void testCloseFromWatcher1()
    {
        // frozen time -- no timeouts and no pings
        Mock_gettimeofday timeMock;
        
        for(int i=0;i<100;i++){
            ZookeeperServer zkServer;
            // make the server return a non-matching session id
            zkServer.returnSessionExpired();
            
            Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
            // use a checked version of pthread calls
            CheckedPthread threadMock;
            // do not actually free the memory while in zookeeper_close()
            Mock_free_noop freeMock;

            CloseOnSessionExpired closeAction;
            zh=zookeeper_init("localhost:2121",activeWatcher,10000,
                    TEST_CLIENT_ID,&closeAction,0);
            
            CPPUNIT_ASSERT(zh!=0);
            // we rely on the fact that zh is freed the last right before
            // zookeeper_close() returns...
            CPPUNIT_ASSERT(ensureCondition(PointerFreed(freeMock,zh),1000)<1000);
            zhandle_t* lzh=zh;
            zh=0;
            // we're done, disable mock's fake free(), use libc's free() instead
            freeMock.disable();
            
            CPPUNIT_ASSERT_EQUAL((int)ZOK,closeAction.rc);          
            adaptor_threads* adaptor=(adaptor_threads*)closeAction.lzh.adaptor_priv;
            // memory
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.hostname));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.addrs.data));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(adaptor));
            // threads
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->io));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->completion));
            // mutexes
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->completions_to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->completions_to_process.lock));
            // conditionals
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->completions_to_process.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->completions_to_process.cond));
        }
    }

    void testIOThreadStoppedOnExpire()
    {
        // frozen time -- no timeouts and no pings
        Mock_gettimeofday timeMock;
        
        for(int i=0;i<100;i++){
            ZookeeperServer zkServer;
            // make the server return a non-matching session id
            zkServer.returnSessionExpired();
            
            Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
            // use a checked version of pthread calls
            CheckedPthread threadMock;
            // do not call zookeeper_close() from the watcher
            CloseOnSessionExpired closeAction(false);
            zh=zookeeper_init("localhost:2121",activeWatcher,10000,
                    &testClientId,&closeAction,0);
            
            // this is to ensure that if any assert fires, zookeeper_close() 
            // will still be called while all the mocks are in the scope!
            CloseFinally guard(&zh);

            CPPUNIT_ASSERT(zh!=0);
            CPPUNIT_ASSERT(ensureCondition(SessionExpired(zh),1000)<1000);
            CPPUNIT_ASSERT(ensureCondition(IOThreadStopped(zh),1000)<1000);
            // make sure the watcher has been processed
            CPPUNIT_ASSERT(ensureCondition(closeAction.isWatcherTriggered(),1000)<1000);
            // make sure the threads have not been destroyed yet
            adaptor_threads* adaptor=(adaptor_threads*)zh->adaptor_priv;
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getDestroyCounter(adaptor->io));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getDestroyCounter(adaptor->completion));
            // about to call zookeeper_close() -- no longer need the guard
            guard.disarm();
            
            // do not actually free the memory while in zookeeper_close()
            Mock_free_noop freeMock;
            zookeeper_close(zh);
            zhandle_t* lzh=zh; zh=0;
            // we're done, disable mock's fake free(), use libc's free() instead
            freeMock.disable();
            
            // memory
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(lzh));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.hostname));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(closeAction.lzh.addrs.data));
            CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(adaptor));
            // threads
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->io));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(adaptor->completion));
            // mutexes
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->to_process.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->to_send.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->sent_requests.lock));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->completions_to_process.lock));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->completions_to_process.lock));
            // conditionals
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->sent_requests.cond));
            CPPUNIT_ASSERT_EQUAL(1,CheckedPthread::getDestroyCounter(&lzh->completions_to_process.cond));
            CPPUNIT_ASSERT_EQUAL(0,CheckedPthread::getInvalidAccessCounter(&lzh->completions_to_process.cond));
        }
    }

#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_close);
