/**
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

package com.yahoo.zookeeper.server.util;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Abstract observer manager -- a liason between an observable component 
 * (event producer) and one or more event listeners (event consumers). It takes 
 * care of the bookeeping chores and of broadcasting events to the observers.
 * <p>
 * Observers register themselves using the add() method.
 */
public abstract class ObserverManager {
    private static ObserverManager instance=null;
    private Map<ObservableComponent,Set<Object>> observableCache=
        Collections.synchronizedMap(new WeakHashMap<ObservableComponent,Set<Object>>());
    
    protected abstract Set<Object> getObserverList(Object key);
    
    protected static void setInstance(ObserverManager newInstance){
        instance=newInstance;
    }
    
    public static ObserverManager getInstance(){
        assert instance!=null;
        return instance;
    }
    /**
     * Add an observer to the list of registered observers. 
     * @param observer to be registered
     */
    public void add(Object observer) {
        Set<Object> ob=getObserverList(observer);
        assert ob!=null;
        synchronized(ob){
            ob.add(observer);
        }
    }

    /**
     * An ObservableComponent will call this method to notify its observers
     * about an event.
     * @param source the ObservableComponent instance
     * @param args application event-specific payload (not used by the ObserverManager) 
     */
    public void notifyObservers(ObservableComponent source, Object args) {
        Set<Object> obs = observableCache.get(source);
        if(obs==null){
            obs=getObserverList(source);
            observableCache.put(source, obs);
        }
        synchronized (obs) {
            for (Object o : obs) {
                try {
                    source.dispatchEvent(o, args);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Unregister the observer.
     * @param observer to be unregistered.
     */
    public void remove(Object observer) {
        Set<Object> ob=getObserverList(observer);
        if(ob!=null){
            synchronized(ob){
                ob.remove(observer);
            }
        }
    }
}
