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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncClientSocket extends AsyncSocket<Socket> {
    private static final Logger LOG =
            LoggerFactory.getLogger(AsyncClientSocket.class.getName());

    public AsyncClientSocket(final Socket socket,
                             ExecutorService executorService) {
        super(socket, executorService);
    }

    public AsyncClientSocket(final Socket socket) {
        super(socket);
    }

    public FutureTask<Socket> connect(final QuorumServer server) {
        FutureTask<Socket> futureTask = new FutureTask<Socket>(
                new Callable<Socket>() {
                    @Override
                    public Socket call() throws IOException {
                        connectSync(server);
                        return getSocket();
                    }
                });
        submitTask(futureTask);
        return futureTask;
    }

    public FutureTask<Socket> connect(final QuorumServer server,
                                      final int timeout) {
        FutureTask<Socket> futureTask = new FutureTask<>(
                new Callable<Socket>() {
                    @Override
                    public Socket call() throws IOException {
                        connectSync(server, timeout);
                        return getSocket();
                    }
                });
        submitTask(futureTask);
        return futureTask;
    }

    public FutureTask<String> read() {
        FutureTask<String> futureTask = new FutureTask<>(
                new Callable<String>() {
                    @Override
                    public String call() throws IOException {
                        return readSync();
                    }
                });
        submitTask(futureTask);
        return futureTask;
    }

    public FutureTask<Void> write(final String msg) {
        FutureTask<Void> futureTask = new FutureTask<>(
                new Callable<Void>() {
                    @Override
                    public Void call() throws IOException {
                        return writeSync(msg);
                    }
                });
        submitTask(futureTask);
        return futureTask;
    }

    protected String readSync() throws IOException {
        String str = null;
        final Socket s = getSocket();
        try(BufferedReader br = new BufferedReader(
                new InputStreamReader(s.getInputStream()))) {
            while((str = br.readLine()) != null) {
                break;
            }
        } catch (IOException exp) {
            LOG.error("read error, exp: " + exp);
            throw exp;
        }

        return str;
    }

    protected Void writeSync(final String msg) throws IOException {
        PrintWriter pw = new PrintWriter(getSocket().getOutputStream(), true);
        pw.println(msg);
        pw.flush();
        pw.close();
        return null;
    }

    private void connectSync(final QuorumServer server)
            throws IOException {
        setOptions();
        getSocket().connect(server.addr, server.addr.getPort());
    }

    private void connectSync(final QuorumServer server, final int timeout)
            throws IOException {
        setOptions();
        getSocket().connect(server.addr, timeout);
    }

    private void setOptions() throws SocketException {
        getSocket().setSoLinger(true, 0);
        getSocket().setTcpNoDelay(true);
        getSocket().setKeepAlive(true);
    }
}
