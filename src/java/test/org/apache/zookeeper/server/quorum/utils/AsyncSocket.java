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
package org.apache.zookeeper.server.quorum.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public abstract class AsyncSocket<T extends Closeable> {
    private static final Logger LOG =
            LoggerFactory.getLogger(AsyncSocket.class.getName());

    private final ExecutorService executorService;
    private final T socket;

    public AsyncSocket(final T socket,
                       final ExecutorService executorService) {
        this.socket = socket;
        this.executorService = executorService;
    }

    public AsyncSocket(final T socket) {
        this(socket, Executors.newSingleThreadExecutor());
    }

    public final T getSocket() {
        return socket;
    }

    public void close() {
        executorService.shutdownNow();
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {}
    }

    protected void submitTask(FutureTask task) {
        executorService.submit(task);
    }


}
