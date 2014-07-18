/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A watch report, essentially a mapping of session ID to paths that the session
 * has set a watch on. This class is immutable.
 */
public class WatchesReport {

    private final Map<Long, Set<String>> id2paths;

    /**
     * Creates a new report.
     *
     * @param id2paths map of session IDs to paths that each session has set
     * a watch on
     */
    WatchesReport(Map<Long, Set<String>> id2paths) {
        this.id2paths = Collections.unmodifiableMap(deepCopy(id2paths));
    }

    private static Map<Long, Set<String>> deepCopy(Map<Long, Set<String>> m) {
        Map<Long, Set<String>> m2 = new HashMap<Long, Set<String>>();
        for (Map.Entry<Long, Set<String>> e : m.entrySet()) {
            m2.put(e.getKey(), new HashSet<String>(e.getValue()));
        }
        return m2;
    }

    /**
     * Checks if the given session has watches set.
     *
     * @param sessionId session ID
     * @return true if session has paths with watches set
     */
    public boolean hasPaths(long sessionId) {
        return id2paths.containsKey(sessionId);
    }

    /**
     * Gets the paths that the given session has set watches on. The returned
     * set is immutable.
     *
     * @param sessionId session ID
     * @return paths that have watches set by the session, or null if none
     */
    public Set<String> getPaths(long sessionId) {
        Set<String> s = id2paths.get(sessionId);
        return s != null ? Collections.unmodifiableSet(s) : null;
    }

    /**
     * Converts this report to a map. The returned map is mutable, and changes
     * to it do not reflect back into this report.
     *
     * @return map representation of report
     */
    public Map<Long, Set<String>> toMap() {
        return deepCopy(id2paths);
    }
}
