/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

package org.apache.bookkeeper.proto;

import java.net.InetSocketAddress;
import org.jboss.netty.buffer.ChannelBuffer;

/**
 * Declaration of a callback interfaces used in bookkeeper client library but
 * not exposed to the client application.
 */

public class BookkeeperInternalCallbacks {
    /**
     * Callback for calls from BookieClient objects. Such calls are for replies
     * of write operations (operations to add an entry to a ledger).
     * 
     */

    public interface WriteCallback {
        void writeComplete(int rc, long ledgerId, long entryId, InetSocketAddress addr, Object ctx);
    }

    public interface GenericCallback<T> {
        void operationComplete(int rc, T result);
    }
    
    /**
     * Declaration of a callback implementation for calls from BookieClient objects.
     * Such calls are for replies of read operations (operations to read an entry
     * from a ledger).
     * 
     */

    public interface ReadEntryCallback {
        void readEntryComplete(int rc, long ledgerId, long entryId, ChannelBuffer buffer, Object ctx);
    }
}
