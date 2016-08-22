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
 
#ifndef __BLOCKINGQUEUE_H__
#define __BLOCKINGQUEUE_H__
 
#include <deque>

#include "mutex.h"
 
using namespace std;
USING_ZKFUSE_NAMESPACE

namespace zk {
 
/**
 * \brief An unbounded blocking queue of elements of type E.
 * 
 * <p>
 * This class is thread safe.
 */
template <class E>
class BlockingQueue {
    public:
        
        /**
         * \brief Adds the specified element to this queue, waiting if necessary 
         * \brief for space to become available.
         * 
         * @param e the element to be added
         */
        void put(E e);
        
        /**
         * \brief Retrieves and removes the head of this queue, waiting if 
         * \brief no elements are present in this queue.
         * 
         * @param timeout how long to wait until an element becomes availabe, 
         *                in milliseconds; if <code>0</code> then wait forever
         * @param timedOut if not NULL then set to true whether this function timed out
         * @return the element from the queue
         */
        E take(int32_t timeout = 0, bool *timedOut = NULL);
        
        /**
         * Returns the current size of this blocking queue.
         * 
         * @return the number of elements in this queue
         */
        int size() const;
        
        /**
         * \brief Returns whether this queue is empty or not.
         * 
         * @return true if this queue has no elements; false otherwise
         */
        bool empty() const;
        
    private:
        
        /**
         * The queue of elements. Deque is used to provide O(1) time 
         * for head elements removal.
         */
        deque<E> m_queue;
        
        /**
         * The mutex used for queue synchronization.
         */
        mutable zkfuse::Mutex m_mutex;
        
        /**
         * The conditionial variable associated with the mutex above.
         */
        mutable Cond m_cond;
        
};

template<class E>
int BlockingQueue<E>::size() const {
    int size;
    m_mutex.Acquire();
    size = m_queue.size();
    m_mutex.Release();
    return size;
}

template<class E>
bool BlockingQueue<E>::empty() const {
    bool isEmpty;
    m_mutex.Acquire();
    isEmpty = m_queue.empty();
    m_mutex.Release();
    return isEmpty;
}

template<class E> 
void BlockingQueue<E>::put(E e) {
    m_mutex.Acquire();
    m_queue.push_back( e );
    m_cond.Signal();
    m_mutex.Release();
}

template<class E> 
    E BlockingQueue<E>::take(int32_t timeout, bool *timedOut) {
    m_mutex.Acquire();
    bool hasResult = true;
    while (m_queue.empty()) {
        if (timeout <= 0) {
            m_cond.Wait( m_mutex );
        } else {
            if (!m_cond.Wait( m_mutex, timeout )) {
                hasResult = false;
                break;
            }
        }
    }
    if (hasResult) {
        E e = m_queue.front();
        m_queue.pop_front();            
        m_mutex.Release();
        if (timedOut) {
            *timedOut = false;
        }
        return e;
    } else {
        m_mutex.Release();
        if (timedOut) {
            *timedOut = true;
        }
        return E();
    }
}

}

#endif  /* __BLOCKINGQUEUE_H__ */

