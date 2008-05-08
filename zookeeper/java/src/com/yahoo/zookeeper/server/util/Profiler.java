/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.util;

import org.apache.log4j.Logger;

public class Profiler {
    private static final Logger LOG = Logger.getLogger(Profiler.class);

    public interface Operation<T> {
        public T execute() throws Exception;
    }

    public static <T> T profile(Operation<T> op, long timeout, String message)
            throws Exception {
        long start = System.currentTimeMillis();
        T res = op.execute();
        long end = System.currentTimeMillis();
        if (end - start > timeout) {
            LOG.warn("Elapsed "+(end - start) + " ms: " + message);
        }
        return res;
    }
}
