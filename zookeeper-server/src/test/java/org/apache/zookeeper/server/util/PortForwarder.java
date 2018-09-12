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

package org.apache.zookeeper.server.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortForwarder extends Thread {
    private static final Logger LOG = LoggerFactory
            .getLogger(PortForwarder.class);

    private static class PortForwardWorker implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        private final Socket toClose;
        private final Socket toClose2;
        private boolean isFinished = false;

        PortForwardWorker(Socket toClose, Socket toClose2, InputStream in,
                OutputStream out) {
            this.toClose = toClose;
            this.toClose2 = toClose2;
            this.in = in;
            this.out = out;
            // LOG.info("starting forward for "+toClose);
        }

        public void run() {
            Thread.currentThread().setName(toClose.toString() + "-->"
                    + toClose2.toString());
            byte[] buf = new byte[1024];
            try {
                while (true) {
                    try {
                        int read = this.in.read(buf);
                        if (read > 0) {
                            try {
                                this.out.write(buf, 0, read);
                            } catch (IOException e) {
                                LOG.warn("exception during write", e);
                                break;
                            }
                        } else if (read < 0) {
                            throw new IOException("read " + read);
                        }
                    } catch (SocketTimeoutException e) {
                        LOG.error("socket timeout", e);
                    }
                }
                Thread.sleep(1);
            } catch (InterruptedException e) {
                LOG.warn("Interrupted", e);
            } catch (SocketException e) {
                if (!"Socket closed".equals(e.getMessage())) {
                    LOG.error("Unexpected exception", e);
                }
            } catch (IOException e) {
                LOG.error("Unexpected exception", e);
            } finally {
                shutdown();
            }
            LOG.info("Shutting down forward for " + toClose);
            isFinished = true;
        }

        boolean waitForShutdown(long timeoutMs) throws InterruptedException {
            synchronized (this) {
                if (!isFinished) {
                   this.wait(timeoutMs);
                }
            }
            return isFinished;
        }

        public void shutdown() {
            try {
                toClose.close();
            } catch (IOException ex) {
                // ignore
            }
            try {
                toClose2.close();
            } catch (IOException ex) {
                // ignore silently
            }
        }
    }

    private volatile boolean stopped = false;
    private ExecutorService workerExecutor = Executors.newCachedThreadPool();
    private List<PortForwardWorker> workers = new ArrayList<>();
    private ServerSocket serverSocket;
    private final int to;

    public PortForwarder(int from, int to) throws IOException {
        this.to = to;
        serverSocket = new ServerSocket(from);
        serverSocket.setSoTimeout(30000);
        this.start();
    }

    @Override
    public void run() {
        try {
            while (!stopped) {
                Socket sock = null;
                try {
                    LOG.info("accepting socket local:"
                            + serverSocket.getLocalPort() + " to:" + to);
                    sock = serverSocket.accept();
                    LOG.info("accepted: local:" + sock.getLocalPort()
                            + " from:" + sock.getPort()
                            + " to:" + to);
                    Socket target = null;
                    int retry = 10;
                    while(sock.isConnected()) {
                        try {
                            target = new Socket("localhost", to);
                            break;
                        } catch (IOException e) {
                            if (retry == 0) {
                               throw e;
                            }
                            LOG.warn("connection failed, retrying(" + retry
                                    + "): local:" + sock.getLocalPort()
                                    + " from:" + sock.getPort()
                                    + " to:" + to, e);
                        }
                        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                        retry--;
                    }
                    LOG.info("connected: local:" + sock.getLocalPort()
                            + " from:" + sock.getPort()
                            + " to:" + to);
                    sock.setSoTimeout(30000);
                    target.setSoTimeout(30000);


                    workers.add(new PortForwardWorker(sock, target,
                            sock.getInputStream(), target.getOutputStream()));
                    workers.add(new PortForwardWorker(target, sock,
                            target.getInputStream(), sock.getOutputStream()));
                    for (PortForwardWorker worker: workers) {
                        workerExecutor.submit(worker);
                    }
                } catch (SocketTimeoutException e) {
                    LOG.warn("socket timed out", e);
                } catch (ConnectException e) {
                    LOG.warn("connection exception local:" + sock.getLocalPort()
                            + " from:" + sock.getPort()
                            + " to:" + to, e);
                    sock.close();
                } catch (IOException e) {
                    if (!"Socket closed".equals(e.getMessage())) {
                        LOG.warn("unexpected exception local:" + sock.getLocalPort()
                            + " from:" + sock.getPort()
                            + " to:" + to, e);
                        throw e;
                    }
                }

            }
        } catch (IOException e) {
            LOG.error("Unexpected exception to:" + to, e);
        } catch (InterruptedException e) {
            LOG.error("Interrupted to:" + to, e);
        }
    }

    public void shutdown() throws Exception {
        this.stopped = true;
        this.serverSocket.close();
        this.join();
        this.workerExecutor.shutdownNow();
        for (PortForwardWorker worker: workers) {
            worker.shutdown();
        }

        for (PortForwardWorker worker: workers) {
            if (!worker.waitForShutdown(5000)) {
                throw new Exception("Failed to stop forwarding within 5 seconds");
            }
        }
    }
}
