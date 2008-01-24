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
    CPPUNIT_TEST(testOperationsAndDisconnectConcurrently1);
    //CPPUNIT_TEST(testOperationsAndDisconnectConcurrently2);
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

#ifndef THREADED
    void testConcurrentOperations1()
    {
        
    }
    void testOperationsAndDisconnectConcurrently1()
    {
        
    }
    void testOperationsAndDisconnectConcurrently2()
    {
        
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
            
            zh=zookeeper_init("localhost:2121",watcher,10000,&testClientId,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            while(zh->state!=CONNECTED_STATE)
                millisleep(2);
                    
            TestConcurrentOpJob j1(&zkServer,zh);
            TestConcurrentOpJob j2(&zkServer,zh);
            TestConcurrentOpJob j3(&zkServer,zh);
            TestConcurrentOpJob j4(&zkServer,zh);
            TestConcurrentOpJob j5(&zkServer,zh);
            TestConcurrentOpJob j6(&zkServer,zh);
            TestConcurrentOpJob j7(&zkServer,zh);
            TestConcurrentOpJob j8(&zkServer,zh);
            TestConcurrentOpJob j9(&zkServer,zh);
            TestConcurrentOpJob j10(&zkServer,zh);
    
            const int THREAD_COUNT=10;
            CountDownLatch startLatch(THREAD_COUNT);
            CountDownLatch endLatch(THREAD_COUNT);
    
            j1.start(&startLatch,&endLatch);
            j2.start(&startLatch,&endLatch);
            j3.start(&startLatch,&endLatch);
            j4.start(&startLatch,&endLatch);
            j5.start(&startLatch,&endLatch);
            j6.start(&startLatch,&endLatch);
            j7.start(&startLatch,&endLatch);
            j8.start(&startLatch,&endLatch);
            j9.start(&startLatch,&endLatch);
            j10.start(&startLatch,&endLatch);
            endLatch.await();
            // validate test results
            VALIDATE_JOB(j1);
            VALIDATE_JOB(j2);
            VALIDATE_JOB(j3);
            VALIDATE_JOB(j4);
            VALIDATE_JOB(j5);
            VALIDATE_JOB(j6);
            VALIDATE_JOB(j7);
            VALIDATE_JOB(j8);
            VALIDATE_JOB(j9);
            VALIDATE_JOB(j10);
        }
    }
    class ZKGetJob: public TestJob{
    public:
        static const int REPS=1000;
        ZKGetJob(zhandle_t* zh)
            :zh_(zh),rc_(ZAPIERROR){}
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

    void testOperationsAndDisconnectConcurrently2()
    {
        zhandle_t* lzh=zookeeper_init("localhost:3181",watcher,10000,0,0,0);
        CPPUNIT_ASSERT(lzh!=0);
        // make sure the client has connected
        while(lzh->state!=CONNECTED_STATE)
            millisleep(2);
        char realpath[1024];
        int rc=zoo_create(lzh,"/xyz","1",1,&OPEN_ACL_UNSAFE,0,realpath,sizeof(realpath)-1);
        CPPUNIT_ASSERT(rc==ZOK || rc==ZNODEEXISTS);
        zookeeper_close(lzh); 
  
        for(int counter=0; counter<200; counter++){
            TEST_TRACE(("Loop count %d",counter));
            
            CloseFinally guard(&zh);

            zh=zookeeper_init("localhost:3181",watcher,10000,0,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            while(zh->state!=CONNECTED_STATE)
                millisleep(2);
            
            const int THREAD_COUNT=10;
            CountDownLatch startLatch(THREAD_COUNT);
            CountDownLatch endLatch(THREAD_COUNT);

            ZKGetJob j1(zh);
            j1.start(0,&endLatch);
            ZKGetJob j2(zh);
            j2.start(0,&endLatch);
            ZKGetJob j3(zh);
            j3.start(0,&endLatch);
            ZKGetJob j4(zh);
            j4.start(0,&endLatch);
            ZKGetJob j5(zh);
            j5.start(0,&endLatch);
            ZKGetJob j6(zh);
            j6.start(0,&endLatch);
            ZKGetJob j7(zh);
            j7.start(0,&endLatch);
            ZKGetJob j8(zh);
            j8.start(0,&endLatch);
            ZKGetJob j9(zh);
            j9.start(0,&endLatch);
            ZKGetJob j10(zh);
            j10.start(0,&endLatch);
            millisleep(5);

            endLatch.await();
            VALIDATE_JOB(j1);
            VALIDATE_JOB(j2);
            VALIDATE_JOB(j3);
            VALIDATE_JOB(j4);
            VALIDATE_JOB(j5);
            VALIDATE_JOB(j6);
            VALIDATE_JOB(j7);
            VALIDATE_JOB(j8);
            VALIDATE_JOB(j9);
            VALIDATE_JOB(j10);
            TEST_TRACE(("run %d finished",counter));
        }

    }

    class TestConcurrentOpWithDisconnectJob: public TestGetDataJob{
    public:
        static const int REPS=1000;
        TestConcurrentOpWithDisconnectJob(ZookeeperServer* svr,zhandle_t* zh):
            TestGetDataJob(svr,zh,REPS){}
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZCONNECTIONLOSS != rc",ZCONNECTIONLOSS,rc_,file,line);
        }
    };

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
            
            zh=zookeeper_init("localhost:2121",watcher,10000,&testClientId,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            while(zh->state!=CONNECTED_STATE)
                millisleep(1);
            
            const int THREAD_COUNT=10;
            CountDownLatch startLatch(THREAD_COUNT);
            CountDownLatch endLatch(THREAD_COUNT);

            TestConcurrentOpWithDisconnectJob j1(&zkServer,zh);
            j1.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j2(&zkServer,zh);
            j2.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j3(&zkServer,zh);
            j3.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j4(&zkServer,zh);
            j4.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j5(&zkServer,zh);
            j5.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j6(&zkServer,zh);
            j6.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j7(&zkServer,zh);
            j7.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j8(&zkServer,zh);
            j8.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j9(&zkServer,zh);
            j9.start(0,&endLatch);
            TestConcurrentOpWithDisconnectJob j10(&zkServer,zh);
            j10.start(0,&endLatch);
            millisleep(5);
            // reconnect attempts will start failing immediately 
            zkServer.setServerDown(0);
            // next recv call will return 0
            zkServer.setConnectionLost();
            endLatch.await();
            // validate test results
            VALIDATE_JOB(j1);
            VALIDATE_JOB(j2);
            VALIDATE_JOB(j3);
            VALIDATE_JOB(j4);
            VALIDATE_JOB(j5);
            VALIDATE_JOB(j6);
            VALIDATE_JOB(j7);
            VALIDATE_JOB(j8);
            VALIDATE_JOB(j9);
            VALIDATE_JOB(j10);
        }
        
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_operations);
