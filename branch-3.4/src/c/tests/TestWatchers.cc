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
#include "CollectionUtil.h"
#include "Util.h"

class Zookeeper_watchers : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_watchers);
    CPPUNIT_TEST(testDefaultSessionWatcher1);
    CPPUNIT_TEST(testDefaultSessionWatcher2);
    CPPUNIT_TEST(testObjectSessionWatcher1);
    CPPUNIT_TEST(testObjectSessionWatcher2);
    CPPUNIT_TEST(testNodeWatcher1);
    CPPUNIT_TEST(testChildWatcher1);
    CPPUNIT_TEST(testChildWatcher2);
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t *, int, int, const char *,void*){}
    zhandle_t *zh;
    FILE *logfile;
    
public:

    Zookeeper_watchers() {
      logfile = openlogfile("Zookeeper_watchers");
    }

    ~Zookeeper_watchers() {
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
    
    class ConnectionWatcher: public WatcherAction{
    public:
        ConnectionWatcher():connected_(false),counter_(0){}
        virtual void onConnectionEstablished(zhandle_t*){
            synchronized(mx_);
            counter_++;
            connected_=true;
        }
        SyncedBoolCondition isConnectionEstablished() const{
            return SyncedBoolCondition(connected_,mx_);
        }
        bool connected_;
        int counter_;
    };

    class DisconnectWatcher: public WatcherAction{
    public:
        DisconnectWatcher():disconnected_(false),counter_(0){}
        virtual void onConnectionLost(zhandle_t*){
            synchronized(mx_);
            counter_++;
            disconnected_=true;
        }
        SyncedBoolCondition isDisconnected() const{
            return SyncedBoolCondition(disconnected_,mx_);
        }
        bool disconnected_;
        int counter_;
    };

    class CountingDataWatcher: public WatcherAction{
    public:
        CountingDataWatcher():disconnected_(false),counter_(0){}
        virtual void onNodeValueChanged(zhandle_t*,const char* path){
            synchronized(mx_);
            counter_++;
        }
        virtual void onConnectionLost(zhandle_t*){
            synchronized(mx_);
            counter_++;
            disconnected_=true;
        }
        bool disconnected_;
        int counter_;
    };

    class DeletionCountingDataWatcher: public WatcherAction{
    public:
        DeletionCountingDataWatcher():counter_(0){}
        virtual void onNodeDeleted(zhandle_t*,const char* path){
            synchronized(mx_);
            counter_++;
        }
        int counter_;
    };

    class ChildEventCountingWatcher: public WatcherAction{
    public:
        ChildEventCountingWatcher():counter_(0){}
        virtual void onChildChanged(zhandle_t*,const char* path){
            synchronized(mx_);
            counter_++;
        }
        int counter_;
    };

#ifndef THREADED
    
    // verify: the default watcher is called once for a session event
    void testDefaultSessionWatcher1(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        ConnectionWatcher watcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &watcher,0);
        CPPUNIT_ASSERT(zh!=0);
        
        int fd=0;
        int interest=0;
        timeval tv;
        // open the socket
        int rc=zookeeper_interest(zh,&fd,&interest,&tv);        
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTING_STATE,zoo_state(zh));
        // send the handshake packet to the server
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);        
        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE,zoo_state(zh));
        // receive the server handshake response
        rc=zookeeper_process(zh,interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // verify connected
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE,zoo_state(zh));
        CPPUNIT_ASSERT(watcher.connected_);
        CPPUNIT_ASSERT_EQUAL(1,watcher.counter_);
    }
    
    // test case: connect to server, set a default watcher, disconnect from the server
    // verify: the default watcher is called once
    void testDefaultSessionWatcher2(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        DisconnectWatcher watcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &watcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        // first operation
        AsyncCompletion ignored;
        zkServer.addOperationResponse(new ZooGetResponse("1",1));
        int rc=zoo_aget(zh,"/x/y/1",0,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // this will process the response and activate the watcher
        rc=zookeeper_process(zh,ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // now, disconnect
        zkServer.setConnectionLost();
        rc=zookeeper_process(zh,ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS,rc);
        // verify disconnected
        CPPUNIT_ASSERT(watcher.disconnected_);
        CPPUNIT_ASSERT_EQUAL(1,watcher.counter_);
    }
    
    // testcase: connect to the server, set a watcher object on a node, 
    //           disconnect from the server
    // verify: the watcher object as well as the default watcher are called
    void testObjectSessionWatcher1(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        DisconnectWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        AsyncCompletion ignored;
        CountingDataWatcher wobject;
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_awexists(zh,"/x/y/1",activeWatcher,&wobject,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // this will process the response and activate the watcher
        rc=zookeeper_process(zh,ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // now, disconnect
        zkServer.setConnectionLost();
        rc=zookeeper_process(zh,ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS,rc);

        // verify the default watcher has been triggered
        CPPUNIT_ASSERT(defWatcher.disconnected_);
        // and triggered only once
        CPPUNIT_ASSERT_EQUAL(1,defWatcher.counter_);
        
        // the path-specific watcher has been triggered as well
        CPPUNIT_ASSERT(wobject.disconnected_);
        // only once!
        CPPUNIT_ASSERT_EQUAL(1,wobject.counter_);
    }

    // testcase: connect to the server, set a watcher object on a node, 
    //           set a def watcher on another node,disconnect from the server
    // verify: the watcher object as well as the default watcher are called
    void testObjectSessionWatcher2(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        DisconnectWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        // set the default watcher
        AsyncCompletion ignored;
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_aexists(zh,"/a/b/c",1,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        CountingDataWatcher wobject;
        zkServer.addOperationResponse(new ZooStatResponse);
        rc=zoo_awexists(zh,"/x/y/z",activeWatcher,&wobject,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // this will process the response and activate the watcher
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        // disconnect now
        zkServer.setConnectionLost();
        rc=zookeeper_process(zh,ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS,rc);

        // verify the default watcher has been triggered
        CPPUNIT_ASSERT(defWatcher.disconnected_);
        // and triggered only once
        CPPUNIT_ASSERT_EQUAL(1,defWatcher.counter_);
        
        // the path-specific watcher has been triggered as well
        CPPUNIT_ASSERT(wobject.disconnected_);
        // only once!
        CPPUNIT_ASSERT_EQUAL(1,wobject.counter_);
    }

    // testcase: register 2 node watches for different paths, trigger the watches
    // verify: the data watchers are processed, the default watcher is not called
    void testNodeWatcher1(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        DisconnectWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        AsyncCompletion ignored;
        CountingDataWatcher wobject1;
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_awexists(zh,"/a/b/c",activeWatcher,&wobject1,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        CountingDataWatcher wobject2;
        zkServer.addOperationResponse(new ZooStatResponse);
        rc=zoo_awexists(zh,"/x/y/z",activeWatcher,&wobject2,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // this will process the response and activate the watcher
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/a/b/c"));
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/x/y/z"));
        // make sure all watchers have been processed
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);
        
        CPPUNIT_ASSERT_EQUAL(1,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }

    // testcase: set up both a children and a data watchers on the node /a, then
    //           delete the node by sending a DELETE_EVENT event
    // verify: both watchers are triggered
    void testChildWatcher1(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        DeletionCountingDataWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        AsyncCompletion ignored;
        DeletionCountingDataWatcher wobject1;
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_awexists(zh,"/a",activeWatcher,&wobject1,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        typedef ZooGetChildrenResponse::StringVector ZooVector;
        zkServer.addOperationResponse(new ZooGetChildrenResponse(
                Util::CollectionBuilder<ZooVector>()("/a/1")("/a/2")
                ));
        DeletionCountingDataWatcher wobject2;
        rc=zoo_awget_children(zh,"/a",activeWatcher,
                &wobject2,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // this will process the response and activate the watcher
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_DELETED_EVENT,"/a"));
        // make sure the watchers have been processed
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        CPPUNIT_ASSERT_EQUAL(1,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }

    // testcase: create both a child and data watch on the node /a, send a ZOO_CHILD_EVENT
    // verify: only the child watch triggered
    void testChildWatcher2(){
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        // must call zookeeper_close() while all the mocks are in scope
        CloseFinally guard(&zh);

        ChildEventCountingWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // simulate connected state
        forceConnected(zh);
        
        AsyncCompletion ignored;
        ChildEventCountingWatcher wobject1;
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_awexists(zh,"/a",activeWatcher,&wobject1,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        typedef ZooGetChildrenResponse::StringVector ZooVector;
        zkServer.addOperationResponse(new ZooGetChildrenResponse(
                Util::CollectionBuilder<ZooVector>()("/a/1")("/a/2")
                ));
        ChildEventCountingWatcher wobject2;
        rc=zoo_awget_children(zh,"/a",activeWatcher,
                &wobject2,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // this will process the response and activate the watcher
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHILD_EVENT,"/a"));
        // make sure the watchers have been processed
        while((rc=zookeeper_process(zh,ZOOKEEPER_READ))==ZOK) {
          millisleep(100);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING,rc);

        CPPUNIT_ASSERT_EQUAL(0,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }

#else
    // verify: the default watcher is called once for a session event
    void testDefaultSessionWatcher1(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_SESSION_EVENT,ZOO_CONNECTED_STATE);
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        ConnectionWatcher watcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &watcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // wait till watcher proccessing has completed (the connection 
        // established event)
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);
        
        // verify the watcher has been triggered
        CPPUNIT_ASSERT(ensureCondition(watcher.isConnectionEstablished(),1000)<1000);
        // triggered only once
        CPPUNIT_ASSERT_EQUAL(1,watcher.counter_);
    }

    // test case: connect to server, set a default watcher, disconnect from the server
    // verify: the default watcher is called once
    void testDefaultSessionWatcher2(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_SESSION_EVENT,ZOO_CONNECTING_STATE);
        DisconnectWatcher watcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &watcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
        // set a default watch
        AsyncCompletion ignored;
        // a successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        int rc=zoo_aexists(zh,"/x/y/z",1,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // now, initiate a disconnect
        zkServer.setConnectionLost();
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);
        
        // verify the watcher has been triggered
        CPPUNIT_ASSERT(watcher.disconnected_);
        // triggered only once
        CPPUNIT_ASSERT_EQUAL(1,watcher.counter_);
    }

    // testcase: connect to the server, set a watcher object on a node, 
    //           disconnect from the server
    // verify: the watcher object as well as the default watcher are called
    void testObjectSessionWatcher1(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_SESSION_EVENT,ZOO_CONNECTING_STATE);
        DisconnectWatcher defWatcher;
        // use the tracker to find out when the watcher has been activated
        WatcherActivationTracker activationTracker;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);

        AsyncCompletion ignored;
        // this successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        CountingDataWatcher wobject;
        activationTracker.track(&wobject);
        // set a path-specific watcher
        int rc=zoo_awexists(zh,"/x/y/z",activeWatcher,&wobject,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // make sure the watcher gets activated before we continue
        CPPUNIT_ASSERT(ensureCondition(activationTracker.isWatcherActivated(),1000)<1000);
        
        // now, initiate a disconnect
        zkServer.setConnectionLost();
        // make sure all watchers have been processed
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);
        
        // verify the default watcher has been triggered
        CPPUNIT_ASSERT(defWatcher.disconnected_);
        // and triggered only once
        CPPUNIT_ASSERT_EQUAL(1,defWatcher.counter_);
        
        // the path-specific watcher has been triggered as well
        CPPUNIT_ASSERT(wobject.disconnected_);
        // only once!
        CPPUNIT_ASSERT_EQUAL(1,wobject.counter_);
    }

    // testcase: connect to the server, set a watcher object on a node, 
    //           set a def watcher on another node,disconnect from the server
    // verify: the watcher object as well as the default watcher are called
    void testObjectSessionWatcher2(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_SESSION_EVENT,ZOO_CONNECTING_STATE);
        DisconnectWatcher defWatcher;
        // use the tracker to find out when the watcher has been activated
        WatcherActivationTracker activationTracker;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);

        // set a default watch
        AsyncCompletion ignored;
        // a successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        activationTracker.track(&defWatcher);
        int rc=zoo_aexists(zh,"/a/b/c",1,asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // make sure the watcher gets activated before we continue
        CPPUNIT_ASSERT(ensureCondition(activationTracker.isWatcherActivated(),1000)<1000);

        // this successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        CountingDataWatcher wobject;
        activationTracker.track(&wobject);
        // set a path-specific watcher
        rc=zoo_awexists(zh,"/x/y/z",activeWatcher,&wobject,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // make sure the watcher gets activated before we continue
        CPPUNIT_ASSERT(ensureCondition(activationTracker.isWatcherActivated(),1000)<1000);
        
        // now, initiate a disconnect
        zkServer.setConnectionLost();
        // make sure all watchers have been processed
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);
        
        // verify the default watcher has been triggered
        CPPUNIT_ASSERT(defWatcher.disconnected_);
        // and triggered only once
        CPPUNIT_ASSERT_EQUAL(1,defWatcher.counter_);
        
        // the path-specific watcher has been triggered as well
        CPPUNIT_ASSERT(wobject.disconnected_);
        // only once!
        CPPUNIT_ASSERT_EQUAL(1,wobject.counter_);
    }

    // testcase: register 2 node watches for different paths, trigger the watches
    // verify: the data watchers are processed, the default watcher is not called
    void testNodeWatcher1(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_CHANGED_EVENT,0,false);
        CountingDataWatcher defWatcher;
        // use the tracker to find out when the watcher has been activated
        WatcherActivationTracker activationTracker;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);

        // don't care about completions
        AsyncCompletion ignored;
        // set a one-shot watch
        // a successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        CountingDataWatcher wobject1;
        activationTracker.track(&wobject1);
        int rc=zoo_awexists(zh,"/a/b/c",activeWatcher,&wobject1,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // make sure the watcher gets activated before we continue
        CPPUNIT_ASSERT(ensureCondition(activationTracker.isWatcherActivated(),1000)<1000);

        // this successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        CountingDataWatcher wobject2;
        activationTracker.track(&wobject2);
        // set a path-specific watcher
        rc=zoo_awexists(zh,"/x/y/z",activeWatcher,&wobject2,
                asyncCompletion,&ignored);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        // make sure the watcher gets activated before we continue
        CPPUNIT_ASSERT(ensureCondition(activationTracker.isWatcherActivated(),1000)<1000);

        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/a/b/c"));
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHANGED_EVENT,"/x/y/z"));
        // make sure all watchers have been processed
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.deliveryCounterEquals(2),1000)<1000);
        
        CPPUNIT_ASSERT_EQUAL(1,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }

    // testcase: set up both a children and a data watchers on the node /a, then
    //           delete the node (that is, send a DELETE_EVENT)
    // verify: both watchers are triggered
    void testChildWatcher1(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_DELETED_EVENT,0);
        DeletionCountingDataWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
        
        // a successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        DeletionCountingDataWatcher wobject1;
        Stat stat;
        // add a node watch
        int rc=zoo_wexists(zh,"/a",activeWatcher,&wobject1,&stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        typedef ZooGetChildrenResponse::StringVector ZooVector;
        zkServer.addOperationResponse(new ZooGetChildrenResponse(
                Util::CollectionBuilder<ZooVector>()("/a/1")("/a/2")
                ));
        DeletionCountingDataWatcher wobject2;
        String_vector children;
        rc=zoo_wget_children(zh,"/a",activeWatcher,&wobject2,&children);
        deallocate_String_vector(&children);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_DELETED_EVENT,"/a"));
        // make sure the watchers have been processed
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);

        CPPUNIT_ASSERT_EQUAL(1,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }
    
    // testcase: create both a child and data watch on the node /a, send a ZOO_CHILD_EVENT
    // verify: only the child watch triggered
    void testChildWatcher2(){
        Mock_gettimeofday timeMock;
        // zookeeper simulator
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        // detects when all watchers have been delivered
        WatcherDeliveryTracker deliveryTracker(ZOO_CHILD_EVENT,0);
        ChildEventCountingWatcher defWatcher;
        zh=zookeeper_init("localhost:2121",activeWatcher,10000,TEST_CLIENT_ID,
                &defWatcher,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh),1000)<1000);
        // a successful server response will activate the watcher 
        zkServer.addOperationResponse(new ZooStatResponse);
        ChildEventCountingWatcher wobject1;
        Stat stat;
        // add a node watch
        int rc=zoo_wexists(zh,"/a",activeWatcher,&wobject1,&stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
        
        typedef ZooGetChildrenResponse::StringVector ZooVector;
        zkServer.addOperationResponse(new ZooGetChildrenResponse(
                Util::CollectionBuilder<ZooVector>()("/a/1")("/a/2")
                ));
        ChildEventCountingWatcher wobject2;
        String_vector children;
        rc=zoo_wget_children(zh,"/a",activeWatcher,&wobject2,&children);
        deallocate_String_vector(&children);
        CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);

        // we are all set now; let's trigger the watches
        zkServer.addRecvResponse(new ZNodeEvent(ZOO_CHILD_EVENT,"/a"));
        // make sure the watchers have been processed
        CPPUNIT_ASSERT(ensureCondition(
                deliveryTracker.isWatcherProcessingCompleted(),1000)<1000);

        CPPUNIT_ASSERT_EQUAL(0,wobject1.counter_);
        CPPUNIT_ASSERT_EQUAL(1,wobject2.counter_);        
        CPPUNIT_ASSERT_EQUAL(0,defWatcher.counter_);
    }

#endif //THREADED
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_watchers);
