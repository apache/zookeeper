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

package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;

/**
 * Represents the info used to fetch the next page of data for pagination.
 */
@InterfaceAudience.Public
public class PaginationNextPage {
    private long minCzxid;
    private int minCzxidOffset;

    public long getMinCzxid() {
        return minCzxid;
    }

    public void setMinCzxid(long minCzxid) {
        this.minCzxid = minCzxid;
    }

    public int getMinCzxidOffset() {
        return minCzxidOffset;
    }

    public void setMinCzxidOffset(int minCzxidOffset) {
        this.minCzxidOffset = minCzxidOffset;
    }

    @Override
    public String toString() {
        return "PaginationNextPage{"
                + "minCzxid=" + minCzxid
                + ", minCzxidOffset=" + minCzxidOffset
                + '}';
    }
}
