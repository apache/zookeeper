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
import org.apache.hedwig.exceptions.PubSubException.ServerNotResponsibleForTopicException;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;

/**
 * An implementation of this interface will persist messages in order and assign
 * a seqId to each persisted message. SeqId need not be a single number in
 * general. SeqId is opaque to all layers above {@link PersistenceManager}. Only
 * the {@link PersistenceManager} needs to understand the format of the seqId
 * and maintain it in such a way that there is a total order on the seqIds of a
 * topic.
 * 
 */
public interface PersistenceManager {

    /**
     * Executes the given persist request asynchronously. When done, the
     * callback specified in the request object is called with the result of the
     * operation set to the {@link LocalMessageSeqId} assigned to the persisted
     * message.
     */
    public void persistMessage(PersistRequest request);

    /**
     * Get the seqId of the last message that has been persisted to the given
     * topic. The returned seqId will be set as the consume position of any
     * brand new subscription on this topic.
     * 
     * Note that the return value may quickly become invalid because a
     * {@link #persistMessage(String, PublishedMessage)} call from another
     * thread succeeds. For us, the typical use case is choosing the consume
     * position of a new subscriber. Since the subscriber need not receive all
     * messages that are published while the subscribe call is in progress, such
     * loose semantics from this method is acceptable.
     * 
     * @param topic
     * @return the seqId of the last persisted message.
     * @throws ServerNotResponsibleForTopicException
     */
    public MessageSeqId getCurrentSeqIdForTopic(ByteString topic) throws ServerNotResponsibleForTopicException;

    /**
     * Executes the given scan request
     * 
     */
    public void scanSingleMessage(ScanRequest request);

    /**
     * Gets the next seq-id. This method should never block.
     */
    public long getSeqIdAfterSkipping(ByteString topic, long seqId, int skipAmount);

    /**
     * Hint that the messages until the given seqId have been delivered and wont
     * be needed unless there is a failure of some kind
     */
    public void deliveredUntil(ByteString topic, Long seqId);

    /**
     * Hint that the messages until the given seqId have been consumed by all
     * subscribers to the topic and no longer need to be stored. The
     * implementation classes can decide how and if they want to garbage collect
     * and delete these older topic messages that are no longer needed.
     * 
     * @param topic
     *            Topic
     * @param seqId
     *            Message local sequence ID
     */
    public void consumedUntil(ByteString topic, Long seqId);

}
