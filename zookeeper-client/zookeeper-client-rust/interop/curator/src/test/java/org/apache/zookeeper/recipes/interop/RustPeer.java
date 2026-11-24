// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.zookeeper.recipes.interop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Subprocess controller for the Rust {@code interop_peer} recipe driver.
 *
 * <p>Communicates with the {@code interop_peer} example binary from
 * {@code apache-zookeeper-recipes} using a line-oriented command/response
 * protocol over {@code stdin} and {@code stdout}. Closing this controller
 * issues {@code QUIT} and terminates the subprocess.
 *
 * <p>The peer processes commands sequentially. {@link #call} synchronizes
 * synchronous request/response exchanges, while {@link #callAsync} dispatches
 * blocking commands (such as contested lock acquisition) on a dedicated
 * background executor thread.
 */
final class RustPeer implements AutoCloseable {

    /** System property specifying the path to the {@code interop_peer} binary. */
    static final String BIN_PROPERTY = "rust.peer.bin";

    /** Maximum timeout for a single peer command response. */
    static final Duration REPLY_WAIT = Duration.ofSeconds(30);

    private final Process process;
    private final BufferedWriter in;
    private final BufferedReader out;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rust-peer-reader");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService caller = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rust-peer-caller");
        t.setDaemon(true);
        return t;
    });

    private RustPeer(Process process) {
        this.process = process;
        this.in = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Returns the executable peer binary, or {@code null} if unconfigured or missing. */
    static File binary() {
        String path = System.getProperty(BIN_PROPERTY, "");
        File f = new File(path);
        return !path.isEmpty() && f.canExecute() ? f : null;
    }

    /**
     * Spawns a peer subprocess connected to {@code connect} and awaits its
     * initial {@code READY} handshake line.
     */
    static RustPeer start(String connect) throws IOException, TimeoutException {
        File bin = binary();
        if (bin == null) {
            throw new IOException("no Rust peer binary in " + BIN_PROPERTY);
        }
        Process p = new ProcessBuilder(bin.getPath(), connect)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        RustPeer peer = new RustPeer(p);
        String first;
        try {
            first = peer.readLine();
        } catch (IOException | TimeoutException e) {
            peer.close();
            throw e;
        }
        if (!"READY".equals(first)) {
            peer.close();
            throw new IOException("the Rust peer did not start: " + first);
        }
        return peer;
    }

    /** Transmits a single command line and returns the peer's response line. */
    synchronized String call(String command) throws IOException, TimeoutException {
        in.write(command);
        in.newLine();
        in.flush();
        return readLine();
    }

    /** Submits a command asynchronously on the peer's background caller thread. */
    Future<String> callAsync(String command) {
        return caller.submit(() -> call(command));
    }

    /**
     * Issues {@code LOCK}, waiting up to {@code timeoutMs} to acquire the lock
     * at {@code path} (returning {@code OK <node-name>} or {@code TIMEOUT}).
     */
    Future<String> lock(String path, long timeoutMs) {
        return callAsync("LOCK " + path + " " + timeoutMs);
    }

    /**
     * Issues {@code LOCK} with UTF-8 holder payload {@code id} stored in the
     * created lock znode.
     */
    Future<String> lock(String path, long timeoutMs, String id) {
        return callAsync("LOCK " + path + " " + timeoutMs + " " + id);
    }

    /** Issues {@code TRYLOCK}, returning the created node name or {@code null} if contended. */
    String tryLock(String path) throws IOException, TimeoutException {
        return tryLockReply(call("TRYLOCK " + path));
    }

    /** Issues {@code TRYLOCK} with UTF-8 holder payload {@code id}. */
    String tryLock(String path, String id) throws IOException, TimeoutException {
        return tryLockReply(call("TRYLOCK " + path + " " + id));
    }

    private static String tryLockReply(String reply) throws IOException {
        return "NONE".equals(reply) ? null : ok(reply);
    }

    /** Issues {@code UNLOCK} to release the currently held lock guard. */
    void unlock() throws IOException, TimeoutException {
        ok(call("UNLOCK"));
    }

    /**
     * Issues {@code DROP_LOCK} to drop the held lock guard in place, returning
     * immediately while background guaranteed-delete cleanup proceeds.
     */
    void dropLock() throws IOException, TimeoutException {
        ok(call("DROP_LOCK"));
    }

    /**
     * Issues {@code ABANDON_LOCK}, starting a lock acquisition on {@code path}
     * and dropping the future after {@code afterMs} milliseconds.
     */
    Future<String> abandonLock(String path, long afterMs) {
        return callAsync("ABANDON_LOCK " + path + " " + afterMs);
    }

    /** Issues {@code LATCH} to start a leader latch and returns its znode name. */
    String latch(String path, String id) throws IOException, TimeoutException {
        return ok(call("LATCH " + path + " " + id));
    }

    /** Issues {@code AWAIT_LEADER}, returning {@code true} if leadership is acquired in time. */
    boolean awaitLeader(long timeoutMs) throws IOException, TimeoutException {
        String reply = call("AWAIT_LEADER " + timeoutMs);
        if ("TIMEOUT".equals(reply)) {
            return false;
        }
        ok(reply);
        return true;
    }

    /** Issues {@code IS_LEADER} to query whether the peer currently holds leadership. */
    boolean isLeader() throws IOException, TimeoutException {
        return Boolean.parseBoolean(ok(call("IS_LEADER")));
    }

    /**
     * Issues {@code PARTICIPANTS}, returning {@code <id>:<0 or 1>} entries in
     * latch sequence order.
     */
    List<String> participants() throws IOException, TimeoutException {
        String rest = ok(call("PARTICIPANTS"));
        return rest.isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(rest.split(" ")));
    }

    /** Issues {@code CLOSE_LATCH} to close the active latch and delete its znode. */
    void closeLatch() throws IOException, TimeoutException {
        ok(call("CLOSE_LATCH"));
    }

    /** Issues {@code SESSION} to retrieve the peer's active session ID and password. */
    Session session() throws IOException, TimeoutException {
        String[] parts = ok(call("SESSION")).split(" ");
        if (parts.length != 2) {
            throw new IOException("bad SESSION reply: " + String.join(" ", parts));
        }
        byte[] password = new byte[parts[1].length() / 2];
        for (int i = 0; i < password.length; i++) {
            password[i] = (byte) Integer.parseInt(parts[1].substring(2 * i, 2 * i + 2), 16);
        }
        return new Session(Long.parseUnsignedLong(parts[0], 16), password);
    }

    /**
     * Verifies that {@code reply} begins with {@code OK} and returns any
     * trailing payload text, throwing {@link IOException} otherwise.
     */
    static String ok(String reply) throws IOException {
        if ("OK".equals(reply)) {
            return "";
        }
        if (reply.startsWith("OK ")) {
            return reply.substring(3);
        }
        throw new IOException("the Rust peer replied: " + reply);
    }

    private String readLine() throws IOException, TimeoutException {
        Future<String> line = reader.submit(out::readLine);
        try {
            String s = line.get(REPLY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (s == null) {
                throw new IOException("the Rust peer closed stdout");
            }
            return s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
    }

    /** Terminates the peer process forcibly (`SIGKILL`), leaving its session open on the server. */
    void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // Cancel any in-flight asynchronous command, issue QUIT if the peer is
        // responsive, and forcibly terminate the process if it does not exit within 5 s.
        caller.shutdownNow();
        try {
            if (process.isAlive() && caller.awaitTermination(1, TimeUnit.SECONDS)) {
                call("QUIT");
            }
        } catch (IOException | TimeoutException e) {
            // Process termination is enforced below.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        reader.shutdownNow();
    }

    /** Captures a peer's ZooKeeper session credentials for forced session expiration. */
    static final class Session {
        final long id;
        final byte[] password;

        Session(long id, byte[] password) {
            this.id = id;
            this.password = password;
        }
    }
}
