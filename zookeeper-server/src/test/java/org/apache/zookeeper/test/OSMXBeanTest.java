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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSMXBeanTest extends ZKTestCase {

    private OSMXBean osMbean;
    private Long ofdc = 0L;
    private Long mfdc = 0L;
    protected static final Logger LOG = LoggerFactory.getLogger(OSMXBeanTest.class);

    @BeforeEach
    public void initialize() {
        this.osMbean = new OSMXBean();
        assertNotNull(osMbean, "Could not initialize OSMXBean object!");
    }

    @Test
    public final void testGetUnix() {
        boolean isUnix = osMbean.getUnix();
        if (!isUnix) {
            LOG.info("Running in a Windows system! Output won't be printed!");
        } else {
            LOG.info("Running in a Unix or Linux system!");
        }
    }

    @Test
    public final void testGetOpenFileDescriptorCount() {
        if (osMbean != null && osMbean.getUnix()) {
            ofdc = osMbean.getOpenFileDescriptorCount();
            LOG.info("open fdcount is: {}", ofdc);
        }
        assertFalse((ofdc < 0), "The number of open file descriptor is negative");
    }

    @Test
    public final void testGetMaxFileDescriptorCount() {
        if (osMbean != null && osMbean.getUnix()) {
            mfdc = osMbean.getMaxFileDescriptorCount();
            LOG.info("max fdcount is: {}", mfdc);
        }
        assertFalse((mfdc < 0), "The max file descriptor number is negative");
    }

}
