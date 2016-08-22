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

#ifndef __EVENT_H__
#define __EVENT_H__

#include <string>
#include <set>
#include <deque>
#include <algorithm>
#ifdef GCC4
#   include <tr1/memory>
using namespace std::tr1;
#else
#   include <boost/shared_ptr.hpp>
using namespace boost;
#endif

#include "log.h"
#include "blockingqueue.h"
#include "mutex.h"
#include "thread.h"

using namespace std;
using namespace zk;

namespace zkfuse {

//forward declaration of EventSource
template<typename E>
class EventSource;

/**
 * \brief This interface is implemented by an observer
 * \brief of a particular {@link EventSource}.
 */
template<typename E>
class EventListener {
    public:
        
        /**
         * \brief This method is invoked whenever an event 
         * \brief has been received by the event source being observed.
         * 
         * @param source the source the triggered the event
         * @param e      the actual event being triggered
         */
        virtual void eventReceived(const EventSource<E> &source, const E &e) = 0;
};            

/**
 * \brief This class represents a source of events.
 * 
 * <p>
 * Each source can have many observers (listeners) attached to it
 * and in case of an event, this source may propagate the event
 * using {@link #fireEvent} method.
 */
template<typename E>           
class EventSource {
    public:
        
        /**
         * \brief The type corresponding to the list of registered event listeners.
         */
        typedef set<EventListener<E> *> EventListeners;
        
        /**
         * \brief Registers a new event listener.
         * 
         * @param listener the listener to be added to the set of listeners
         */
        void addListener(EventListener<E> *listener) {
            m_listeners.insert( listener );
        }
        
        /**
         * \brief Removes an already registered listener.
         * 
         * @param listener the listener to be removed
         */
        void removeListener(EventListener<E> *listener) {
            m_listeners.erase( listener );
        }
        
        /**
         * \brief Destructor.
         */
        virtual ~EventSource() {}
        
    protected:
        
        /**
         * \brief Fires the given event to all registered listeners.
         * 
         * <p>
         * This method essentially iterates over all listeners
         * and invokes {@link fireEvent(EventListener<E> *listener, const E &event)}
         * for each element. All derived classes are free to
         * override the method to provide better error handling
         * than the default implementation.
         * 
         * @param event the event to be propagated to all listeners
         */
        void fireEvent(const E &event);
        
        /**
         * \brief Sends an event to the given listener.
         * 
         * @param listener the listener to whom pass the event
         * @param event the event to be handled
         */
        virtual void fireEvent(EventListener<E> *listener, const E &event);
        
    private:
        
        /**
         * The set of registered event listeners.
         */
        EventListeners m_listeners;            
    
};

/**
 * \brief The interface of a generic event wrapper.
 */
class AbstractEventWrapper {
    public:
        
        /**
         * \brief Destructor.
         */
        virtual ~AbstractEventWrapper() {}
        
        /**
         * \brief Returns the underlying wrapee's data.
         */
        virtual void *getWrapee() = 0;
};

/**
 * \brief A template based implementation of {@link AbstractEventWrapper}.
 */
template<typename E>
class EventWrapper : public AbstractEventWrapper {
    public:
        EventWrapper(const E &e) : m_e(e) {
        }
        void *getWrapee() {
            return &m_e;
        }
    private:
        E m_e;
};

/**
 * \brief This class represents a generic event.
 */
class GenericEvent {
    public:
        
        /**
         * \brief Constructor.
         */
        GenericEvent() : m_type(0) {}

        /**
         * \brief Constructor.
         * 
         * @param type the type of this event
         * @param eventWarpper the wrapper around event's data
         */
        GenericEvent(int type, AbstractEventWrapper *eventWrapper) : 
            m_type(type), m_eventWrapper(eventWrapper) {
        }
        
        /**
         * \brief Returns the type of this event.
         * 
         * @return type of this event
         */
        int getType() const { return m_type; }
        
        /**
         * \brief Returns the event's data.
         * 
         * @return the event's data
         */
        void *getEvent() const { return m_eventWrapper->getWrapee(); }
        
    private:

        /**
         * The event type.
         */
        int m_type;

