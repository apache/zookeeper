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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.zookeeper.common.StringUtils;

/**
 * a class that represents the stats associated with quotas
 */
public class StatsTrack {

    private static final String countStr = "count";
    private static final String countHardLimitStr = "countHardLimit";

    private static final String byteStr = "bytes";
    private static final String byteHardLimitStr = "byteHardLimit";

    private final Map<String, Long> stats = new HashMap<>();
    private static final Pattern PAIRS_SEPARATOR = Pattern.compile("[,;]+");

    /**
     * a default constructor for
     * stats
     */
    public StatsTrack() {
        this("");
    }

    /**
     *
     * @param stat the byte[] stat to be initialized with
     */
    public StatsTrack(byte[] stat) {
        this(new String(stat, StandardCharsets.UTF_8));
    }

    /**
     * the stat string should be of the form key1str=long,key2str=long,..
     * where either , or ; are valid separators
     * uninitialized values are returned as -1
     * @param stat the stat string to be initialized with
     */
    public StatsTrack(String stat) {
        this.stats.clear();
        if (stat == null || stat.length() == 0) {
            return;
        }
        String[] keyValuePairs = PAIRS_SEPARATOR.split(stat);
        for (String keyValuePair : keyValuePairs) {
            String[] kv = keyValuePair.split("=");
            this.stats.put(kv[0], Long.parseLong(StringUtils.isEmpty(kv[1]) ? "-1" : kv[1]));
        }
    }


    /**
     * get the count of nodes allowed as part of quota
     *
     * @return the count as part of this string
     */
    public long getCount() {
        return getValue(countStr);
    }

    /**
     * set the count for this stat tracker.
     *
     * @param count
     *            the count to set with
     */
    public void setCount(long count) {
        setValue(countStr, count);
    }

    /**
     * get the count of nodes allowed as part of quota (hard limit)
     *
     * @return the count as part of this string
     */
    public long getCountHardLimit() {
        return getValue(countHardLimitStr);
    }

    /**
     * set the count hard limit
     *
     * @param count the count limit to set
     */
    public void setCountHardLimit(long count) {
        setValue(countHardLimitStr, count);
    }

    /**
     * get the count of bytes allowed as part of quota
     *
     * @return the bytes as part of this string
     */
    public long getBytes() {
        return getValue(byteStr);
    }

    /**
     * set the bytes for this stat tracker.
     *
     * @param bytes
     *            the bytes to set with
     */
    public void setBytes(long bytes) {
        setValue(byteStr, bytes);
    }

    /**
     * get the count of bytes allowed as part of quota (hard limit)
     *
     * @return the bytes as part of this string
     */
    public long getByteHardLimit() {
        return getValue(byteHardLimitStr);
    }

    /**
     * set the byte hard limit
     *
     * @param bytes the byte limit to set
     */
    public void setByteHardLimit(long bytes) {
        setValue(byteHardLimitStr, bytes);
    }

    /**
     * get helper to lookup a given key
     *
     * @param key the key to lookup
     * @return key's value or -1 if it doesn't exist
     */
    private long getValue(String key) {
        Long val = this.stats.get(key);
        return val == null ? -1 : val.longValue();
    }

    /**
     * set helper to set the value for the specified key
     *
     * @param key   the key to set
     * @param value the value to set
     */
    private void setValue(String key, long value) {
        this.stats.put(key, value);
    }

    /*
     * returns the string that maps to this stat tracking.
     *
     * Builds a string of the form
     * "count=4,bytes=5=;countHardLimit=10;byteHardLimit=10"
     *
     * This string is slightly hacky to preserve compatibility with 3.4.3 and
     * older parser. In particular, count must be first, bytes must be second,
     * all new fields must use a separator that is not a "," (so, ";"), and the
     * seemingly spurious "=" after the bytes field is essential to allowing
     * it to be parseable by the old parsing code.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        ArrayList<String> keys = new ArrayList<>(stats.keySet());

        // Special handling for count=,byte= to enforce them coming first
        // for backwards compatibility
        keys.remove(countStr);
        keys.remove(byteStr);
        buf.append(countStr);
        buf.append("=");
        buf.append(getCount());
        buf.append(",");
        buf.append(byteStr);
        buf.append("=");
        buf.append(getBytes());
        if (!keys.isEmpty()) {
            // Add extra = to trick old parsing code so it will ignore new flags
            buf.append("=");
            Collections.sort(keys);
            for (String key : keys) {
                buf.append(";");
                buf.append(key);
                buf.append("=");
                buf.append(stats.get(key));
            }
        }
        return buf.toString();
    }

    public byte[] getStatsBytes() {
        return toString().getBytes(StandardCharsets.UTF_8);
    }
}
