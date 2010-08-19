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
#include <cppunit/Test.h>
#include <cppunit/TestSuite.h>
#include <cppunit/extensions/HelperMacros.h>

#include "../lib/clientimpl.h"
#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <stdexcept>
#include <pthread.h>

#include <log4cpp/Category.hh>

#include "servercontrol.h"
#include "util.h"

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwigtest."__FILE__);

class SubscribeTestSuite : public CppUnit::TestFixture {
private:
  HedwigTest::ServerControl* control;
  HedwigTest::TestServerPtr zk;
  HedwigTest::TestServerPtr bk1;
  HedwigTest::TestServerPtr bk2;
  HedwigTest::TestServerPtr bk3;
  HedwigTest::TestServerPtr hw1;
  HedwigTest::TestServerPtr hw2;

			       
  CPPUNIT_TEST_SUITE( SubscribeTestSuite );
  CPPUNIT_TEST(testSyncSubscribe);
  CPPUNIT_TEST(testSyncSubscribeAttach);
  CPPUNIT_TEST(testAsyncSubscribe);
  CPPUNIT_TEST(testAsyncSubcribeAndUnsubscribe);
  CPPUNIT_TEST(testAsyncSubcribeAndSyncUnsubscribe);
  CPPUNIT_TEST(testAsyncSubcribeCloseSubscriptionAndThenResubscribe);
  CPPUNIT_TEST(testUnsubscribeWithoutSubscribe);
  CPPUNIT_TEST(testSubscribeTwice);      
  CPPUNIT_TEST_SUITE_END();

public:
  SubscribeTestSuite() {
    
  }

  ~SubscribeTestSuite() {
  }

  void setUp()
  {
    control = new HedwigTest::ServerControl(HedwigTest::DEFAULT_CONTROLSERVER_PORT);
    zk = control->startZookeeperServer(12345);
    bk1 = control->startBookieServer(12346, zk);
    bk2 = control->startBookieServer(12347, zk);
    bk3 = control->startBookieServer(12348, zk);
    
    std::string region("testRegion");
    hw1 = control->startPubSubServer(12349, region, zk);
    hw2 = control->startPubSubServer(12350, region, zk);
  }
  
  void tearDown() 
  {
    try {
      hw1->kill();
    
      bk1->kill();
      bk2->kill();
      bk3->kill();
      
      zk->kill();
    } catch (std::exception& e) {
      // don't allow an exception to break everything, we're going deleting the control no matter what
    }
    delete control;
  }

  void testSyncSubscribe() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    
    sub.subscribe("testTopic", "mySubscriberId-1", Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
  }

  void testSyncSubscribeAttach() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);

    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    
    CPPUNIT_ASSERT_THROW(sub.subscribe("iAmATopicWhoDoesNotExist", "mySubscriberId-2", Hedwig::SubscribeRequest::ATTACH), Hedwig::ClientException);
  }

  void testAsyncSubscribe() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    std::auto_ptr<SimpleWaitCondition> cond1ptr(cond1);

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);

    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
   
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));

    sub.asyncSubscribe("testTopic", "mySubscriberId-3", Hedwig::SubscribeRequest::CREATE_OR_ATTACH, testcb1);
    
    cond1->wait();
  }
  
  void testAsyncSubcribeAndUnsubscribe() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    std::auto_ptr<SimpleWaitCondition> cond1ptr(cond1);
    SimpleWaitCondition* cond2 = new SimpleWaitCondition();
    std::auto_ptr<SimpleWaitCondition> cond2ptr(cond2);

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);

    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
   
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));
    Hedwig::OperationCallbackPtr testcb2(new TestCallback(cond2));

    sub.asyncSubscribe("testTopic", "mySubscriberId-4", Hedwig::SubscribeRequest::CREATE_OR_ATTACH, testcb1);
    cond1->wait();
    
    sub.asyncUnsubscribe("testTopic", "mySubscriberId-4", testcb2);
    cond2->wait();
  }

  void testAsyncSubcribeAndSyncUnsubscribe() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    std::auto_ptr<SimpleWaitCondition> cond1ptr(cond1);

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);

    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
   
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));
    
    sub.asyncSubscribe("testTopic", "mySubscriberId-5", Hedwig::SubscribeRequest::CREATE_OR_ATTACH, testcb1);
    cond1->wait();

    sub.unsubscribe("testTopic", "mySubscriberId-5");
  }

  void testAsyncSubcribeCloseSubscriptionAndThenResubscribe() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);

    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
   
    sub.subscribe("testTopic", "mySubscriberId-6", Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.closeSubscription("testTopic", "mySubscriberId-6");
    sub.subscribe("testTopic", "mySubscriberId-6", Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.unsubscribe("testTopic", "mySubscriberId-6");
  }

  void testUnsubscribeWithoutSubscribe() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    
    CPPUNIT_ASSERT_THROW(sub.unsubscribe("testTopic", "mySubscriberId-7"), Hedwig::NotSubscribedException);
  }

  void testSubscribeTwice() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    
    sub.subscribe("testTopic", "mySubscriberId-8", Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    CPPUNIT_ASSERT_THROW(sub.subscribe("testTopic", "mySubscriberId-8", Hedwig::SubscribeRequest::CREATE_OR_ATTACH), Hedwig::AlreadySubscribedException);
  }
};

CPPUNIT_TEST_SUITE_NAMED_REGISTRATION( SubscribeTestSuite, "Subscribe" );
