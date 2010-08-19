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

/**
 * Encapsulates a request to scan messages on the given topic starting from the
 * given seqId (included). A call-back {@link ScanCallback} is provided. As
 * messages are scanned, the relevant methods of the {@link ScanCallback} are
 * called. Two hints are provided as to when scanning should stop: in terms of
 * number of messages scanned, or in terms of the total size of messages
 * scanned. Scanning stops whenever one of these limits is exceeded. These
 * checks, especially the one about message size, are only approximate. The
 * {@link ScanCallback} used should be prepared to deal with more or less
 * messages scanned. If an error occurs during scanning, the
 * {@link ScanCallback} is notified of the error.
 * 
 */
public class RangeScanRequest {
    ByteString topic;
    long startSeqId;
    int messageLimit;
    long sizeLimit;
    ScanCallback callback;
    Object ctx;

    public RangeScanRequest(ByteString topic, long startSeqId, int messageLimit, long sizeLimit, ScanCallback callback,
            Object ctx) {
        this.topic = topic;
        this.startSeqId = startSeqId;
        this.messageLimit = messageLimit;
        this.sizeLimit = sizeLimit;
        this.callback = callback;
        this.ctx = ctx;
    }

    public ByteString getTopic() {
        return topic;
    }

    public long getStartSeqId() {
        return startSeqId;
    }

    public int getMessageLimit() {
        return messageLimit;
    }

    public long getSizeLimit() {
        return sizeLimit;
    }

    public ScanCallback getCallback() {
        return callback;
    }

    public Object getCtx() {
        return ctx;
    }

}
