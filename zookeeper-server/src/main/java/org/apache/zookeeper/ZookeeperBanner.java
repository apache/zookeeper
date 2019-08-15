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

import org.slf4j.Logger;

/**
 * ZookeeperBanner which writes the 'Zookeeper' banner at the start of zk server.
 *
 */
public class ZookeeperBanner {

    private static final String[] BANNER = {
        "",
        "  ______                  _                                          ",
        " |___  /                 | |                                         ",
        "    / /    ___     ___   | | __   ___    ___   _ __     ___   _ __   ",
        "   / /    / _ \\   / _ \\  | |/ /  / _ \\  / _ \\ | '_ \\   / _ \\ | '__|",
        "  / /__  | (_) | | (_) | |   <  |  __/ |  __/ | |_) | |  __/ | |    ",
        " /_____|  \\___/   \\___/  |_|\\_\\  \\___|  \\___| | .__/   \\___| |_|",
        "                                              | |                     ",
        "                                              |_|                     ", ""};

    public static void printBanner(Logger log) {
        for (String line : BANNER) {
            log.info(line);
        }
    }

}
