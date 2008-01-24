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
#include <sys/types.h>
#include <netinet/in.h>
#include <errno.h>

#include "Util.h"
#include "LibCMocks.h"

#ifdef THREADED
#include "PthreadMocks.h"
#else
class MockPthreadsNull;
#endif

using namespace std;

class Zookeeper_init : public CPPUNIT_NS::TestFixture
{
	CPPUNIT_TEST_SUITE(Zookeeper_init);
	CPPUNIT_TEST(testBasic);
    CPPUNIT_TEST(testAddressResolution);
    CPPUNIT_TEST(testMultipleAddressResolution);
    CPPUNIT_TEST(testInvalidAddressString1);
    CPPUNIT_TEST(testInvalidAddressString2);
    CPPUNIT_TEST(testNonexistentHost);
    CPPUNIT_TEST(testOutOfMemory_init);
    CPPUNIT_TEST(testOutOfMemory_getaddrs1);
    CPPUNIT_TEST(testOutOfMemory_getaddrs2);
    CPPUNIT_TEST(testPermuteAddrsList);
	CPPUNIT_TEST_SUITE_END();
    zhandle_t *zh;
    MockPthreadsNull* pthreadMock;   
    static void watcher(zhandle_t *, int , int , const char *){}
public: 
    Zookeeper_init():zh(0),pthreadMock(0){}
    
    void setUp()
    {
    	zoo_set_debug_level((ZooLogLevel)0); // disable logging
        zoo_deterministic_conn_order(0);
#ifdef THREADED
        // disable threading
        pthreadMock=new MockPthreadZKNull;
#endif        
        zh=0;
    }
    
    void tearDown()
    {
        zookeeper_close(zh);
#ifdef THREADED
        delete pthreadMock;
#endif
    }

    void testBasic()
    {
    	const string EXPECTED_HOST("localhost:2121");
    	const int EXPECTED_ADDRS_COUNT =1;
    	const int EXPECTED_RECV_TIMEOUT=10000;
        clientid_t cid;
    	memset(&cid,0xFE,sizeof(cid));
        
    	zh=zookeeper_init(EXPECTED_HOST.c_str(),watcher,EXPECTED_RECV_TIMEOUT,
    	        &cid,(void*)1,0);
        
        CPPUNIT_ASSERT(zh!=0);
    	CPPUNIT_ASSERT(zh->fd == -1);
    	CPPUNIT_ASSERT(zh->hostname!=0);
    	CPPUNIT_ASSERT_EQUAL(EXPECTED_ADDRS_COUNT,zh->addrs_count);
    	CPPUNIT_ASSERT_EQUAL(EXPECTED_HOST,string(zh->hostname));
        CPPUNIT_ASSERT(zh->state == 0);
        CPPUNIT_ASSERT(zh->context == (void*)1);
        CPPUNIT_ASSERT_EQUAL(EXPECTED_RECV_TIMEOUT,zh->recv_timeout);
        CPPUNIT_ASSERT(zh->watcher == watcher);
        CPPUNIT_ASSERT(zh->connect_index==0);
        CPPUNIT_ASSERT(zh->primer_buffer.buffer==zh->primer_storage_buffer);
        CPPUNIT_ASSERT(zh->primer_buffer.curr_offset ==0);
        CPPUNIT_ASSERT(zh->primer_buffer.len == sizeof(zh->primer_storage_buffer));
        CPPUNIT_ASSERT(zh->primer_buffer.next == 0);
        CPPUNIT_ASSERT(zh->last_zxid ==0);
        CPPUNIT_ASSERT(memcmp(&zh->client_id,&cid,sizeof(cid))==0);

#ifdef THREADED
        // thread specific checks
        adaptor_threads* adaptor=(adaptor_threads*)zh->adaptor_priv;
        CPPUNIT_ASSERT(adaptor!=0);
        CPPUNIT_ASSERT(pthreadMock->pthread_createCounter==2);
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(adaptor->io));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(adaptor->completion));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->to_process.lock));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->to_send.lock));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->sent_requests.lock));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->completions_to_process.lock));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->sent_requests.cond));
        CPPUNIT_ASSERT(MockPthreadsNull::isInitialized(&zh->completions_to_process.cond));