        /**
         * The event represented as abstract wrapper.
         */
        shared_ptr<AbstractEventWrapper> m_eventWrapper;
        
};
    
/**
 * \brief This class adapts {@link EventListener} to a generic listener.
 * Essentially this class listens on incoming events and fires them 
 * as {@link GenericEvent}s.
 */
template<typename E, const int type>
class EventListenerAdapter : public virtual EventListener<E>,
                             public virtual EventSource<GenericEvent>
{
    public:
        
        /**
         * \brief Constructor.
         * 
         * @param eventSource the source on which register this listener
         */
        EventListenerAdapter(EventSource<E> &eventSource) {
            eventSource.addListener(this);
        }
        
        void eventReceived(const EventSource<E> &source, const E &e) {
            AbstractEventWrapper *wrapper = new EventWrapper<E>(e);
            GenericEvent event(type, wrapper);
            fireEvent( event );
        }

};        

/**
 * \brief This class provides an adapter between an asynchronous and synchronous 
 * \brief event handling.
 * 
 * <p>
 * This class queues up all received events and exposes them through 
 * {@link #getNextEvent()} method.
 */
template<typename E>                  
class SynchronousEventAdapter : public EventListener<E> {
    public:
        
        void eventReceived(const EventSource<E> &source, const E &e) {
            m_queue.put( e );
        }

        /**
         * \brief Returns the next available event from the underlying queue,
         * \brief possibly blocking, if no data is available.
         * 
         * @return the next available event
         */
        E getNextEvent() {
            return m_queue.take();
        }
        
        /**
         * \brief Returns whether there are any events in the queue or not.
         * 
         * @return true if there is at least one event and 
         *         the next call to {@link #getNextEvent} won't block
         */
        bool hasEvents() const {
            return (m_queue.empty() ? false : true);
        }
        
        /**
         * \brief Destructor.
         */
        virtual ~SynchronousEventAdapter() {}

    private:
        
        /**
         * The blocking queue of all events received so far.
         */
        BlockingQueue<E> m_queue;
        
};

/**
 * This typedef defines the type of a timer Id.
 */
typedef int32_t TimerId;

/**
 * This class represents a timer event parametrized by the user's data type.
 */
template<typename T>
class TimerEvent {
    public:
       
        /**
         * \brief Constructor.
         * 
         * @param id the ID of this event
         * @param alarmTime when this event is to be triggered
         * @param userData the user data associated with this event
         */
        TimerEvent(TimerId id, int64_t alarmTime, const T &userData) :
            m_id(id), m_alarmTime(alarmTime), m_userData(userData) 
        {}     

        /**
         * \brief Constructor.
         */
        TimerEvent() : m_id(-1), m_alarmTime(-1) {}
                           
        /**
         * \brief Returns the ID.
         * 
         * @return the ID of this event
         */
        TimerId getID() const { return m_id; }
        
        /**
         * \brief Returns the alarm time.
         * 
         * @return the alarm time
         */
        int64_t getAlarmTime() const { return m_alarmTime; }
              
        /**
         * \brief Returns the user's data.
         * 
         * @return the user's data
         */
        T const &getUserData() const { return m_userData; }
        
        /**
         * \brief Returns whether the given alarm time is less than this event's 
         * \brief time.
         */
        bool operator<(const int64_t alarmTime) const {
            return m_alarmTime < alarmTime;
        }
        
    private:
        
        /**
         * The ID of ths event.
         */
        TimerId m_id;
        
        /**
         * The time at which this event triggers.
         */
        int64_t m_alarmTime;    
        
        /**
         * The user specific data associated with this event.
         */
        T m_userData;
        
};

template<typename T>
class Timer : public EventSource<TimerEvent<T> > {
    public:
        
        /**
         * \brief Constructor.
         */
        Timer() : m_currentEventID(0), m_terminating(false) {
            m_workerThread.Create( *this, &Timer<T>::sendAlarms );
        }
        
        /**
         * \brief Destructor.
         */
        ~Timer() {
            m_terminating = true;
            m_lock.notify();
            m_workerThread.Join();
        }
        
