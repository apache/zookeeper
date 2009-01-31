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

package org.apache.zookeeper.test.system;

import org.apache.zookeeper.KeeperException;

/**
 * This interface is implemented by a class that can be run in an
 * instance container.
 *
 */
public interface Instance {
    /**
     * This object is used to report back changes in status.
     */
    interface Reporter {
        void report(String report) throws KeeperException, InterruptedException;
    }
    /**
     * This will be the first method invoked by the InstanceContainer after
     * an instance of this interface has been constructed. It will only be
     * invoked once.
     * 
     * @param r a handle to use to report on status changes.
     */
    void setReporter(Reporter r);
    /**
     * This will be the second method invoked by the InstanceContainer. It 
     * may be invoked again if the configuration changes.
     * 
     * @param params parameters that were passed to the InstanceManager when
     *        this instance was scheduled.
     */
    void configure(String params);
    /**
     * Starts this instance.
     */
    void start();
    /**
     * Stops this instance.
     */
    void stop();
}
