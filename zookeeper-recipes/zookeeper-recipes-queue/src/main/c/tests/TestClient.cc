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

#include <pthread.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/select.h>
#include <cppunit/TestAssert.h>


using namespace std;

#include <cstring>
#include <list>

#include <zookeeper.h>
#include <zoo_queue.h>

static void yield(zhandle_t *zh, int i)
{
    sleep(i);
}

typedef struct evt {
    string path;
    int type;
} evt_t;

typedef struct watchCtx {
private:
    list<evt_t> events;
public:
    bool connected;
    zhandle_t *zh;
    
    watchCtx() {
        connected = false;
        zh = 0;
    }
    ~watchCtx() {
        if (zh) {
            zookeeper_close(zh);
            zh = 0;
        }
    }

    evt_t getEvent() {
        evt_t evt;
        evt = events.front();
        events.pop_front();
        return evt;
    }

    int countEvents() {
        int count;
        count = events.size();
        return count;
    }

    void putEvent(evt_t evt) {
        events.push_back(evt);
    }

    bool waitForConnected(zhandle_t *zh) {
        time_t expires = time(0) + 10;
        while(!connected && time(0) < expires) {
            yield(zh, 1);
        }
        return connected;
    }
    bool waitForDisconnected(zhandle_t *zh) {
        time_t expires = time(0) + 15;
        while(connected && time(0) < expires) {
            yield(zh, 1);
        }
        return !connected;
    }
} watchctx_t; 

extern "C" {
    
    const char *thread_test_string="Hello World!";
   
    void *offer_thread_shared_queue(void *queue_handle){
        zkr_queue_t *queue = (zkr_queue_t *) queue_handle;

        int test_string_buffer_length = strlen(thread_test_string) + 1;
        int offer_rc = zkr_queue_offer(queue, thread_test_string, test_string_buffer_length);
        pthread_exit(NULL);
    }
    
    void *take_thread_shared_queue(void *queue_handle){
        zkr_queue_t *queue = (zkr_queue_t *) queue_handle;

        int test_string_buffer_length = strlen(thread_test_string) + 1;
        int receive_buffer_capacity = test_string_buffer_length;
        int receive_buffer_length = receive_buffer_capacity;
        char *receive_buffer = (char *) malloc(sizeof(char) * receive_buffer_capacity);

        int remove_rc = zkr_queue_take(queue, receive_buffer, &receive_buffer_length);
        switch(remove_rc){
        case ZOK:
            pthread_exit(receive_buffer);
        default:
            free(receive_buffer);
            pthread_exit(NULL);
        }
    }
    
    int valid_test_string(void *result){
        char *result_string = (char *) result;
        return !strncmp(result_string, thread_test_string, strlen(thread_test_string));
    }
}

