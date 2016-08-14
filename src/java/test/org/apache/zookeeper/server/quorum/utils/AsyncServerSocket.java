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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

public class AsyncServerSocket extends AsyncSocket<ServerSocket> {
    private static final Logger LOG =
            LoggerFactory.getLogger(AsyncServerSocket.class.getName());

    public AsyncServerSocket(final ServerSocket socket,
                             ExecutorService executorService) {
        super(socket, executorService);
    }

    public AsyncServerSocket(final ServerSocket socket) {
        super(socket);
    }

    public FutureTask<Socket> accept() {
        FutureTask<Socket> futureTask = new FutureTask<>(
                new Callable<Socket>() {
                    @Override
                    public Socket call() throws IOException {
                        return acceptSync();
                    }
                });
        submitTask(futureTask);
        return futureTask;
    }

    private Socket acceptSync() throws IOException {
        Socket s = getSocket().accept();
        setOptions(s);
        return s;
    }

    private void setOptions(Socket s) throws SocketException {
        s.setSoLinger(true, 0);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
    }
}
