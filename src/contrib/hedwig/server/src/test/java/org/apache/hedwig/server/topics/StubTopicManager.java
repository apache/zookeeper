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
package org.apache.hedwig.server.topics;

import java.net.UnknownHostException;
import java.util.concurrent.Executors;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.HedwigSocketAddress;

public class StubTopicManager extends TrivialOwnAllTopicManager {

    boolean shouldOwnEveryNewTopic = false;
    boolean shouldError = false;

    public void setShouldOwnEveryNewTopic(boolean shouldOwnEveryNewTopic) {
        this.shouldOwnEveryNewTopic = shouldOwnEveryNewTopic;
    }

    public void setShouldError(boolean shouldError) {
        this.shouldError = shouldError;
    }

    public StubTopicManager(ServerConfiguration conf) throws UnknownHostException {
        super(conf, Executors.newSingleThreadScheduledExecutor());
    }

    @Override
    protected void realGetOwner(ByteString topic, boolean shouldClaim, 
            Callback<HedwigSocketAddress> cb, Object ctx) {

        if (shouldError) {
            cb.operationFailed(ctx, new PubSubException.ServiceDownException("Asked to fail"));
            return;
        }
        if (topics.contains(topic) // already own it
                || shouldOwnEveryNewTopic) {
            super.realGetOwner(topic, shouldClaim, cb, ctx);
            return;
        } else {
            // return some other address
            cb.operationFinished(ctx, new HedwigSocketAddress("124.31.0.1:80"));
        }
    }

}