#endif
    }
    void testAddressResolution()
    {
        const char EXPECTED_IPS[][4]={{127,0,0,1},{127,0,0,2},{127,0,0,3}};
        const int EXPECTED_ADDRS_COUNT =COUNTOF(EXPECTED_IPS);
        Mock_gethostbyname mock;
        mock.addHostEntry("somehostname").addAddress(EXPECTED_IPS[0]).
            addAddress(EXPECTED_IPS[1]).addAddress(EXPECTED_IPS[2]);

        zoo_deterministic_conn_order(1);
        zh=zookeeper_init("host:2121",0,10000,0,0,0);
        
        CPPUNIT_ASSERT(zh!=0);
        CPPUNIT_ASSERT_EQUAL(EXPECTED_ADDRS_COUNT,zh->addrs_count);
        for(int i=0;i<zh->addrs_count;i++){
            sockaddr_in* addr=(struct sockaddr_in*)&zh->addrs[i];
            CPPUNIT_ASSERT(memcmp(EXPECTED_IPS[i],&addr->sin_addr,sizeof(addr->sin_addr))==0);
            CPPUNIT_ASSERT_EQUAL(2121,(int)ntohs(addr->sin_port));
        }
    }
    void testMultipleAddressResolution()
    {
        const string EXPECTED_HOST("host1:2121,host2:3434");
        const char EXPECTED_IPS[][4]={{127,0,0,1},{127,0,0,2},{127,0,0,3},
                {126,1,1,1},{126,2,2,2}};
        const int EXPECTED_ADDRS_COUNT =COUNTOF(EXPECTED_IPS);
        Mock_gethostbyname mock;
        mock.addHostEntry("somehost1").addAddress(EXPECTED_IPS[0]).
            addAddress(EXPECTED_IPS[1]).addAddress(EXPECTED_IPS[2]);
        mock.addHostEntry("somehost2").addAddress(EXPECTED_IPS[3]).
            addAddress(EXPECTED_IPS[4]);

        zoo_deterministic_conn_order(1);
        zh=zookeeper_init(EXPECTED_HOST.c_str(),0,1000,0,0,0);

        CPPUNIT_ASSERT(zh!=0);
        CPPUNIT_ASSERT_EQUAL(EXPECTED_ADDRS_COUNT,zh->addrs_count);

        for(int i=0;i<zh->addrs_count;i++){
            sockaddr_in* addr=(struct sockaddr_in*)&zh->addrs[i];
            CPPUNIT_ASSERT(memcmp(EXPECTED_IPS[i],&addr->sin_addr,sizeof(addr->sin_addr))==0);
            if(i<3)
                CPPUNIT_ASSERT_EQUAL(2121,(int)ntohs(addr->sin_port));
            else
                CPPUNIT_ASSERT_EQUAL(3434,(int)ntohs(addr->sin_port));
        }
    }
    void testInvalidAddressString1()
    {
        const string INVALID_HOST("host1");
        zh=zookeeper_init(INVALID_HOST.c_str(),0,0,0,0,0);
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(EINVAL,errno);
    }
    void testInvalidAddressString2()
    {
        const string INVALID_HOST("host1:1111+host:123");
        zh=zookeeper_init(INVALID_HOST.c_str(),0,0,0,0,0);
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(EINVAL,errno);
    }
    void testNonexistentHost()
    {
        const string EXPECTED_HOST("host1:1111");
        MockFailed_gethostbyname mock;
        
        zh=zookeeper_init(EXPECTED_HOST.c_str(),0,0,0,0,0);
        
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(EINVAL,errno);
        CPPUNIT_ASSERT_EQUAL(HOST_NOT_FOUND,h_errno);
    }
    void testOutOfMemory_init()
    {
        Mock_calloc mock;
        mock.callsBeforeFailure=0; // fail first calloc in init()
        
        zh=zookeeper_init("ahost:123",watcher,10000,0,0,0);
        
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(ENOMEM,errno);
    }
    void testOutOfMemory_getaddrs1()
    {
        Mock_realloc reallocMock;
        reallocMock.callsBeforeFailure=0; // fail on first call to realloc

        Mock_gethostbyname gethostbynameMock;
        gethostbynameMock.addHostEntry("ahost").addAddress("\1\1\1\1");

        zh=zookeeper_init("ahost:123",0,0,0,0,0);
        
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(ENOMEM,errno);
    }
    void testOutOfMemory_getaddrs2()
    {
        const char ADDR[]="\1\1\1\1";
        Mock_realloc reallocMock;
        reallocMock.callsBeforeFailure=1; // fail on the second call to realloc

        Mock_gethostbyname gethostbynameMock;
        // need >16 IPs to get realloc called the second time
        gethostbynameMock.addHostEntry("ahost").
            addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).
            addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).
            addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).
            addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).addAddress(ADDR).
            addAddress(ADDR);

        zh=zookeeper_init("ahost:123",0,0,0,0,0);
        
        CPPUNIT_ASSERT(zh==0);
        CPPUNIT_ASSERT_EQUAL(ENOMEM,errno);
    }
    void testPermuteAddrsList()
    {
        const char EXPECTED[][5]={"\0\0\0\0","\1\1\1\1","\2\2\2\2","\3\3\3\3"};
        const int EXPECTED_ADDR_COUNT=COUNTOF(EXPECTED);
        
        Mock_gethostbyname gethostbynameMock;
        gethostbynameMock.addHostEntry("ahost").
            addAddress(EXPECTED[0]).addAddress(EXPECTED[1]).
            addAddress(EXPECTED[2]).addAddress(EXPECTED[3]);

        const int RAND_SEQ[]={0,1,2,3,1,3,2,0,-1};
        const int RAND_SIZE=COUNTOF(RAND_SEQ);
        Mock_random randomMock;
        randomMock.randomReturns.assign(RAND_SEQ,RAND_SEQ+RAND_SIZE-1);
        zh=zookeeper_init("ahost:123",0,1000,0,0,0);
        
        CPPUNIT_ASSERT(zh!=0);
        CPPUNIT_ASSERT_EQUAL(EXPECTED_ADDR_COUNT,zh->addrs_count);
        const string EXPECTED_SEQ("3210");
        char ACTUAL_SEQ[EXPECTED_ADDR_COUNT+1]; ACTUAL_SEQ[EXPECTED_ADDR_COUNT]=0;
        for(int i=0;i<zh->addrs_count;i++){
            sockaddr_in* addr=(struct sockaddr_in*)&zh->addrs[i];
            // match the first byte of the EXPECTED and of the actual address
            ACTUAL_SEQ[i]=((char*)&addr->sin_addr)[0]+'0';
        }
        CPPUNIT_ASSERT_EQUAL(EXPECTED_SEQ,string(ACTUAL_SEQ));
    }
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_init);
