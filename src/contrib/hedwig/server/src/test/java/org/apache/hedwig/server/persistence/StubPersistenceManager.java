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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protoextensions.MessageIdUtils;
import org.apache.hedwig.server.persistence.ScanCallback.ReasonForFinish;

public class StubPersistenceManager implements PersistenceManagerWithRangeScan {
    Map<ByteString, List<Message>> messages = new HashMap<ByteString, List<Message>>();
    boolean failure = false;
    ServiceDownException exception = new ServiceDownException("Asked to fail");

    public void deliveredUntil(ByteString topic, Long seqId) {
        // noop
    }

    public void consumedUntil(ByteString topic, Long seqId) {
        // noop
    }

    protected static class ArrayListMessageFactory implements Factory<List<Message>> {
        static ArrayListMessageFactory instance = new ArrayListMessageFactory();

        public List<Message> newInstance() {
            return new ArrayList<Message>();
        }
    }

    public MessageSeqId getCurrentSeqIdForTopic(ByteString topic) {
        long seqId = MapMethods.getAfterInsertingIfAbsent(messages, topic, ArrayListMessageFactory.instance).size();
        return MessageSeqId.newBuilder().setLocalComponent(seqId).build();
    }

    public long getSeqIdAfterSkipping(ByteString topic, long seqId, int skipAmount) {
        return seqId + skipAmount;
    }

    public void persistMessage(PersistRequest request) {
        if (failure) {
            request.callback.operationFailed(request.getCtx(), exception);
            return;
        }

        MapMethods.addToMultiMap(messages, request.getTopic(), request.getMessage(), ArrayListMessageFactory.instance);
        request.callback.operationFinished(request.getCtx(), (long) messages.get(request.getTopic()).size());
    }

    public void scanSingleMessage(ScanRequest request) {
        if (failure) {
            request.getCallback().scanFailed(request.getCtx(), exception);
            return;
        }

        request.getCallback().messageScanned(request.getCtx(),
                messages.get(request.getTopic()).get((int) request.getStartSeqId()));

    }

    public void scanMessages(RangeScanRequest request) {
        if (failure) {
            request.getCallback().scanFailed(request.getCtx(), exception);
            return;
        }

        long totalSize = 0;
        long startSeqId = request.getStartSeqId();
        for (int i = 0; i < request.getMessageLimit(); i++) {
            List<Message> messageList = MapMethods.getAfterInsertingIfAbsent(messages, request.getTopic(),
                    ArrayListMessageFactory.instance);
            if (startSeqId + i > messageList.size()) {
                request.getCallback().scanFinished(request.getCtx(), ReasonForFinish.NO_MORE_MESSAGES);
                return;
            }
            Message msg = messageList.get((int) startSeqId + i - 1);
            Message toDeliver = MessageIdUtils.mergeLocalSeqId(msg, startSeqId + i);
            request.getCallback().messageScanned(request.getCtx(), toDeliver);

            totalSize += toDeliver.getBody().size();

            if (totalSize > request.getSizeLimit()) {
                request.getCallback().scanFinished(request.getCtx(), ReasonForFinish.SIZE_LIMIT_EXCEEDED);
                return;
            }
        }
        request.getCallback().scanFinished(request.getCtx(), ReasonForFinish.NUM_MESSAGES_LIMIT_EXCEEDED);

    }
}
