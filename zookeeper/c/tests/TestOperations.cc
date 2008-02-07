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

#include <cppunit/extensions/HelperMacros.h>

#include "ZKMocks.h"
#include "CppAssertHelper.h"

using namespace std;

class Zookeeper_operations : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_operations);
    CPPUNIT_TEST(testAsyncWatcher1);
    CPPUNIT_TEST(testAsyncGetOperation);
    CPPUNIT_TEST(testOperationsAndDisconnectConcurrently1);
    CPPUNIT_TEST(testOperationsAndDisconnectConcurrently2);
    CPPUNIT_TEST(testConcurrentOperations1);
    CPPUNIT_TEST_SUITE_END();
    zhandle_t *zh;

    static void watcher(zhandle_t *, int, int, const char *){}
public: 
    void setUp()
    {
        zoo_set_debug_level((ZooLogLevel)0); // disable logging
        zoo_deterministic_conn_order(0);
        zh=0;
    }
    
    void tearDown()
    {
        zookeeper_close(zh);
    }

    class AsyncGetOperationCompletion: public AsyncCompletion{
    public:
        AsyncGetOperationCompletion():called_(false),rc_(ZAPIERROR){}
        virtual void dataCompl(int rc, const char *value, int len, const Stat *stat){
            synchronized(mx_);
            called_=true;
            rc_=rc;
            value_.erase();
            if(rc!=ZOK) return;
            value_.assign(value,len);
            if(stat)
                stat_=*stat;
        }
        bool operator()()const{
            synchronized(mx_);
            return called_;
        }
        mutable Mutex mx_;
        bool called_;
        int rc_;
        string value_;
        NodeStat stat_;
    };
#ifndef THREADED
    // send two get data requests; verify that the corresponding completions called
    void testConcurrentOperations1()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        zh->state=CONNECTED_STATE;
        zh->fd=ZookeeperServer::FD;
        zh->input_buffer=0;
        gettimeofday(&zh->last_recv,0);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // process the send queue
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);        
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);        

        CPPUNIT_ASSERT_EQUAL(ZOK,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(string("1"),res1.value_);
        CPPUNIT_ASSERT_EQUAL(ZOK,res2.rc_);
        CPPUNIT_ASSERT_EQUAL(string("2"),res2.value_);
    }
    // send two getData requests and disconnect while the second request is
    // outstanding;
    // verify the completions are called
    void testOperationsAndDisconnectConcurrently1()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        zh->state=CONNECTED_STATE;
        zh->fd=ZookeeperServer::FD;
        zh->input_buffer=0;
        gettimeofday(&zh->last_recv,0);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // process the send queue
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // simulate a disconnect
        zkServer.setConnectionLost();
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL(ZCONNECTIONLOSS,rc);
        CPPUNIT_ASSERT_EQUAL(ZOK,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(string("1"),res1.value_);
        CPPUNIT_ASSERT_EQUAL(ZCONNECTIONLOSS,res2.rc_);
        CPPUNIT_ASSERT_EQUAL(string(""),res2.value_);
    }
    // send two getData requests and simulate timeout while the both request
    // are pending;
    // verify the completions are called
    void testOperationsAndDisconnectConcurrently2()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        zh->state=CONNECTED_STATE;
        zh->fd=ZookeeperServer::FD;
        zh->input_buffer=0;
        gettimeofday(&zh->last_recv,0);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        // simulate timeout
        timeMock.tick(10); // advance system time by 10 secs
        // the next call to zookeeper_interest should return ZOPERATIONTIMEOUT
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL(ZOPERATIONTIMEOUT,rc);
        // make sure the completions have been called
        CPPUNIT_ASSERT_EQUAL(ZOPERATIONTIMEOUT,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(ZOPERATIONTIMEOUT,res2.rc_);
    }
    void testAsyncGetOperation(){
        
    }
    void testAsyncWatcher1(){
        
    }
