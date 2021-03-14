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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Finds the local host's loopback hostname for use in tests that
 * perform reverse lookups on the IPv4 loopback address 127.0.0.1
 * and need the host names to match.
 * <p>
 * On most systems this is "localhost", but on some Linux
 * distributions it is "localhost.localdomain".
 */
public class Hostname {

    public static String hostname;

    static {
        try {
            hostname = InetAddress.getByName("127.0.0.1").getCanonicalHostName();
        } catch (UnknownHostException uhe) {
            hostname = "localhost";
        }
    }

    public static String getLocalHost() {
        return hostname;
    }
}
