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

import java.util.concurrent.LinkedBlockingQueue;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.Either;

public class StubScanCallback implements ScanCallback{

    public static Message END_MESSAGE = Message.newBuilder().setBody(ByteString.EMPTY).build();
    
    LinkedBlockingQueue<Either<Message, Exception>> queue = new LinkedBlockingQueue<Either<Message,Exception>>();
    
    @Override
    public void messageScanned(Object ctx, Message message) {
       ConcurrencyUtils.put(queue, Either.of(message, (Exception) null));
    }
    
    @Override
    public void scanFailed(Object ctx, Exception exception) {
        ConcurrencyUtils.put(queue, Either.of((Message) null, exception));
    }
    
    @Override
    public void scanFinished(Object ctx, ReasonForFinish reason) {
        ConcurrencyUtils.put(queue, Either.of(END_MESSAGE, (Exception) null));
        
    }
}
