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
package org.apache.hedwig;

import java.util.ArrayList;
import java.util.List;

import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.persistence.ScanCallback;

public class StubScanCallback implements ScanCallback {
    List<Message> messages = new ArrayList<Message>();
    boolean success = false, failed = false;

    public void messageScanned(Object ctx, Message message) {
        messages.add(message);
        success = true;
    }

    public void scanFailed(Object ctx, Exception exception) {
        failed = true;
        success = false;
    }

    public void scanFinished(Object ctx, ReasonForFinish reason) {
        success = true;
        failed = false;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailed() {
        return failed;
    }

}