        /**
         * \brief Schedules the given event <code>timeFromNow</code> milliseconds.
         * 
         * @param timeFromNow time from now, in milliseconds, when the event 
         *                    should be triggered 
         * @param userData the user data associated with the timer event
         * 
         * @return the ID of the newly created timer event
         */
        TimerId scheduleAfter(int64_t timeFromNow, const T &userData) {
            return scheduleAt( getCurrentTimeMillis() + timeFromNow, userData );
        }

        /**
         * \brief Schedules an event at the given time.
         * 
         * @param absTime absolute time, in milliseconds, at which the event 
         *                should be triggered; the time is measured
         *                from Jan 1st, 1970   
         * @param userData the user data associated with the timer event
         * 
         * @return the ID of the newly created timer event
         */
        TimerId scheduleAt(int64_t absTime, const T &userData) {
            m_lock.lock();
            typename QueueType::iterator pos = 
                    lower_bound( m_queue.begin(), m_queue.end(), absTime );
            TimerId id = m_currentEventID++;
            TimerEvent<T> event(id, absTime, userData); 
            m_queue.insert( pos, event );
            m_lock.notify();
            m_lock.unlock();
            return id;
        }
        
        /**
         * \brief Returns the current time since Jan 1, 1970, in milliseconds.
         * 
         * @return the current time in milliseconds
         */
        static int64_t getCurrentTimeMillis() {
            struct timeval now;
            gettimeofday( &now, NULL );
            return now.tv_sec * 1000LL + now.tv_usec / 1000;
        }

        /**
         * \brief Cancels the given timer event.
         * 
         * 
         * @param eventID the ID of the event to be canceled
         * 
         * @return whether the event has been canceled
         */
        bool cancelAlarm(TimerId eventID) {
            bool canceled = false;                      
            m_lock.lock();
            typename QueueType::iterator i;
            for (i = m_queue.begin(); i != m_queue.end(); ++i) {
                if (eventID == i->getID()) {
                    m_queue.erase( i );
                    canceled = true;
                    break;
                }
            }
            m_lock.unlock();
            return canceled;
        }
        
        /**
         * Executes the main loop of the worker thread.
         */
        void sendAlarms() {
            //iterate until terminating
            while (!m_terminating) {
                m_lock.lock();
                //1 step - wait until there is an event in the queue
                if (m_queue.empty()) {
                    //wait up to 100ms to get next event
                    m_lock.wait( 100 );
                }     
                bool fire = false;
                if (!m_queue.empty()) {
                    //retrieve the event from the queue and send it
                    TimerEvent<T> event = m_queue.front();      
                    //check whether we can send it right away
                    int64_t timeToWait = 
                        event.getAlarmTime() - getCurrentTimeMillis();
                    if (timeToWait <= 0) {
                        m_queue.pop_front();
                        //we fire only if it's still in the queue and alarm
                        //time has just elapsed (in case the top event
                        //is canceled)
                        fire = true;    
                    } else {
                        m_lock.wait( timeToWait );
                    }
                    m_lock.unlock();
                    if (fire) {
                        fireEvent( event );
                    }
                } else {
                    m_lock.unlock();
                }
            }    
        }
        
    private:
        
        /**
         * The type of timer events queue.
         */
        typedef deque<TimerEvent<T> > QueueType;
        
        /**
         * The current event ID, auto-incremented each time a new event 
         * is created.
         */
        TimerId m_currentEventID;
        
        /**
         * The queue of timer events sorted by {@link TimerEvent#alarmTime}.
         */
        QueueType m_queue;
        
        /**
         * The lock used to guard {@link #m_queue}.
         */
        Lock m_lock;
        
        /**
         * The thread that triggers alarms.
         */
        CXXThread<Timer<T> > m_workerThread;
        
        /**
         * Whether {@link #m_workerThread}  is terminating.
         */
        volatile bool m_terminating;
        
};

template<typename E>
void EventSource<E>::fireEvent(const E &event) {
    for (typename EventListeners::iterator i = m_listeners.begin(); 
         i != m_listeners.end(); 
         ++i) 
    {
        fireEvent( *i, event );
    }
}

template<typename E>
void EventSource<E>::fireEvent(EventListener<E> *listener, const E &event) {
    listener->eventReceived( *this, event );
}
        
}   /* end of 'namespace zkfuse' */

#endif /* __EVENT_H__ */
