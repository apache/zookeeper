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

package org.apache.zookeeper.server.util;

/**
 * An observable component is responsible for decoding and dispatching its events
 * to an observer. Normally, an observable component is also responsible for
 * triggering of its events.
 */
public interface ObservableComponent {
    /**
     * Dispatch an event to the observer.
     * 
     * @param observer the observer to be notified
     * @param args application specific event payload
     */
    public void dispatchEvent(Object observer,Object args);
}
