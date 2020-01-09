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

package org.apache.jute;

/**
 * Various utility functions for Hadoop record I/O runtime.
 */
public class Utils {

    /**
     * Cannot create a new instance of Utils.
     */
    private Utils() {
        super();
    }

    public static int compareBytes(byte[] b1, int off1, int len1, byte[] b2, int off2, int len2) {
        int i;
        for (i = 0; i < len1 && i < len2; i++) {
            if (b1[off1 + i] != b2[off2 + i]) {
                return b1[off1 + i] < b2[off2 + i] ? -1 : 1;
            }
        }
        if (len1 != len2) {
            return len1 < len2 ? -1 : 1;
        }
        return 0;
    }
}
