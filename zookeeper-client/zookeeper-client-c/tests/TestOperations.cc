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
#include "CppAssertHelper.h"

#include "ZKMocks.h"
#include <proto.h>

using namespace std;

class Zookeeper_operations : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_operations);
#ifndef THREADED
    CPPUNIT_TEST(testPing);
    CPPUNIT_TEST(testUnsolicitedPing);
    CPPUNIT_TEST(testTimeoutCausedByWatches1);
    CPPUNIT_TEST(testTimeoutCausedByWatches2);
    CPPUNIT_TEST(testCloseWhileInProgressFromMain);
    CPPUNIT_TEST(testCloseWhileInProgressFromCompletion);
    CPPUNIT_TEST(testCloseWhileMultiInProgressFromMain);
    CPPUNIT_TEST(testCloseWhileMultiInProgressFromCompletion);
    CPPUNIT_TEST(testConnectResponseFull);
    CPPUNIT_TEST(testConnectResponseNoReadOnlyFlag);
    CPPUNIT_TEST(testConnectResponseSplitAtReadOnlyFlag);
    CPPUNIT_TEST(testConnectResponseNoReadOnlyFlagSplit);
#else    
    CPPUNIT_TEST(testAsyncWatcher1);
    CPPUNIT_TEST(testAsyncGetOperation);
#endif
    CPPUNIT_TEST(testOperationsAndDisconnectConcurrently1);
    CPPUNIT_TEST(testOperationsAndDisconnectConcurrently2);
    CPPUNIT_TEST(testConcurrentOperations1);
    CPPUNIT_TEST_SUITE_END();
    zhandle_t *zh;
    FILE *logfile;

    static void watcher(zhandle_t *, int, int, const char *,void*){}
