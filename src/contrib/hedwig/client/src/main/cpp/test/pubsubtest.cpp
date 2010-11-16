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
#include <boost/thread/mutex.hpp>

#include "../lib/clientimpl.h"
#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <stdexcept>
#include <pthread.h>

#include <log4cxx/logger.h>

#include "servercontrol.h"
#include "util.h"

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

class PubSubTestSuite : public CppUnit::TestFixture {
private:
  HedwigTest::ServerControl* control;
  HedwigTest::TestServerPtr zk;
  HedwigTest::TestServerPtr bk1;
  HedwigTest::TestServerPtr bk2;
  HedwigTest::TestServerPtr bk3;
  HedwigTest::TestServerPtr hw1;

			       
  CPPUNIT_TEST_SUITE( PubSubTestSuite );
  CPPUNIT_TEST(testPubSubContinuousOverClose);
  //  CPPUNIT_TEST(testPubSubContinuousOverServerDown);
  CPPUNIT_TEST(testMultiTopic);
  CPPUNIT_TEST(testBigMessage);
  CPPUNIT_TEST(testMultiTopicMultiSubscriber);
  CPPUNIT_TEST_SUITE_END();

public:
  PubSubTestSuite() : control(NULL) {
    
  }

  ~PubSubTestSuite() {
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
  }
  
  void tearDown() 
  {
    try {
      if (hw1.get()) {
	hw1->kill();
      }
      
      if (bk1.get()) {
	bk1->kill();
      }
      if (bk2.get()) {
	bk2->kill();
      }
      if (bk3.get()) {
	bk3->kill();
      }
      
      if (zk.get()) {
	zk->kill();
      }
    } catch (std::exception& e) {
      // don't allow an exception to break everything, we're going deleting the control no matter what
    }
    if (control) {
      delete control;
    }
  }

  class MyMessageHandlerCallback : public Hedwig::MessageHandlerCallback {
  public:
    MyMessageHandlerCallback(const std::string& topic, const std::string& subscriberId) : messagesReceived(0), topic(topic), subscriberId(subscriberId) {
      
    }

    virtual void consume(const std::string& topic, const std::string& subscriberId, const Hedwig::Message& msg, Hedwig::OperationCallbackPtr& callback) {
      if (topic == this->topic && subscriberId == this->subscriberId) {
	boost::lock_guard<boost::mutex> lock(mutex);
      
	messagesReceived++;
	lastMessage = msg.body();
	callback->operationComplete();
      }
    }
    
    std::string getLastMessage() {
      boost::lock_guard<boost::mutex> lock(mutex);
      std::string s = lastMessage;
      return s;
    }

    int numMessagesReceived() {
      boost::lock_guard<boost::mutex> lock(mutex);
      int i = messagesReceived;
      return i;
    }    
    
  protected:
    boost::mutex mutex;
    int messagesReceived;
    std::string lastMessage;
    std::string topic;
    std::string subscriberId;
  };
 