class Zookeeper_queuetest : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_queuetest);
    CPPUNIT_TEST(testInitDestroy);
    CPPUNIT_TEST(testOffer1);
    CPPUNIT_TEST(testOfferRemove1);
    CPPUNIT_TEST(testOfferRemove2);
    CPPUNIT_TEST(testOfferRemove3);
    CPPUNIT_TEST(testOfferRemove4);
    CPPUNIT_TEST(testOfferRemove5);
    CPPUNIT_TEST(testOfferRemove6);
    CPPUNIT_TEST(testOfferTake1);
    CPPUNIT_TEST(testOfferTake2);
    CPPUNIT_TEST(testOfferTake3);
    CPPUNIT_TEST(testOfferTake4);
    CPPUNIT_TEST(testOfferTake5);
    CPPUNIT_TEST(testOfferTake6);
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t *, int type, int state, const char *path,void*v){
        watchctx_t *ctx = (watchctx_t*)v;

        if (state == ZOO_CONNECTED_STATE) {
            ctx->connected = true;
        } else {
            ctx->connected = false;
        }
        if (type != ZOO_SESSION_EVENT) {
            evt_t evt;
            evt.path = path;
            evt.type = type;
            ctx->putEvent(evt);
        }
    }

    static const char hostPorts[];

    const char *getHostPorts() {
        return hostPorts;
    }

    zhandle_t *createClient(watchctx_t *ctx) {
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0,
                                       ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
    
public:

#define ZKSERVER_CMD "./tests/zkServer.sh"

    void setUp()
        {
            char cmd[1024];
            sprintf(cmd, "%s startClean %s", ZKSERVER_CMD, getHostPorts());
            CPPUNIT_ASSERT(system(cmd) == 0);
        }
    

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s start %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        tearDown();
    }

    void tearDown()
        {
            char cmd[1024];
            sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
            CPPUNIT_ASSERT(system(cmd) == 0);
        }

    void initializeQueuesAndHandles(int num_clients, zhandle_t *zoohandles[], 
                                    watchctx_t ctxs[], zkr_queue_t queues[], char *path){
        int i;
        for(i=0; i< num_clients; i++){
            zoohandles[i] = createClient(&ctxs[i]);
            zkr_queue_init(&queues[i], zoohandles[i], path, &ZOO_OPEN_ACL_UNSAFE);
        }
    }

    void cleanUpQueues(int num_clients, zkr_queue_t queues[]){
        int i;
        for(i=0; i < num_clients; i++){
            zkr_queue_destroy(&queues[i]);
        }
    }

    void testInitDestroy(){
        int num_clients = 1;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
        char *path= (char *)"/testInitDestroy";

        int i;
        for(i=0; i< num_clients; i++){
            zoohandles[i] = createClient(&ctxs[i]);
            zkr_queue_init(&queues[i], zoohandles[i], path, &ZOO_OPEN_ACL_UNSAFE);
        }
    
        for(i=0; i< num_clients; i++){
            zkr_queue_destroy(&queues[i]);
        }
    
    }

    void testOffer1(){
        int num_clients = 1;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
        char *path= (char *)"/testOffer1";

        initializeQueuesAndHandles(num_clients, zoohandles, ctxs, queues, path);

        const char *test_string="Hello World!";
        int test_string_length = strlen(test_string);
        int test_string_buffer_length = test_string_length + 1;
        char buffer[test_string_buffer_length];

        int offer_rc = zkr_queue_offer(&queues[0], test_string, test_string_buffer_length);
        CPPUNIT_ASSERT(offer_rc == ZOK);

        int removed_element_buffer_length = test_string_buffer_length;
        int remove_rc = zkr_queue_remove(&queues[0], buffer, &removed_element_buffer_length);
        CPPUNIT_ASSERT(remove_rc == ZOK);
        CPPUNIT_ASSERT(removed_element_buffer_length == test_string_buffer_length);
        CPPUNIT_ASSERT(strncmp(test_string,buffer,test_string_length)==0);

        cleanUpQueues(num_clients,queues);
    }

    void create_n_remove_m(char *path, int n, int m){
        int num_clients = 2;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
    
        initializeQueuesAndHandles(num_clients, zoohandles, ctxs, queues, path);

        int i;
        int max_digits = sizeof(int)*3;
        const char *test_string = "Hello World!";
        int buffer_length = strlen(test_string) + max_digits + 1;
        char correct_buffer[buffer_length];
        char receive_buffer[buffer_length];

        for(i = 0; i < n; i++){
            snprintf(correct_buffer, buffer_length, "%s%d", test_string,i);
            int offer_rc = zkr_queue_offer(&queues[0], correct_buffer, buffer_length);
            CPPUNIT_ASSERT(offer_rc == ZOK);
        }
        printf("Offers\n");
        for(i=0; i<m ;i++){
            snprintf(correct_buffer, buffer_length, "%s%d", test_string,i);
            int receive_buffer_length=buffer_length;
            int remove_rc = zkr_queue_remove(&queues[1], receive_buffer, &receive_buffer_length);
            CPPUNIT_ASSERT(remove_rc == ZOK);
            if(i >=n){
                CPPUNIT_ASSERT(receive_buffer_length == -1);
            }else{
                CPPUNIT_ASSERT(strncmp(correct_buffer,receive_buffer, buffer_length)==0);
            }
        }

        cleanUpQueues(num_clients,queues);
    }

    void testOfferRemove1(){
        create_n_remove_m((char *)"/testOfferRemove1", 0,1);
    }

    void testOfferRemove2(){
        create_n_remove_m((char *)"/testOfferRemove2", 1,1);
    }

    void testOfferRemove3(){
        create_n_remove_m((char *)"/testOfferRemove3", 10,1);
    }
    
    void testOfferRemove4(){
        create_n_remove_m((char *)"/testOfferRemove4", 10,10);
    }

    void testOfferRemove5(){
        create_n_remove_m((char *)"/testOfferRemove5", 10,5);
    }

    void testOfferRemove6(){
        create_n_remove_m((char *)"/testOfferRemove6", 10,11);
    }

    void create_n_take_m(char *path, int n, int m){
        CPPUNIT_ASSERT(m<=n);
        int num_clients = 2;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
    
        initializeQueuesAndHandles(num_clients, zoohandles, ctxs, queues, path);

        int i;
        int max_digits = sizeof(int)*3;
        const char *test_string = "Hello World!";
        int buffer_length = strlen(test_string) + max_digits + 1;
        char correct_buffer[buffer_length];
        char receive_buffer[buffer_length];

        for(i = 0; i < n; i++){
            snprintf(correct_buffer, buffer_length, "%s%d", test_string,i);
            int offer_rc = zkr_queue_offer(&queues[0], correct_buffer, buffer_length);
            CPPUNIT_ASSERT(offer_rc == ZOK);
        }
        printf("Offers\n");
        for(i=0; i<m ;i++){
            snprintf(correct_buffer, buffer_length, "%s%d", test_string,i);
            int receive_buffer_length=buffer_length;
            int remove_rc = zkr_queue_take(&queues[1], receive_buffer, &receive_buffer_length);
            CPPUNIT_ASSERT(remove_rc == ZOK);
            if(i >=n){
                CPPUNIT_ASSERT(receive_buffer_length == -1);
            }else{
                CPPUNIT_ASSERT(strncmp(correct_buffer,receive_buffer, buffer_length)==0);
            }
        }

        cleanUpQueues(num_clients,queues);
    }

    void testOfferTake1(){
        create_n_take_m((char *)"/testOfferTake1", 2,1);
    }

    void testOfferTake2(){
        create_n_take_m((char *)"/testOfferTake2", 1,1);
    }

    void testOfferTake3(){
        create_n_take_m((char *)"/testOfferTake3", 10,1);
    }
    
    void testOfferTake4(){
        create_n_take_m((char *)"/testOfferTake4", 10,10);
    }

    void testOfferTake5(){
        create_n_take_m((char *)"/testOfferTake5", 10,5);
    }

    void testOfferTake6(){
        create_n_take_m((char *)"/testOfferTake6", 12,11);
    }

    void testTakeThreaded(){
        int num_clients = 1;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
        char *path=(char *)"/testTakeThreaded";
    
        initializeQueuesAndHandles(num_clients, zoohandles, ctxs, queues, path);
        pthread_t take_thread;

        pthread_create(&take_thread, NULL, take_thread_shared_queue, (void *) &queues[0]);

        usleep(1000);

        pthread_t offer_thread;
        pthread_create(&offer_thread, NULL, offer_thread_shared_queue, (void *) &queues[0]);
        pthread_join(offer_thread, NULL);

        void *take_thread_result;
        pthread_join(take_thread, &take_thread_result);
        CPPUNIT_ASSERT(take_thread_result != NULL);
        CPPUNIT_ASSERT(valid_test_string(take_thread_result));

        cleanUpQueues(num_clients,queues);
    }

    void testTakeThreaded2(){
        int num_clients = 1;
        watchctx_t ctxs[num_clients];
        zhandle_t *zoohandles[num_clients];
        zkr_queue_t queues[num_clients];
        char *path=(char *)"/testTakeThreaded2";
    
        initializeQueuesAndHandles(num_clients, zoohandles, ctxs, queues, path);

        int take_attempts;
        int num_take_attempts = 2;
        for(take_attempts=0; take_attempts < num_take_attempts; take_attempts++){ 
            pthread_t take_thread;
    
            pthread_create(&take_thread, NULL, take_thread_shared_queue, (void *) &queues[0]);
    
            usleep(1000);
    
            pthread_t offer_thread;
            pthread_create(&offer_thread, NULL, offer_thread_shared_queue, (void *) &queues[0]);
            pthread_join(offer_thread, NULL);
    
            void *take_thread_result;
            pthread_join(take_thread, &take_thread_result);
            CPPUNIT_ASSERT(take_thread_result != NULL);
            CPPUNIT_ASSERT(valid_test_string(take_thread_result));

        }
        cleanUpQueues(num_clients,queues);
    }
};

const char Zookeeper_queuetest::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_queuetest);
