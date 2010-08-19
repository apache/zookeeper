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

using namespace CppUnit;

class PublishTestSuite : public CppUnit::TestFixture {
private:
  HedwigTest::ServerControl* control;
  HedwigTest::TestServerPtr zk;
  HedwigTest::TestServerPtr bk1;
  HedwigTest::TestServerPtr bk2;
  HedwigTest::TestServerPtr bk3;
  HedwigTest::TestServerPtr hw1;
  HedwigTest::TestServerPtr hw2;

  CPPUNIT_TEST_SUITE( PublishTestSuite );
  CPPUNIT_TEST(testSyncPublish);
  CPPUNIT_TEST(testAsyncPublish);
  CPPUNIT_TEST(testMultipleAsyncPublish);
  //  CPPUNIT_TEST(simplePublish);
  //CPPUNIT_TEST(simplePublishAndSubscribe);
  //CPPUNIT_TEST(publishAndSubscribeWithRedirect);
  CPPUNIT_TEST_SUITE_END();

public:
  PublishTestSuite() {

  }

  ~PublishTestSuite() {

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
    hw2->kill();
    hw1->kill();
    
    bk1->kill();
    bk2->kill();
    bk3->kill();
    
    zk->kill();
    delete control;
  }

  void testSyncPublish() {
    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    Hedwig::Publisher& pub = client->getPublisher();
    
    pub.publish("testTopic", "testMessage 1");
    
    delete client;
    delete conf;
  }

  void testAsyncPublish() {
    SimpleWaitCondition* cond = new SimpleWaitCondition();

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    Hedwig::Client* client = new Hedwig::Client(*conf);
    Hedwig::Publisher& pub = client->getPublisher();
    
    Hedwig::OperationCallbackPtr testcb(new TestCallback(cond));
    pub.asyncPublish("testTopic", "async test message", testcb);
    
    cond->wait();
    delete cond;
    delete client;
    delete conf;
  }

  void testMultipleAsyncPublish() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    SimpleWaitCondition* cond2 = new SimpleWaitCondition();
    SimpleWaitCondition* cond3 = new SimpleWaitCondition();

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    Hedwig::Client* client = new Hedwig::Client(*conf);
    Hedwig::Publisher& pub = client->getPublisher();
   
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));
    Hedwig::OperationCallbackPtr testcb2(new TestCallback(cond2));
    Hedwig::OperationCallbackPtr testcb3(new TestCallback(cond3));

    pub.asyncPublish("testTopic", "async test message #1", testcb1);
    pub.asyncPublish("testTopic", "async test message #2", testcb2);
    pub.asyncPublish("testTopic", "async test message #3", testcb3);
    
    cond3->wait();
    cond2->wait();
    cond1->wait();

    delete cond3; delete cond2; delete cond1;
    delete client;
    delete conf;
  }
  /*  void simplePublish() {
    LOG.debugStream() << ">>> simplePublish";
    SimpleWaitCondition* cond = new SimpleWaitCondition();

    Hedwig::Configuration* conf = new Configuration1();
    Hedwig::Client* client = new Hedwig::Client(*conf);
    Hedwig::Publisher& pub = client->getPublisher();
    
    Hedwig::OperationCallbackPtr testcb(new TestCallback(cond));
    pub.asyncPublish("foobar", "barfoo", testcb);
    
    LOG.debugStream() << "wait for response";
    cond->wait();
    delete cond;
    LOG.debugStream() << "got response";
    

    delete client;
    delete conf;
    LOG.debugStream() << "<<< simplePublish";
  }

  class MyMessageHandler : public Hedwig::MessageHandlerCallback {
  public:
    MyMessageHandler(SimpleWaitCondition* cond) : cond(cond) {}

    void consume(const std::string& topic, const std::string& subscriberId, const Hedwig::Message& msg, Hedwig::OperationCallbackPtr& callback) {
      LOG.debugStream() << "Topic: " << topic << "  subscriberId: " << subscriberId;
      LOG.debugStream() << " Message: " << msg.body();
      
      callback->operationComplete();
      cond->setTrue();
      cond->signal();
    }
  private:
    SimpleWaitCondition* cond;
    };*/
  /*
  void simplePublishAndSubscribe() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    SimpleWaitCondition* cond2 = new SimpleWaitCondition();
    SimpleWaitCondition* cond3 = new SimpleWaitCondition();

    Hedwig::Configuration* conf = new Configuration1();
    Hedwig::Client* client = new Hedwig::Client(*conf);
    Hedwig::Publisher& pub = client->getPublisher();
    Hedwig::Subscriber& sub = client->getSubscriber();
    
    std::string topic("foobar");
    std::string sid("mysubscriber");
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));
    sub.asyncSubscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH, testcb1);
    Hedwig::MessageHandlerCallbackPtr messagecb(new MyMessageHandler(cond2));
    sub.startDelivery(topic, sid, messagecb);
    cond1->wait();
    
    Hedwig::OperationCallbackPtr testcb2(new TestCallback(cond3));
    pub.asyncPublish("foobar", "barfoo", testcb2);
    cond3->wait();
    cond2->wait();

    delete cond1;
    delete cond3;
    delete cond2;

    delete client;
    delete conf;
  }

  void publishAndSubscribeWithRedirect() {
    SimpleWaitCondition* cond1 = new SimpleWaitCondition();
    SimpleWaitCondition* cond2 = new SimpleWaitCondition();
    SimpleWaitCondition* cond3 = new SimpleWaitCondition();
    SimpleWaitCondition* cond4 = new SimpleWaitCondition();

    Hedwig::Configuration* publishconf = new Configuration1();
    Hedwig::Configuration* subscribeconf = new Configuration2();

    Hedwig::Client* publishclient = new Hedwig::Client(*publishconf);
    Hedwig::Publisher& pub = publishclient->getPublisher();

    Hedwig::Client* subscribeclient = new Hedwig::Client(*subscribeconf);
    Hedwig::Subscriber& sub = subscribeclient->getSubscriber();
    
    LOG.debugStream() << "publishing";
    Hedwig::OperationCallbackPtr testcb2(new TestCallback(cond3));
    pub.asyncPublish("foobar", "barfoo", testcb2);
    cond3->wait();
    
    LOG.debugStream() << "Subscribing";
    std::string topic("foobar");
    std::string sid("mysubscriber");
    Hedwig::OperationCallbackPtr testcb1(new TestCallback(cond1));
    sub.asyncSubscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH, testcb1);
    LOG.debugStream() << "Starting delivery";
    Hedwig::MessageHandlerCallbackPtr messagecb(new MyMessageHandler(cond2));
    sub.startDelivery(topic, sid, messagecb);

    LOG.debugStream() << "Subscribe wait";
    cond1->wait();

    Hedwig::OperationCallbackPtr testcb3(new TestCallback(cond4));
    pub.asyncPublish("foobar", "barfoo", testcb3);
    cond4->wait();


    LOG.debugStream() << "Delivery wait";

    cond2->wait();

    sub.stopDelivery(topic, sid);

    delete cond1;
    delete cond3;
    delete cond2;
    delete cond4;

    delete subscribeclient;
    delete publishclient;
    delete publishconf;
    delete subscribeconf;
    }*/
};

CPPUNIT_TEST_SUITE_NAMED_REGISTRATION( PublishTestSuite, "Publish");
