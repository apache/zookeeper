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

#include <stdlib.h>

#include "CollectionUtil.h"
using namespace Util;

#include "Vector.h"
using namespace std;

#include "src/zk_hashtable.h"

class Zookeeper_hashtable : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_hashtable);
    CPPUNIT_TEST(testInsertElement1);
    CPPUNIT_TEST(testInsertElement2);
    CPPUNIT_TEST(testInsertElement3);
    CPPUNIT_TEST(testContainsWatcher1);
    CPPUNIT_TEST(testContainsWatcher2);
    CPPUNIT_TEST(testCombineHashtable1);
    CPPUNIT_TEST(testMoveMergeWatchers1);
    CPPUNIT_TEST(testDeliverSessionEvent1);
    CPPUNIT_TEST(testDeliverZnodeEvent1);
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t *, int, int, const char *,void*){}
    zk_hashtable *ht;
    
public:

    void setUp()
    {
        ht=create_zk_hashtable();
    }
    
    void tearDown()
    {
        destroy_zk_hashtable(ht);
    }

    static vector<int> getWatcherCtxAsVector(zk_hashtable* ht,const char* path){
        watcher_object_t* wo=getFirstWatcher(ht,path);
        vector<int> res;
        while(wo!=0){
            res.push_back((int)wo->context);
            wo=wo->next;
        }
        return res;
    }
    
    // insert 2 watchers for different paths
    // verify that hashtable size is 2
    void testInsertElement1()
    {
        CPPUNIT_ASSERT_EQUAL(0,get_element_count(ht));
        int res=insert_watcher_object(ht,"path1",
                create_watcher_object(watcher,(void*)1));
        CPPUNIT_ASSERT_EQUAL(1,res);
        res=insert_watcher_object(ht,"path2",
                create_watcher_object(watcher,(void*)1));
        CPPUNIT_ASSERT_EQUAL(1,res);
        CPPUNIT_ASSERT_EQUAL(2,get_element_count(ht));
        vector<int> expWatchers=CollectionBuilder<vector<int> >().push_back(1);
        CPPUNIT_ASSERT_EQUAL(expWatchers,getWatcherCtxAsVector(ht,"path1"));
        CPPUNIT_ASSERT_EQUAL(expWatchers,getWatcherCtxAsVector(ht,"path2"));
        clean_zk_hashtable(ht);
        CPPUNIT_ASSERT_EQUAL(0,get_element_count(ht));        
    }
    
    // insert 2 different watchers for the same path;
    // verify: hashtable element count is 1, and the watcher count for the path
    // is 2
    void testInsertElement2()
    {
        int res=insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)1));
        CPPUNIT_ASSERT_EQUAL(1,res);
        res=insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)2));
        CPPUNIT_ASSERT_EQUAL(1,res);
        CPPUNIT_ASSERT_EQUAL(1,get_element_count(ht));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path1"));
        vector<int> expWatchers=CollectionBuilder<vector<int> >().
            push_back(2).push_back(1);
        CPPUNIT_ASSERT_EQUAL(expWatchers,getWatcherCtxAsVector(ht,"path1"));
    }

    // insert 2 identical watchers for the same path;
    // verify: hashtable element count is 1, the watcher count for the path is 1
    void testInsertElement3()
    {
        watcher_object_t wobject;
        wobject.watcher=watcher;
        wobject.context=(void*)1;
        
        int res=insert_watcher_object(ht,"path1",clone_watcher_object(&wobject));
        CPPUNIT_ASSERT_EQUAL(1,res);
        watcher_object_t* wo=clone_watcher_object(&wobject);
        res=insert_watcher_object(ht,"path1",wo);
        CPPUNIT_ASSERT_EQUAL(0,res);
        CPPUNIT_ASSERT_EQUAL(1,get_element_count(ht));
        CPPUNIT_ASSERT_EQUAL(1,get_watcher_count(ht,"path1"));
        vector<int> expWatchers=CollectionBuilder<vector<int> >().push_back(1);
        CPPUNIT_ASSERT_EQUAL(expWatchers,getWatcherCtxAsVector(ht,"path1"));
        // must delete the object that wasn't inserted!
        free(wo);
    }

    // verify: the watcher is found in the table
    void testContainsWatcher1()
    {
        watcher_object_t expected;
        expected.watcher=watcher;
        expected.context=(void*)1;
        
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)4));
        insert_watcher_object(ht,"path2",clone_watcher_object(&expected));
        
        CPPUNIT_ASSERT_EQUAL(2,get_element_count(ht));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path1"));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path2"));

        int res=contains_watcher(ht,&expected);
        CPPUNIT_ASSERT(res==1);
    }

    // verify: the watcher is not found
    void testContainsWatcher2()
    {
        watcher_object_t expected;
        expected.watcher=watcher;
        expected.context=(void*)1;
        
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)4));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)5));
        
        CPPUNIT_ASSERT_EQUAL(2,get_element_count(ht));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path1"));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path2"));

        int res=contains_watcher(ht,&expected);
        CPPUNIT_ASSERT(res==0);
    }

    void testCombineHashtable1()
    {
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)4));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)5));
        
        zk_hashtable* ht2=create_zk_hashtable();

        insert_watcher_object(ht2,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht2,"path2",create_watcher_object(watcher,(void*)6));
        insert_watcher_object(ht2,"path3",create_watcher_object(watcher,(void*)2));

        zk_hashtable* res=combine_hashtables(ht,ht2);
        
        CPPUNIT_ASSERT_EQUAL(3,get_element_count(res));
        // path1 --> 2,3
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(res,"path1"));
        vector<int> expWatchers1=CollectionBuilder<vector<int> >().
            push_back(2).push_back(3);
        CPPUNIT_ASSERT_EQUAL(expWatchers1,getWatcherCtxAsVector(res,"path1"));
        // path2 --> 4,5,6
        CPPUNIT_ASSERT_EQUAL(3,get_watcher_count(res,"path2"));
        vector<int> expWatchers2=CollectionBuilder<vector<int> >().
            push_back(6).push_back(4).push_back(5);
        CPPUNIT_ASSERT_EQUAL(expWatchers2,getWatcherCtxAsVector(res,"path2"));
        // path3 --> 2
        CPPUNIT_ASSERT_EQUAL(1,get_watcher_count(res,"path3"));
        vector<int> expWatchers3=CollectionBuilder<vector<int> >().push_back(2);
        CPPUNIT_ASSERT_EQUAL(expWatchers3,getWatcherCtxAsVector(res,"path3"));

        destroy_zk_hashtable(ht2);
        destroy_zk_hashtable(res);
    }
    
    void testMoveMergeWatchers1()
    {
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht,"path1",create_watcher_object(watcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)4));
        insert_watcher_object(ht,"path2",create_watcher_object(watcher,(void*)5));
        
        zk_hashtable* ht2=create_zk_hashtable();

        insert_watcher_object(ht2,"path1",create_watcher_object(watcher,(void*)2));
        insert_watcher_object(ht2,"path1",create_watcher_object(watcher,(void*)6));

        zk_hashtable* res=move_merge_watchers(ht,ht2,"path1");
        
        CPPUNIT_ASSERT_EQUAL(1,get_element_count(res));
        CPPUNIT_ASSERT_EQUAL(3,get_watcher_count(res,"path1"));
        vector<int> expWatchers=CollectionBuilder<vector<int> >().
            push_back(6).push_back(2).push_back(3);
        CPPUNIT_ASSERT_EQUAL(expWatchers,getWatcherCtxAsVector(res,"path1"));

        // make sure the path entry has been deleted from the source hashtables
        CPPUNIT_ASSERT_EQUAL(1,get_element_count(ht));
        CPPUNIT_ASSERT_EQUAL(2,get_watcher_count(ht,"path2"));
        CPPUNIT_ASSERT_EQUAL(0,get_element_count(ht2));
        
        destroy_zk_hashtable(ht2);
        destroy_zk_hashtable(res);
    }

    static void iterWatcher(zhandle_t *zh, int type, int state, 
            const char* path,void* ctx){
        vector<int>* res=reinterpret_cast<vector<int>*>(zh);
        res->push_back((int)ctx);
    }
    
    void testDeliverSessionEvent1(){
        insert_watcher_object(ht,"path1",create_watcher_object(iterWatcher,(void*)2));
        insert_watcher_object(ht,"path2",create_watcher_object(iterWatcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(iterWatcher,(void*)4));
        insert_watcher_object(ht,"path3",create_watcher_object(iterWatcher,(void*)5));
        
        vector<int> res;
        deliver_session_event(ht,(zhandle_t*)&res,10,20);
        vector<int> expWatchers=CollectionBuilder<vector<int> >().
            push_back(4).push_back(3).push_back(5).push_back(2);
        CPPUNIT_ASSERT_EQUAL(expWatchers,res);
    }
    
    void testDeliverZnodeEvent1(){
        insert_watcher_object(ht,"path2",create_watcher_object(iterWatcher,(void*)3));
        insert_watcher_object(ht,"path2",create_watcher_object(iterWatcher,(void*)4));
        
        vector<int> res;
        deliver_znode_event(ht,(zhandle_t*)&res,"path2",10,20);
        vector<int> expWatchers=CollectionBuilder<vector<int> >().
            push_back(4).push_back(3);
        CPPUNIT_ASSERT_EQUAL(expWatchers,res);
        expWatchers.clear();
        res.clear();
        // non-existent path
        deliver_znode_event(ht,(zhandle_t*)&res,"path100",10,20);
        CPPUNIT_ASSERT_EQUAL(expWatchers,res);
        // make sure the path entry has been deleted from the source hashtable
        CPPUNIT_ASSERT_EQUAL(0,get_element_count(ht));
    }
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_hashtable);
