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

package org.apache.zookeeper.server;

import junit.framework.TestCase;

import org.apache.zookeeper.proto.SetDataRequest;
import org.junit.Test;

/**
 * A misc place to verify toString methods - mainly to make sure they don't
 * fail.
 */
public class ToStringTest extends TestCase {
    /** Verify jute - which we've had particular problems with in the past 
     * wrt null fields */
    @Test
    public void testJuteToString() {
        SetDataRequest req = new SetDataRequest(null, null, 0);
        assertNotSame("ERROR", req.toString());
    }
}
