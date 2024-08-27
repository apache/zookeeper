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

package org.apache.zookeeper.common;

import java.util.Date;

public class Time {

    public static final int MILLISECOND = 1;
    public static final int SECOND = 1000 * MILLISECOND;
    public static final int MINUTE = 60 * SECOND;
    public static final int HOUR = 60 * MINUTE;
    public static final int DAY = 24 * HOUR;

    /**
     * Returns time in milliseconds as does System.currentTimeMillis(),
     * but uses elapsed time from an arbitrary epoch more like System.nanoTime().
     * The difference is that if somebody changes the system clock,
     * Time.currentElapsedTime will change but nanoTime won't. On the other hand,
     * all of ZK assumes that time is measured in milliseconds.
     * @return The time in milliseconds from some arbitrary point in time.
     */
    public static long currentElapsedTime() {
        return System.nanoTime() / 1000000;
    }

    /**
     * Explicitly returns system dependent current wall time.
     * @return Current time in msec.
     */
    public static long currentWallTime() {
        return System.currentTimeMillis();
    }

    /**
     * This is to convert the elapsedTime to a Date.
     * @return A date object indicated by the elapsedTime.
     */
    public static Date elapsedTimeToDate(long elapsedTime) {
        long wallTime = currentWallTime() + elapsedTime - currentElapsedTime();
        return new Date(wallTime);
    }

    /** Parse a string as a time interval.  An interval is specified as an
     * integer with an optional suffix.  No suffix means milliseconds, s, m,
     * h, d indicates seconds, minutes, hours, and days respectively.
     * As a special case, "ms" means milliseconds.
     * @param str the interval string
     * @return interval in milliseconds
     */

    public static int parseTimeInterval(String str) {
        try {
            int len = str.length();
            char suffix = str.charAt(len - 1);
            if (Character.isDigit(suffix)) {
                return Integer.parseInt(str);
            } else {
                if (str.endsWith("ms") || str.endsWith("MS")) {
                    return Integer.parseInt(str.substring(0, len - 2));
                } else {
                    String numstr = str.substring(0, len - 1);
                    switch (Character.toUpperCase(suffix)) {
                    case 'S':
                        return Integer.parseInt(numstr) * SECOND;
                    case 'M':
                        return Integer.parseInt(numstr) * MINUTE;
                    case 'H':
                        return Integer.parseInt(numstr) * HOUR;
                    case 'D':
                        return Integer.parseInt(numstr) * DAY;
                    default:
                        throw new NumberFormatException("Illegal time interval suffix: " + str);
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new NumberFormatException("empty string");
        }
    }

}
