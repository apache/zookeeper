/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import org.apache.zookeeper.Quotas;
import org.junit.Test;

public class QuotasTest {

    @Test
    public void testStatPath() {
        assertEquals("/zookeeper/quota/foo/zookeeper_stats", Quotas.statPath("/foo"));
        assertEquals("/zookeeper/quota/bar/zookeeper_stats", Quotas.statPath("/bar"));
    }

    @Test
    public void testLimitPath() {
        assertEquals("/zookeeper/quota/foo/zookeeper_limits", Quotas.limitPath("/foo"));
        assertEquals("/zookeeper/quota/bar/zookeeper_limits", Quotas.limitPath("/bar"));
    }

    @Test
    public void testQuotaPathPath() {
        assertEquals("/zookeeper/quota/bar", Quotas.quotaPath("/bar"));
        assertEquals("/zookeeper/quota/foo", Quotas.quotaPath("/foo"));
    }

    @Test
    public void testTrimQuotaPath() {
        assertEquals("/foo", Quotas.trimQuotaPath("/zookeeper/quota/foo"));
        assertEquals("/bar", Quotas.trimQuotaPath("/zookeeper/quota/bar"));
    }
}
