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

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;

/**
 * Encapsulates a request for reading a single message. The message on the given
 * topic <b>at</b> the given seqId is scanned. A call-back {@link ScanCallback}
 * is provided. When the message is scanned, the
 * {@link ScanCallback#messageScanned(Object, Message)} method is called. Since
 * there is only 1 record to be scanned the
 * {@link ScanCallback#operationFinished(Object)} method may not be called since
 * its redundant.
 * {@link ScanCallback#scanFailed(Object, org.apache.hedwig.exceptions.PubSubException)}
 * method is called in case of error.
 * 
 */
public class ScanRequest {
    ByteString topic;
    long startSeqId;
    ScanCallback callback;
    Object ctx;

    public ScanRequest(ByteString topic, long startSeqId, ScanCallback callback, Object ctx) {
        this.topic = topic;
        this.startSeqId = startSeqId;
        this.callback = callback;
        this.ctx = ctx;
    }

    public ByteString getTopic() {
        return topic;
    }

    public long getStartSeqId() {
        return startSeqId;
    }

    public ScanCallback getCallback() {
        return callback;
    }

    public Object getCtx() {
        return ctx;
    }

}
