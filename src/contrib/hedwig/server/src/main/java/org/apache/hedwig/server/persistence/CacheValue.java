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
package org.apache.hedwig.server.persistence;

import java.util.LinkedList;
import java.util.Queue;

import org.apache.log4j.Logger;

import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.common.UnexpectedError;

/**
 * This class is NOT thread safe. It need not be thread-safe because our
 * read-ahead cache will operate with only 1 thread
 * 
 */
public class CacheValue {

    static Logger logger = Logger.getLogger(ReadAheadCache.class);

    Queue<ScanCallbackWithContext> callbacks = new LinkedList<ScanCallbackWithContext>();
    Message message;
    long timeOfAddition = 0;

    public CacheValue() {
    }

    public boolean isStub() {
        return message == null;
    }

    public long getTimeOfAddition() {
        if (message == null) {
            throw new UnexpectedError("Time of add requested from a stub");
        }
        return timeOfAddition;
    }

    public void setMessageAndInvokeCallbacks(Message message, long currTime) {
        if (this.message != null) {
            // Duplicate read for the same message coming back
            return;
        }

        this.message = message;
        this.timeOfAddition = currTime;
        ScanCallbackWithContext callbackWithCtx;
        if (logger.isDebugEnabled()) {
            logger.debug("Invoking " + callbacks.size() + " callbacks for " + " message added to cache");
        }
        while ((callbackWithCtx = callbacks.poll()) != null) {
            callbackWithCtx.getScanCallback().messageScanned(callbackWithCtx.getCtx(), message);
        }
    }

    public void addCallback(ScanCallback callback, Object ctx) {
        if (!isStub()) {
            // call the callback right away
            callback.messageScanned(ctx, message);
            return;
        }

        callbacks.add(new ScanCallbackWithContext(callback, ctx));
    }

    public Message getMessage() {
        return message;
    }

    public void setErrorAndInvokeCallbacks(Exception exception) {
        ScanCallbackWithContext callbackWithCtx;
        while ((callbackWithCtx = callbacks.poll()) != null) {
            callbackWithCtx.getScanCallback().scanFailed(callbackWithCtx.getCtx(), exception);
        }
    }

}
