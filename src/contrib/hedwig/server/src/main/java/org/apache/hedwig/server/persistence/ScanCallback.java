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

import org.apache.hedwig.protocol.PubSubProtocol.Message;

public interface ScanCallback {

    enum ReasonForFinish {
        NO_MORE_MESSAGES, SIZE_LIMIT_EXCEEDED, NUM_MESSAGES_LIMIT_EXCEEDED
    };

    /**
     * This method is called when a message is read from the persistence layer
     * as part of a scan. The message just read is handed to this listener which
     * can then take the desired action on it. The return value from the method
     * indicates whether the scan should continue or not.
     * 
     * @param ctx
     *            The context for the callback
     * @param message
     *            The message just scanned from the log
     * @return true if the scan should continue, false otherwise
     */
    public void messageScanned(Object ctx, Message message);

    /**
     * This method is called when the scan finishes
     * 
     * 
     * @param ctx
     * @param reason
     */

    public abstract void scanFinished(Object ctx, ReasonForFinish reason);

    /**
     * This method is called when the operation failed due to some reason. The
     * reason for failure is passed in.
     * 
     * @param ctx
     *            The context for the callback
     * @param exception
     *            The reason for the failure of the scan
     */
    public abstract void scanFailed(Object ctx, Exception exception);

}
