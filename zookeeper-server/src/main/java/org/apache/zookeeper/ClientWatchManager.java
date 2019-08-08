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

package org.apache.zookeeper;

import java.util.Set;

/**
 * 客户端侧的Watch管理接口
 */
public interface ClientWatchManager {
    /**
     * Return a set of watchers that should be notified of the event. The 
     * manager must not notify the watcher(s), however it will update it's 
     * internal structure as if the watches had triggered. The intent being 
     * that the callee is now responsible for notifying the watchers of the 
     * event, possibly at some later time.
     * 接收事件返回一组应该通知的观察者。
     * manager不通知观察者，但它会更新它的内部结构，就像watcher被触发一样。
     * 目的是被调用者现在负责通知观察者事件，可能在稍后的某个时间。
     *
     * @param state event state
     * @param type event type
     * @param path event path
     * @return may be empty set but must not be null可能是空集但不能为空
     */
    //ClientWatchManager负责根据Event得到需要通知的watcher，该manager本身并不进行通知
    public Set<Watcher> materialize(Watcher.Event.KeeperState state,
        Watcher.Event.EventType type, String path);
}