public: 
    Zookeeper_operations() {
      logfile = openlogfile("Zookeeper_operations");
    }

    ~Zookeeper_operations() {
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

    class AsyncVoidOperationCompletion: public AsyncCompletion{
    public:
        AsyncVoidOperationCompletion():called_(false),rc_(ZAPIERROR){}
        virtual void voidCompl(int rc){
            synchronized(mx_);
            called_=true;
            rc_=rc;
        }
        bool operator()()const{
            synchronized(mx_);
            return called_;
        }
        mutable Mutex mx_;
        bool called_;
        int rc_;
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
        forceConnected(zh);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // process the send queue
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        while((rc=zookeeper_process(zh,interest))==ZOK) {
          millisleep(100);
          //printf("%d\n", rc);
        }
        //printf("RC = %d", rc);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        CPPUNIT_ASSERT_EQUAL((int)ZOK,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(string("1"),res1.value_);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,res2.rc_);
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
        forceConnected(zh);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // process the send queue
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // simulate a disconnect
        zkServer.setConnectionLost();
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS,rc);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,res1.rc_);
        CPPUNIT_ASSERT_EQUAL(string("1"),res1.value_);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS,res2.rc_);
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
        forceConnected(zh);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // first operation
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // second operation
        AsyncGetOperationCompletion res2;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        rc=zoo_aget(zh,"/x/y/2",0,asyncCompletion,&res2);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // simulate timeout
        timeMock.tick(+10); // advance system time by 10 secs
        // the next call to zookeeper_interest should return ZOPERATIONTIMEOUT
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOPERATIONTIMEOUT,rc);
        // make sure the completions have been called
        CPPUNIT_ASSERT_EQUAL((int)ZOPERATIONTIMEOUT,res1.rc_);
        CPPUNIT_ASSERT_EQUAL((int)ZOPERATIONTIMEOUT,res2.rc_);
    }

    class PingCountingServer: public ZookeeperServer{
    public:
        PingCountingServer():pingCount_(0){}
        // called when a client request is received
        virtual void onMessageReceived(const RequestHeader& rh, iarchive* ia){
           if(rh.type==ZOO_PING_OP){
               pingCount_++;
           }
        }
        int pingCount_;
    };

    // establish a connection; idle for a while
    // verify ping was sent at least once
    void testPing()
    {
        const int TIMEOUT=9; // timeout in secs
        Mock_gettimeofday timeMock;
        PingCountingServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        // receive timeout is in milliseconds
        zh=zookeeper_init("localhost:1234",watcher,TIMEOUT*1000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // Round 1.
        int rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // simulate waiting for the select() call to timeout; 
        // advance the system clock accordingly
        timeMock.tick(tv);  
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        // verify no ping sent
        CPPUNIT_ASSERT(zkServer.pingCount_==0);
        
        // Round 2.
        // the client should have the idle threshold exceeded, by now
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // assume the socket is writable, so no idling here; move on to 
        // zookeeper_process immediately
        rc=zookeeper_process(zh,interest);
        // ZNOTHING means the client hasn't received a ping response yet
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        // verify a ping is sent
        CPPUNIT_ASSERT_EQUAL(1,zkServer.pingCount_);
        
        // Round 3.
        // we're going to receive a server PING response and make sure
        // that the client has updated its last_recv timestamp 
        zkServer.addRecvResponse(new PingResponse);
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // pseudo-sleep for a short while (10 ms)
        timeMock.millitick(10);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // only one ping so far?
        CPPUNIT_ASSERT_EQUAL(1,zkServer.pingCount_);
        CPPUNIT_ASSERT(timeMock==zh->last_recv);

        // Round 4
        // make sure that a ping is not sent if something is outstanding
        AsyncGetOperationCompletion res1;
        rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        timeMock.tick(tv);  
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // pseudo-sleep for a short while (10 ms)
        timeMock.millitick(10);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        // only one ping so far?
        CPPUNIT_ASSERT_EQUAL(1,zkServer.pingCount_);
    }

    // ZOOKEEPER-2253: Permit unsolicited pings
    void testUnsolicitedPing()
    {
        const int TIMEOUT=9; // timeout in secs
        Mock_gettimeofday timeMock;
        PingCountingServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        // receive timeout is in milliseconds
        zh=zookeeper_init("localhost:1234",watcher,TIMEOUT*1000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);

        int fd=0;
        int interest=0;
        timeval tv;

        int rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // verify no ping sent
        CPPUNIT_ASSERT(zkServer.pingCount_==0);

        // we're going to receive a unsolicited PING response; ensure
        // that the client has updated its last_recv timestamp
        timeMock.tick(tv);
        zkServer.addRecvResponse(new PingResponse);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        CPPUNIT_ASSERT(timeMock==zh->last_recv);
    }

    // simulate a watch arriving right before a ping is due
    // assert the ping is sent nevertheless
    void testTimeoutCausedByWatches1()
    {
        const int TIMEOUT=9; // timeout in secs
        Mock_gettimeofday timeMock;
        PingCountingServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        // receive timeout is in milliseconds
        zh=zookeeper_init("localhost:1234",watcher,TIMEOUT*1000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // Round 1.
        int rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // simulate waiting for the select() call to timeout; 
        // advance the system clock accordingly
        timeMock.tick(tv);
        timeMock.tick(-1); // set the clock to a millisecond before a ping is due
        // trigger a watch now
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/x/y/z"));
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // arrival of a watch sets the last_recv to the current time
        CPPUNIT_ASSERT(timeMock==zh->last_recv);
        // spend 1 millisecond by processing the watch
        timeMock.tick(1);
        
        // Round 2.
        // a ping is due; zookeeper_interest() must send it now
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // no delay here -- as if the socket is immediately writable
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        // verify a ping is sent
        CPPUNIT_ASSERT_EQUAL(1,zkServer.pingCount_);        
    }

    // similar to testTimeoutCausedByWatches1, but this time the watch is 
    // triggered while the client has an outstanding request
    // assert the ping is sent on time
    void testTimeoutCausedByWatches2()
    {
        const int TIMEOUT=9; // timeout in secs
        Mock_gettimeofday now;
        PingCountingServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);
        
        // receive timeout is in milliseconds
        zh=zookeeper_init("localhost:1234",watcher,TIMEOUT*1000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        // queue up a request; keep it pending (as if the server is busy or has died)
        AsyncGetOperationCompletion res1;
        zkServer.addOperationResponse(new ZooGetResponse("2",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);

        int fd=0;
        int interest=0;
        timeval tv;
        // Round 1.
        // send the queued up zoo_aget() request
        Mock_gettimeofday beginningOfTimes(now); // remember when we started
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // no delay -- the socket is writable
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc); 
        
        // Round 2.
        // what's next?
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // no response from the server yet -- waiting in the select() call
        now.tick(tv);
        // a watch has arrived, thus preventing the connection from timing out 
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/x/y/z"));        
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc); // read the watch message
        CPPUNIT_ASSERT_EQUAL(0,zkServer.pingCount_); // not yet!
        
        //Round 3.
        // now is the time to send a ping; make sure it's actually sent
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        // verify a ping is sent
        CPPUNIT_ASSERT_EQUAL(1,zkServer.pingCount_);
        // make sure only 1/3 of the timeout has passed
        CPPUNIT_ASSERT_EQUAL((int32_t)TIMEOUT/3*1000,toMilliseconds(now-beginningOfTimes));
    }

    // ZOOKEEPER-2894: Memory and completions leak on zookeeper_close
    // while there is a request waiting for being processed
    // call zookeeper_close() from the main event loop
    // assert the completion callback is called
    void testCloseWhileInProgressFromMain()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        forceConnected(zh);
        zhandle_t* savezh=zh;

        // issue a request
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        AsyncGetOperationCompletion res1;
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // but do not allow Zookeeper C Client to process the request
        // and call zookeeper_close() from the main event loop immediately
        Mock_free_noop freeMock;
        rc=zookeeper_close(zh); zh=0;
        freeMock.disable();
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // verify that memory for completions was freed (would be freed if no mock installed)
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT(savezh->completions_to_process.head==0);
        CPPUNIT_ASSERT(savezh->completions_to_process.last==0);

        // verify that completion was called, and it was called with ZCLOSING status
        CPPUNIT_ASSERT(res1.called_);
        CPPUNIT_ASSERT_EQUAL((int)ZCLOSING,res1.rc_);
    }

    // ZOOKEEPER-2894: Memory and completions leak on zookeeper_close
    // send some request #1
    // then, while there is a request #2 waiting for being processed
    // call zookeeper_close() from the completion callback of request #1
    // assert the completion callback #2 is called
    void testCloseWhileInProgressFromCompletion()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        forceConnected(zh);
        zhandle_t* savezh=zh;

        // will handle completion on request #1 and issue request #2 from it
        class AsyncGetOperationCompletion1: public AsyncCompletion{
        public:
            AsyncGetOperationCompletion1(zhandle_t **zh, ZookeeperServer *zkServer, 
                    AsyncGetOperationCompletion *res2)
            :zh_(zh),zkServer_(zkServer),res2_(res2){}

            virtual void dataCompl(int rc1, const char *value, int len, const Stat *stat){
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc1);

                // from the completion #1 handler, issue request #2
                zkServer_->addOperationResponse(new ZooGetResponse("2",1));
                int rc2=zoo_aget(*zh_,"/x/y/2",0,asyncCompletion,res2_);
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc2);

                // but do not allow Zookeeper C Client to process the request #2
                // and call zookeeper_close() from the completion callback of request #1
                rc2=zookeeper_close(*zh_); *zh_=0;
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc2);

                // do not disable freeMock here, let completion #2 handler
                // return through ZooKeeper C Client internals to the main loop
                // and fulfill the work
            }

            zhandle_t **zh_;
            ZookeeperServer *zkServer_;
            AsyncGetOperationCompletion *res2_;
        };

        // issue request #1
        AsyncGetOperationCompletion res2;
        AsyncGetOperationCompletion1 res1(&zh,&zkServer,&res2);
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // process the send queue
        int fd; int interest; timeval tv;
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        CPPUNIT_ASSERT(zh!=0);
        Mock_free_noop freeMock;
        while(zh!=0 && (rc=zookeeper_process(zh,interest))==ZOK) {
          millisleep(100);
        }
        freeMock.disable();
        CPPUNIT_ASSERT(zh==0);

        // verify that memory for completions was freed (would be freed if no mock installed)
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT(savezh->completions_to_process.head==0);
        CPPUNIT_ASSERT(savezh->completions_to_process.last==0);

        // verify that completion #2 was called, and it was called with ZCLOSING status
        CPPUNIT_ASSERT(res2.called_);
        CPPUNIT_ASSERT_EQUAL((int)ZCLOSING,res2.rc_);
    }

    // ZOOKEEPER-2891: Invalid processing of zookeeper_close for mutli-request
    // while there is a multi request waiting for being processed
    // call zookeeper_close() from the main event loop
    // assert the completion callback is called with status ZCLOSING
    void testCloseWhileMultiInProgressFromMain()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        forceConnected(zh);
        zhandle_t* savezh=zh;

        // issue a multi request
        int nops=2;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];
        zoo_create_op_init(&ops[0],"/a",0,-1,&ZOO_OPEN_ACL_UNSAFE,0,0,0);
        zoo_create_op_init(&ops[1],"/a/b",0,-1,&ZOO_OPEN_ACL_UNSAFE,0,0,0);
        // TODO: Provide ZooMultiResponse. However, it's not required in this test.
        // zkServer.addOperationResponse(new ZooMultiResponse(...));
        AsyncVoidOperationCompletion res1;
        int rc=zoo_amulti(zh,nops,ops,results,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // but do not allow Zookeeper C Client to process the request
        // and call zookeeper_close() from the main event loop immediately
        Mock_free_noop freeMock;
        rc=zookeeper_close(zh); zh=0;
        freeMock.disable();
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // verify that memory for completions was freed (would be freed if no mock installed)
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT(savezh->completions_to_process.head==0);
        CPPUNIT_ASSERT(savezh->completions_to_process.last==0);

        // verify that completion was called, and it was called with ZCLOSING status
        CPPUNIT_ASSERT(res1.called_);
        CPPUNIT_ASSERT_EQUAL((int)ZCLOSING,res1.rc_);
    }

    // ZOOKEEPER-2891: Invalid processing of zookeeper_close for mutli-request
    // send some request #1 (not a multi request)
    // then, while there is a multi request #2 waiting for being processed
    // call zookeeper_close() from the completion callback of request #1
    // assert the completion callback #2 is called with status ZCLOSING
    void testCloseWhileMultiInProgressFromCompletion()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh=zookeeper_init("localhost:2121",watcher,10000,TEST_CLIENT_ID,0,0);
        CPPUNIT_ASSERT(zh!=0);
        forceConnected(zh);
        zhandle_t* savezh=zh;

        // these shall persist during the test
        int nops=2;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        // will handle completion on request #1 and issue request #2 from it
        class AsyncGetOperationCompletion1: public AsyncCompletion{
        public:
            AsyncGetOperationCompletion1(zhandle_t **zh, ZookeeperServer *zkServer,
                    AsyncVoidOperationCompletion *res2,
                    int nops, zoo_op_t* ops, zoo_op_result_t* results)
            :zh_(zh),zkServer_(zkServer),res2_(res2),nops_(nops),ops_(ops),results_(results){}

            virtual void dataCompl(int rc1, const char *value, int len, const Stat *stat){
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc1);

                // from the completion #1 handler, issue multi request #2
                assert(nops_>=2);
                zoo_create_op_init(&ops_[0],"/a",0,-1,&ZOO_OPEN_ACL_UNSAFE,0,0,0);
                zoo_create_op_init(&ops_[1],"/a/b",0,-1,&ZOO_OPEN_ACL_UNSAFE,0,0,0);
                // TODO: Provide ZooMultiResponse. However, it's not required in this test.
                // zkServer_->addOperationResponse(new ZooMultiResponse(...));
                int rc2=zoo_amulti(*zh_,nops_,ops_,results_,asyncCompletion,res2_);
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc2);

                // but do not allow Zookeeper C Client to process the request #2
                // and call zookeeper_close() from the completion callback of request #1
                rc2=zookeeper_close(*zh_); *zh_=0;
                CPPUNIT_ASSERT_EQUAL((int)ZOK,rc2);

                // do not disable freeMock here, let completion #2 handler
                // return through ZooKeeper C Client internals to the main loop
                // and fulfill the work
            }

            zhandle_t **zh_;
            ZookeeperServer *zkServer_;
            AsyncVoidOperationCompletion *res2_;
            int nops_;
            zoo_op_t* ops_;
            zoo_op_result_t* results_;
        };

        // issue some request #1 (not a multi request)
        AsyncVoidOperationCompletion res2;
        AsyncGetOperationCompletion1 res1(&zh,&zkServer,&res2,nops,ops,results);
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&res1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // process the send queue
        int fd; int interest; timeval tv;
        rc=zookeeper_interest(zh,&fd,&interest,&tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        CPPUNIT_ASSERT(zh!=0);
        Mock_free_noop freeMock;
        while(zh!=0 && (rc=zookeeper_process(zh,interest))==ZOK) {
          millisleep(100);
        }
        freeMock.disable();
        CPPUNIT_ASSERT(zh==0);

        // verify that memory for completions was freed (would be freed if no mock installed)
        CPPUNIT_ASSERT_EQUAL(1,freeMock.getFreeCount(savezh));
        CPPUNIT_ASSERT(savezh->completions_to_process.head==0);
        CPPUNIT_ASSERT(savezh->completions_to_process.last==0);

        // verify that completion #2 was called, and it was called with ZCLOSING status
        CPPUNIT_ASSERT(res2.called_);
        CPPUNIT_ASSERT_EQUAL((int)ZCLOSING,res2.rc_);
    }

    void testConnectResponseFull()
    {
        CloseFinally guard(&zh);
        Mock_socket socketMock;
        HandshakeResponse hsResponse;
        std::string hsResponseData = hsResponse.toString();

        CPPUNIT_ASSERT_EQUAL(hsResponseData.length(), static_cast<size_t>(41));

        zh = zookeeper_init("localhost:2121", watcher, 10000, NULL, NULL, 0);
        CPPUNIT_ASSERT(zh!=0);

        int rc, fd, interest;
        timeval tv;

        rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        socketMock.recvReturnBuffer = hsResponseData;
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, static_cast<int>(zh->state));
    }

    void testConnectResponseNoReadOnlyFlag()
    {
        CloseFinally guard(&zh);
        Mock_socket socketMock;
        HandshakeResponse hsResponse;

        hsResponse.omitReadOnly = true;

        std::string hsResponseData = hsResponse.toString();

        CPPUNIT_ASSERT_EQUAL(hsResponseData.length(), static_cast<size_t>(40));

        zh = zookeeper_init("localhost:2121", watcher, 10000, NULL, NULL, 0);
        CPPUNIT_ASSERT(zh!=0);

        int rc, fd, interest;
        timeval tv;

        rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        socketMock.recvReturnBuffer = hsResponseData;
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, static_cast<int>(zh->state));
    }

    void testConnectResponseSplitAtReadOnlyFlag()
    {
        CloseFinally guard(&zh);
        Mock_socket socketMock;
        HandshakeResponse hsResponse;
        std::string hsResponseData = hsResponse.toString();

        CPPUNIT_ASSERT_EQUAL(hsResponseData.length(), static_cast<size_t>(41));

        zh = zookeeper_init("localhost:2121", watcher, 10000, NULL, NULL, 0);
        CPPUNIT_ASSERT(zh!=0);

        int rc, fd, interest;
        timeval tv;

        rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        socketMock.recvReturnBuffer = hsResponseData.substr(0, 40);
        rc = zookeeper_process(zh, interest);
        // Response not complete.
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZNOTHING), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE, static_cast<int>(zh->state));

        socketMock.recvReturnBuffer = hsResponseData.substr(40);
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, static_cast<int>(zh->state));
    }

    void testConnectResponseNoReadOnlyFlagSplit()
    {
        CloseFinally guard(&zh);
        Mock_socket socketMock;
        HandshakeResponse hsResponse;

        hsResponse.omitReadOnly = true;

        std::string hsResponseData = hsResponse.toString();

        CPPUNIT_ASSERT_EQUAL(hsResponseData.length(), static_cast<size_t>(40));

        zh = zookeeper_init("localhost:2121", watcher, 10000, NULL, NULL, 0);
        CPPUNIT_ASSERT(zh!=0);

        int rc, fd, interest;
        timeval tv;

        rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        socketMock.recvReturnBuffer = hsResponseData.substr(0, 20);
        rc = zookeeper_process(zh, interest);
        // Response not complete.
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZNOTHING), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE, static_cast<int>(zh->state));

        socketMock.recvReturnBuffer = hsResponseData.substr(20);
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL(static_cast<int>(ZOK), rc);

        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, static_cast<int>(zh->state));
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

                if (i % 10 == 0) {
                    // We need to pause every once in a while so we don't
                    // get too far ahead and finish before the disconnect
	            millisleep(1);
                }
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
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",(int)ZOK,rc_,file,line);
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
            //TEST_TRACE("Finished %d iterations",i);
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",(int)ZOK,rc_,file,line);
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
        int rc=zoo_create(lzh,"/xyz","1",1,&ZOO_OPEN_ACL_UNSAFE,0,realpath,sizeof(realpath)-1);
        CPPUNIT_ASSERT(rc==ZOK || rc==ZNODEEXISTS);
        zookeeper_close(lzh); 
  
        for(int counter=0; counter<200; counter++){
            TEST_TRACE("Loop count %d",counter);
            
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
            TEST_TRACE("run %d finished",counter);
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
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZCONNECTIONLOSS != rc",(int)ZCONNECTIONLOSS,rc_,file,line);
        }
    };

    // this test is not 100% accurate in a sense it may not detect all error cases.
    // TODO: I can't think of a test that is 100% accurate and doesn't interfere
    //       with the code being tested (in terms of introducing additional 
    //       implicit synchronization points)
    void testOperationsAndDisconnectConcurrently1()
    {
        for(int counter=0; counter<50; counter++){
            //TEST_TRACE("Loop count %d",counter);
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
            // let everything startup before we shutdown the server
            millisleep(4);
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
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        CPPUNIT_ASSERT(ensureCondition(res1,1000)<1000);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,res1.rc_);
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
        // this predicate checks if CHANGE_EVENT event type was triggered, unlike
        // the isWatcherTriggered() that returns true whenever a watcher is triggered
        // regardless of the event type
        SyncedBoolCondition isNodeChangedTriggered() const{
            return SyncedBoolCondition(changed_,mx_);
        }
        bool changed_;
        string path_;
    };
    
    class AsyncWatcherCompletion: public AsyncCompletion{
    public:
        AsyncWatcherCompletion(ZookeeperServer& zkServer):zkServer_(zkServer){}
        virtual void statCompl(int rc, const Stat *stat){
            // we received a server response, now enqueue a watcher event
            // to trigger the watcher
            zkServer_.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/x/y/z"));
        }
        ZookeeperServer& zkServer_;
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
        
        // set the watcher
        AsyncWatcherCompletion completion(zkServer);
        // prepare a response for the zoo_aexists() request
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_aexists(zh,"/x/y/z",1,asyncCompletion,&completion);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        CPPUNIT_ASSERT(ensureCondition(action.isNodeChangedTriggered(),1000)<1000);
        CPPUNIT_ASSERT_EQUAL(string("/x/y/z"),action.path_);                
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_operations);