  void testPubSubContinuousOverClose() {
    std::string topic = "pubSubTopic";
    std::string sid = "MySubscriberid-1";

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    Hedwig::Publisher& pub = client->getPublisher();

    sub.subscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    MyMessageHandlerCallback* cb = new MyMessageHandlerCallback(topic, sid);
    Hedwig::MessageHandlerCallbackPtr handler(cb);

    sub.startDelivery(topic, sid, handler);
    pub.publish(topic, "Test Message 1");
    bool pass = false;
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cb->numMessagesReceived() > 0) {
	if (cb->getLastMessage() == "Test Message 1") {
	  pass = true;
	  break;
	}
      }
    }
    CPPUNIT_ASSERT(pass);
    sub.closeSubscription(topic, sid);

    pub.publish(topic, "Test Message 2");
    
    sub.subscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.startDelivery(topic, sid, handler);
    pass = false;
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cb->numMessagesReceived() > 0) {
	if (cb->getLastMessage() == "Test Message 2") {
	  pass = true;
	  break;
	}
      }
    }
    CPPUNIT_ASSERT(pass);
  }


  /*  void testPubSubContinuousOverServerDown() {
    std::string topic = "pubSubTopic";
    std::string sid = "MySubscriberid-1";

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    Hedwig::Publisher& pub = client->getPublisher();

    sub.subscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    MyMessageHandlerCallback* cb = new MyMessageHandlerCallback(topic, sid);
    Hedwig::MessageHandlerCallbackPtr handler(cb);

    sub.startDelivery(topic, sid, handler);
    pub.publish(topic, "Test Message 1");
    bool pass = false;
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cb->numMessagesReceived() > 0) {
	if (cb->getLastMessage() == "Test Message 1") {
	  pass = true;
	  break;
	}
      }
    }
    CPPUNIT_ASSERT(pass);
    sub.closeSubscription(topic, sid);

    pub.publish(topic, "Test Message 2");
    
    sub.subscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.startDelivery(topic, sid, handler);
    pass = false;
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cb->numMessagesReceived() > 0) {
	if (cb->getLastMessage() == "Test Message 2") {
	  pass = true;
	  break;
	}
      }
    }
    CPPUNIT_ASSERT(pass);
    }*/

  void testMultiTopic() {
    std::string topicA = "pubSubTopicA";
    std::string topicB = "pubSubTopicB";
    std::string sid = "MySubscriberid-3";

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    Hedwig::Publisher& pub = client->getPublisher();

    sub.subscribe(topicA, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.subscribe(topicB, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
   
    MyMessageHandlerCallback* cbA = new MyMessageHandlerCallback(topicA, sid);
    Hedwig::MessageHandlerCallbackPtr handlerA(cbA);
    sub.startDelivery(topicA, sid, handlerA);

    MyMessageHandlerCallback* cbB = new MyMessageHandlerCallback(topicB, sid);
    Hedwig::MessageHandlerCallbackPtr handlerB(cbB);
    sub.startDelivery(topicB, sid, handlerB);

    pub.publish(topicA, "Test Message A");
    pub.publish(topicB, "Test Message B");
    int passA = false, passB = false;
    
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cbA->numMessagesReceived() > 0) {
	if (cbA->getLastMessage() == "Test Message A") {
	  passA = true;
	}
      }
      if (cbB->numMessagesReceived() > 0) {
	if (cbB->getLastMessage() == "Test Message B") {
	  passB = true;
	}
      }
      if (passA && passB) {
	break;
      }
    }
    CPPUNIT_ASSERT(passA && passB);
  }

  void testMultiTopicMultiSubscriber() {
    std::string topicA = "pubSubTopicA";
    std::string topicB = "pubSubTopicB";
    std::string sidA = "MySubscriberid-4";
    std::string sidB = "MySubscriberid-5";

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    Hedwig::Publisher& pub = client->getPublisher();

    sub.subscribe(topicA, sidA, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    sub.subscribe(topicB, sidB, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
   
    MyMessageHandlerCallback* cbA = new MyMessageHandlerCallback(topicA, sidA);
    Hedwig::MessageHandlerCallbackPtr handlerA(cbA);
    sub.startDelivery(topicA, sidA, handlerA);

    MyMessageHandlerCallback* cbB = new MyMessageHandlerCallback(topicB, sidB);
    Hedwig::MessageHandlerCallbackPtr handlerB(cbB);
    sub.startDelivery(topicB, sidB, handlerB);

    pub.publish(topicA, "Test Message A");
    pub.publish(topicB, "Test Message B");
    int passA = false, passB = false;
    
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cbA->numMessagesReceived() > 0) {
	if (cbA->getLastMessage() == "Test Message A") {
	  passA = true;
	}
      }
      if (cbB->numMessagesReceived() > 0) {
	if (cbB->getLastMessage() == "Test Message B") {
	  passB = true;
	}
      }
      if (passA && passB) {
	break;
      }
    }
    CPPUNIT_ASSERT(passA && passB);
  }

  static const int BIG_MESSAGE_SIZE = 16436*2; // MTU to lo0 is 16436 by default on linux

  void testBigMessage() {
    std::string topic = "pubSubTopic";
    std::string sid = "MySubscriberid-6";

    Hedwig::Configuration* conf = new TestServerConfiguration(hw1);
    std::auto_ptr<Hedwig::Configuration> confptr(conf);
    
    Hedwig::Client* client = new Hedwig::Client(*conf);
    std::auto_ptr<Hedwig::Client> clientptr(client);

    Hedwig::Subscriber& sub = client->getSubscriber();
    Hedwig::Publisher& pub = client->getPublisher();

    sub.subscribe(topic, sid, Hedwig::SubscribeRequest::CREATE_OR_ATTACH);
    MyMessageHandlerCallback* cb = new MyMessageHandlerCallback(topic, sid);
    Hedwig::MessageHandlerCallbackPtr handler(cb);

    sub.startDelivery(topic, sid, handler);

    char buf[BIG_MESSAGE_SIZE];
    std::string bigmessage(buf, BIG_MESSAGE_SIZE);
    pub.publish(topic, bigmessage);
    pub.publish(topic, "Test Message 1");
    bool pass = false;
    for (int i = 0; i < 10; i++) {
      sleep(3);
      if (cb->numMessagesReceived() > 0) {
	if (cb->getLastMessage() == "Test Message 1") {
	  pass = true;
	  break;
	}
      }
    }
    CPPUNIT_ASSERT(pass);
  }
};

CPPUNIT_TEST_SUITE_NAMED_REGISTRATION( PubSubTestSuite, "PubSub" );
