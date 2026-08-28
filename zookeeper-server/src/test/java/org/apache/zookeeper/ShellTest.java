/*
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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

public class ShellTest {

    /**
     * Interrupting a thread running an external command must not swallow the interrupt.
     * The interrupt does not abort the child process, it only has to survive into the
     * caller, which is what {@link Login} relies on to notice it was asked to stop.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void shouldRestoreInterruptStatusWhenInterruptedWhileWaitingForProcess() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interruptedAfterwards = new AtomicBoolean(false);
        AtomicReference<IOException> expectedFailure = new AtomicReference<>();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        Thread runner = new Thread(() -> {
            started.countDown();
            try {
                // streams closed up front, so execCommand() reaches Process.waitFor()
                // while the child is still alive
                Shell.execCommand("sh", "-c", "exec >/dev/null 2>&1; sleep 2");
            } catch (IOException e) {
                expectedFailure.set(e);
            } catch (Throwable t) {
                unexpectedFailure.set(t);
            } finally {
                interruptedAfterwards.set(Thread.currentThread().isInterrupted());
                finished.countDown();
            }
        });
        runner.start();

        assertTrue(started.await(10, TimeUnit.SECONDS), "runner thread did not start");

        // waitFor() is the only blocking call left, wait for the thread to park in it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runner.getState() != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < deadline, "runner thread never reached Process.waitFor()");
            Thread.onSpinWait();
        }
        runner.interrupt();

        assertTrue(finished.await(30, TimeUnit.SECONDS), "execCommand did not return");
        runner.join();

        assertNull(unexpectedFailure.get(), "unexpected failure in runner thread");
        assertNotNull(expectedFailure.get(), "execCommand was expected to fail with an IOException");
        assertTrue(interruptedAfterwards.get(),
            "execCommand must leave the interrupt status set, otherwise the caller never "
                + "learns that it was asked to stop");
    }

}
