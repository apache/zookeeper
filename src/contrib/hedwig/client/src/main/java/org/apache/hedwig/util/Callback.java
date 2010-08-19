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
package org.apache.hedwig.util;

import org.apache.hedwig.exceptions.PubSubException;

/**
 * This class is used for callbacks for asynchronous operations
 * 
 */
public interface Callback<T> {

    /**
     * This method is called when the asynchronous operation finishes
     * 
     * @param ctx
     * @param resultOfOperation
     */
    public abstract void operationFinished(Object ctx, T resultOfOperation);

    /**
     * This method is called when the operation failed due to some reason. The
     * reason for failure is passed in.
     * 
     * @param ctx
     *            The context for the callback
     * @param exception
     *            The reason for the failure of the scan
     */
    public abstract void operationFailed(Object ctx, PubSubException exception);

}