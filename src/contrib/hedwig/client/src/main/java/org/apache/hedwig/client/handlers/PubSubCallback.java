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
package org.apache.hedwig.client.handlers;

import org.apache.log4j.Logger;

import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.util.Callback;

/**
 * This class is used when we are doing synchronous type of operations. All
 * underlying client ops in Hedwig are async so this is just a way to make the
 * async calls synchronous.
 * 
 */
public class PubSubCallback implements Callback<Void> {

    private static Logger logger = Logger.getLogger(PubSubCallback.class);

    // Private member variables
    private PubSubData pubSubData;
    // Boolean indicator to see if the sync PubSub call was successful or not.
    private boolean isCallSuccessful;
    // For sync callbacks, we'd like to know what the PubSubException is thrown
    // on failure. This is so we can have a handle to the exception and rethrow
    // it later.
    private PubSubException failureException;

    // Constructor
    public PubSubCallback(PubSubData pubSubData) {
        this.pubSubData = pubSubData;
    }

    public void operationFinished(Object ctx, Void resultOfOperation) {
        if (logger.isDebugEnabled())
            logger.debug("PubSub call succeeded for pubSubData: " + pubSubData);
        // Wake up the main sync PubSub thread that is waiting for us to
        // complete.
        synchronized (pubSubData) {
            isCallSuccessful = true;
            pubSubData.isDone = true;
            pubSubData.notify();
        }
    }

    public void operationFailed(Object ctx, PubSubException exception) {
        if (logger.isDebugEnabled())
            logger.debug("PubSub call failed with exception: " + exception + ", pubSubData: " + pubSubData);
        // Wake up the main sync PubSub thread that is waiting for us to
        // complete.
        synchronized (pubSubData) {
            isCallSuccessful = false;
            failureException = exception;
            pubSubData.isDone = true;
            pubSubData.notify();
        }
    }

    // Public getter to determine if the PubSub callback is successful or not
    // based on the PubSub ack response from the server.
    public boolean getIsCallSuccessful() {
        return isCallSuccessful;
    }

    // Public getter to retrieve what the PubSubException was that occurred when
    // the operation failed.
    public PubSubException getFailureException() {
        return failureException;
    }

}
