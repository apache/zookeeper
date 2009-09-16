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

#ifndef UTIL_H_
#define UTIL_H_

#include <map>
#include <vector>
#include <string>

#include "zookeeper_log.h"

// number of elements in array
#define COUNTOF(array) sizeof(array)/sizeof(array[0])

#define DECLARE_WRAPPER(ret,sym,sig) \
    extern "C" ret __real_##sym sig; \
    extern "C" ret __wrap_##sym sig

#define CALL_REAL(sym,params) \
    __real_##sym params

// must include "src/zookeeper_log.h" to be able to use this macro
#define TEST_TRACE(x) \
    log_message(ZOO_LOG_LEVEL_DEBUG,__LINE__,__func__,format_log_message x)

extern const std::string EMPTY_STRING;

// *****************************************************************************
// A bit of wizardry to get to the bare type from a reference or a pointer 
// to the type
template <class T>
struct TypeOp {
    typedef T BareT;
    typedef T ArgT;
};

// partial specialization for reference types
template <class T>
struct TypeOp<T&>{
    typedef T& ArgT;
    typedef typename TypeOp<T>::BareT BareT;
};

// partial specialization for pointers
template <class T>
struct TypeOp<T*>{
    typedef T* ArgT;
    typedef typename TypeOp<T>::BareT BareT;
};

// *****************************************************************************
// Container utilities

template <class K, class V>
void putValue(std::map<K,V>& map,const K& k, const V& v){
    typedef std::map<K,V> Map;
    typename Map::const_iterator it=map.find(k);
    if(it==map.end())
        map.insert(typename Map::value_type(k,v));
    else
        map[k]=v;
}

template <class K, class V>
bool getValue(const std::map<K,V>& map,const K& k,V& v){
    typedef std::map<K,V> Map;
    typename Map::const_iterator it=map.find(k);
    if(it==map.end())
        return false;
    v=it->second;
    return true;
}

// *****************************************************************************
// misc utils

// millisecond sleep
void millisleep(int ms);
FILE *openlogfile(const char* name);
// evaluate given predicate until it returns true or the timeout 
// (in millis) has expired
template<class Predicate>
int ensureCondition(const Predicate& p,int timeout){
    int elapsed=0;
    while(!p() && elapsed<timeout){
        millisleep(2);
        elapsed+=2;
    }
    return elapsed;
};

// *****************************************************************************
// test global configuration data 
class TestConfig{
    typedef std::vector<std::string> CmdLineOptList;
public:
    typedef CmdLineOptList::const_iterator const_iterator;
    TestConfig(){}
    ~TestConfig(){}
    void addConfigFromCmdLine(int argc, char* argv[]){
        if(argc>=2)
            testName_=argv[1];
        for(int i=2; i<argc;++i)
            cmdOpts_.push_back(argv[i]);
    }
    const_iterator getExtraOptBegin() const {return cmdOpts_.begin();}
    const_iterator getExtraOptEnd() const {return cmdOpts_.end();}
    size_t getExtraOptCount() const {
        return cmdOpts_.size();
    }
    const std::string& getTestName() const {
        return testName_=="all"?EMPTY_STRING:testName_;
    }
private:
    CmdLineOptList cmdOpts_;
    std::string testName_;
};

extern TestConfig globalTestConfig;

#endif /*UTIL_H_*/
