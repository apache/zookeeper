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
package org.apache.zookeeper.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.zookeeper.server.ExitCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for service management.
 */
public abstract class ServiceUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceUtils.class);

    private ServiceUtils() {
    }

    /**
     * Default strategy for shutting down the JVM.
     */
    @SuppressFBWarnings("DM_EXIT")
    public static final Consumer<Integer> SYSTEM_EXIT = (code) -> {
        String msg = "Exiting JVM with code {}";
        if (code == 0) {
            // JVM exits normally
            LOG.info(msg, code);
        } else {
            // JVM exits with error
            LOG.error(msg, code);
        }
        System.exit(code);
    };

    /**
     * No-op strategy, useful for tests.
     */
    public static final Consumer<Integer> LOG_ONLY = (code) -> {
        if (code != 0) {
            LOG.error("Fatal error, JVM should exit with code {}. "
                + "Actually System.exit is disabled", code);
        } else {
            LOG.info("JVM should exit with code {}. Actually System.exit is disabled", code);
        }
    };

    private static volatile Consumer<Integer> systemExitProcedure = SYSTEM_EXIT;

    /**
     * Override system callback. Useful for preventing the JVM to exit in tests
     * or in applications that are running an in-process ZooKeeper server.
     *
     * @param systemExitProcedure
     */
    public static void setSystemExitProcedure(Consumer<Integer> systemExitProcedure) {
        Objects.requireNonNull(systemExitProcedure);
        ServiceUtils.systemExitProcedure = systemExitProcedure;
    }

    /**
     * Force shutdown of the JVM using System.exit.
     *
     * @param code the exit code
     * @see ExitCode
     */
    public static void requestSystemExit(int code) {
        systemExitProcedure.accept(code);
    }

}