#else   
    class TestGetDataJob: public TestJob{
    public:
        TestGetDataJob(ZookeeperServer* svr,zhandle_t* zh, int reps=500)
            :svr_(svr),zh_(zh),rc_(ZAPIERROR),reps_(reps){}
        virtual void run(){
            int i;
            for(i=0;i<reps_;i++){
                char buf;
                int size=sizeof(buf);
                svr_->addOperationResponse(new ZooGetResponse("1",1));
                rc_=zoo_get(zh_,"/x/y/z",0,&buf,&size,0);
                if(rc_!=ZOK){
                    break;
                }
            }
        }
        ZookeeperServer* svr_;
        zhandle_t* zh_;
        int rc_;
        int reps_;
    };
    class TestConcurrentOpJob: public TestGetDataJob{
    public:
        static const int REPS=500;
        TestConcurrentOpJob(ZookeeperServer* svr,zhandle_t* zh):
            TestGetDataJob(svr,zh,REPS){}
        virtual TestJob* clone() const {
            return new TestConcurrentOpJob(svr_,zh_);
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",ZOK,rc_,file,line);
        }
    };
    void testConcurrentOperations1()
    {
        for(int counter=0; counter<50; counter++){
            // frozen time -- no timeouts and no pings
            Mock_gettimeofday timeMock;
            
            ZookeeperServer zkServer;
            Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
            // must call zookeeper_close() while all the mocks are in the scope!
            CloseFinally guard(&zh);
            
            zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
            
            TestJobManager jmgr(TestConcurrentOpJob(&zkServer,zh),10);
            jmgr.startAllJobs();
            jmgr.wait();
            // validate test results
            VALIDATE_JOBS(jmgr);
        }
    }
    class ZKGetJob: public TestJob{
    public:
        static const int REPS=1000;
        ZKGetJob(zhandle_t* zh)
            :zh_(zh),rc_(ZAPIERROR){}
        virtual TestJob* clone() const {
            return new ZKGetJob(zh_);
        }
        virtual void run(){
            int i;
            for(i=0;i<REPS;i++){
                char buf;
                int size=sizeof(buf);                
                rc_=zoo_get(zh_,"/xyz",0,&buf,&size,0);
                if(rc_!=ZOK){
                    break;
                }
            }
            //TEST_TRACE(("Finished %d iterations",i));
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",ZOK,rc_,file,line);
        }
        zhandle_t* zh_;
        int rc_;
    };

    // this test connects to a real ZK server and creates the /xyz node and sends
    // lots of zoo_get requests.
    // to run this test use the following command:
    // zktest-mt Zookeeper_operations::testOperationsAndDisconnectConcurrently2 localhost:3181
    // where the second parameter is the server host and port
    void testOperationsAndDisconnectConcurrently2()
    {
        if(globalTestConfig.getTestName().find(__func__)==string::npos || 
                globalTestConfig.getExtraOptCount()==0)
        {
            // only run this test when specifically asked so
            return;
        }
        string host(*(globalTestConfig.getExtraOptBegin()));
        zhandle_t* lzh=zookeeper_init(host.c_str(),watcher,10000,0,0,0);
        CPPUNIT_ASSERT(lzh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT_MESSAGE("Unable to connect to the host",
                ensureCondition(ClientConnected(zh),5000)<5000);
        
        char realpath[1024];
        int rc=zoo_create(lzh,"/xyz","1",1,&OPEN_ACL_UNSAFE,0,realpath,sizeof(realpath)-1);
        CPPUNIT_ASSERT(rc==ZOK || rc==ZNODEEXISTS);
        zookeeper_close(lzh); 
  
        for(int counter=0; counter<200; counter++){
            TEST_TRACE(("Loop count %d",counter));
            
            CloseFinally guard(&zh);

            zh=zookeeper_init(host.c_str(),watcher,10000,0,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            CPPUNIT_ASSERT_MESSAGE("Unable to connect to the host",
                    ensureCondition(ClientConnected(zh),5000)<5000);
            
            TestJobManager jmgr(ZKGetJob(zh),10);
            jmgr.startJobsImmediately();
            jmgr.wait();
            VALIDATE_JOBS(jmgr);
            TEST_TRACE(("run %d finished",counter));
        }

    }

    class TestConcurrentOpWithDisconnectJob: public TestGetDataJob{
    public:
        static const int REPS=1000;
        TestConcurrentOpWithDisconnectJob(ZookeeperServer* svr,zhandle_t* zh):
            TestGetDataJob(svr,zh,REPS){}
        virtual TestJob* clone() const {
            return new TestConcurrentOpWithDisconnectJob(svr_,zh_);
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZCONNECTIONLOSS != rc",ZCONNECTIONLOSS,rc_,file,line);
        }
    };

    // this test is not 100% accurate in a sense it may not detect all error cases.
    // TODO: I can't think of a test that is 100% accurate and doesn't interfere
    //       with the code being tested (in terms of introducing additional 
    //       implicit synchronization points)
    void testOperationsAndDisconnectConcurrently1()
    {
        for(int counter=0; counter<50; counter++){
            //TEST_TRACE(("Loop count %d",counter));
            // frozen time -- no timeouts and no pings
            Mock_gettimeofday timeMock;
            
            ZookeeperServer zkServer;
            Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
            // must call zookeeper_close() while all the mocks are in the scope!
            CloseFinally guard(&zh);
            
            zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
            
            TestJobManager jmgr(TestConcurrentOpWithDisconnectJob(&zkServer,zh),10);
            jmgr.startJobsImmediately();
            millisleep(1);
            // reconnect attempts will start failing immediately 
            zkServer.setServerDown(0);
            // next recv call will return 0
            zkServer.setConnectionLost();
            jmgr.wait();
            VALIDATE_JOBS(jmgr);
        }
        
    }
    // call zoo_aget() in the multithreaded mode
    void testAsyncGetOperation()
    {
        Mock_gettimeofday timeMock;
        
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);

        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL(ZOK,rc);
        
        CPPUNIT_ASSERT(ensureCondition(res1,1000)<1000);
        CPPUNIT_ASSERT_EQUAL(ZOK,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(string("1"),res1.value_);        
    }
    class ChangeNodeWatcher: public WatcherAction{
    public:
        ChangeNodeWatcher():changed_(false){}
        virtual void onNodeValueChanged(zhandle_t*,const char* path){
            synchronized(mx_);
            changed_=true;
            if(path!=0) path_=path;
        }
        SyncedBoolCondition isNodeChangedTriggered() const{
            return SyncedBoolCondition(changed_,mx_);
        }
        bool changed_;
        string path_;
    };
    // verify that async watcher is called for znode events (CREATED, DELETED etc.)
    void testAsyncWatcher1(){
        Mock_gettimeofday timeMock;
        
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        ChangeNodeWatcher action;        
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,
                TEST_CLIENT_ID,&action,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);

        // trigger the watcher
        zkServer.addRecvResponse(new ZNodeEvent(CHANGED_EVENT,"/x/y/z"));
        CPPUNIT_ASSERT(ensureCondition(action.isNodeChangedTriggered(),1000)<1000);
        CPPUNIT_ASSERT_EQUAL(string("/x/y/z"),action.path_);                
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_operations);
