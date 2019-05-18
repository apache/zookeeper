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

package org.apache.zookeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a class that represents the stats associated with quotas
 */
public class StatsTrack {
    private int count;
    private long bytes;

    private static final String countStr = "count";
    private static final String countHardLimitStr = "countHardLimit";

    private static final String byteStr = "bytes";
    private static final String byteHardLimitStr = "byteHardLimit";

    private static final String bytesPerSecStr = "bytesPerSec";
    private static final String bytesPerSecBytesStr = "bpsBytes";
    private static final String bytesPerSecStartTimeStr = "bpsTime";
    private static final String bytesPerSecHardLimitStr = "bytesPerSecHardLimit";

    private final Map<String, Long> stats = new HashMap<>();

    /**
     * a default constructor for
     * stats
     */
    public StatsTrack() {
        this(null);
    }
    /**
     * the stat string should be of the form <key1str>=long,<key2str>=long,..
     * where either , or ; are valid separators
     * uninitialized values are returned as -1
     * @param stats the stat string to be initialized with
     */
    public StatsTrack(String stats) {
        this.stats.clear();
        if (stats == null || stats.length() == 0) {
            return;
        }
        String[] keyValuePairs = stats.split("[,;]");
        for (String keyValuePair : keyValuePairs) {
            String[] kv = keyValuePair.split("=");
            this.stats.put(kv[0], Long.parseLong(kv[1]));
        }
    }


    /**
     * get the count of nodes allowed as part of quota
     *
     * @return the count as part of this string
     */
    public int getCount() {
        return (int) getValue(countStr);
    }

    /**
     * set the count for this stat tracker.
     *
     * @param count
     *            the count to set with
     */
    public void setCount(int count) {
        setValue(countStr, count);
    }

    /**
     * get the count of nodes allowed as part of quota (hard limit)
     *
     * @return the count as part of this string
     */
    public int getCountHardLimit() {
        return (int) getValue(countHardLimitStr);
    }

    /**
     * set the count hard limit
     *
     * @param count the count limit to set
     */
    public void setCountHardLimit(int count) {
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
     * get the bytes-per-sec allowed as part of quota
     *
     * @return the bytes-per-sec
     */
    public long getBytesPerSec() {
        return getValue(bytesPerSecStr);
    }

    /**
     * set the bytes-per-sec for this stat tracker.
     *
     * @param bytesPerSec the bytes-per-sec to set with
     */
    public void setBytesPerSec(long bytesPerSec) {
        setValue(bytesPerSecStr, bytesPerSec);
    }

    /**
     * get the bytes-per-sec allowed as part of quota (hard limit)
     *
     * @return the bytes-per-sec hard limit
     */
    public long getBytesPerSecHardLimit() {
        return getValue(bytesPerSecHardLimitStr);
    }

    /**
     * set the bytes-per-sec hard limit
     *
     * @param bytesPerSec the bytes-per-sec to set with
     */
    public void setBytesPerSecHardLimit(long bytesPerSec) {
        setValue(bytesPerSecHardLimitStr, bytesPerSec);
    }

    /**
     * get the bytes-per-sec byte count for the current window
     *
     * @return the bytes-per-sec byte count
     */
    public long getBytesPerSecBytes() {
        return getValue(bytesPerSecBytesStr);
    }

    /**
     * set the bytes-per-second bytes for this stat tracker.
     *
     * @param bpsBytes the bytes to set with
     */
    public void setBytesPerSecBytes(long bpsBytes) {
        setValue(bytesPerSecBytesStr, bpsBytes);
    }

    /**
     * get the bytes-per-second window start time in milliseconds
     *
     * @return the bytes-per-sec window start time
     */
    public long getBytesPerSecStartTime() {
        return getValue(bytesPerSecStartTimeStr);
    }

    /**
     * set the bytes-per-sec window start time for this stat tracker.
     *
     * @param time the bytes-per-sec start time to set with
     */
    public void setBytesPerSecStartTime(long time) {
        setValue(bytesPerSecStartTimeStr, time);
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
        List<String> keys = new ArrayList<>(stats.keySet());

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
}
