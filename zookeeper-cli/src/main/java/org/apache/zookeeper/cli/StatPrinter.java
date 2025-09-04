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

package org.apache.zookeeper.cli;

import java.io.PrintStream;
import java.util.Date;
import org.apache.zookeeper.data.Stat;

/**
 * utility for printing stat values s
 */
public class StatPrinter {

    protected PrintStream out;

    public StatPrinter(PrintStream out) {
        this.out = out;
    }

    public void print(Stat stat) {
        out.println("cZxid = 0x" + Long.toHexString(stat.getCzxid()));
        out.println("ctime = " + new Date(stat.getCtime()).toString());
        out.println("mZxid = 0x" + Long.toHexString(stat.getMzxid()));
        out.println("mtime = " + new Date(stat.getMtime()).toString());
        out.println("pZxid = 0x" + Long.toHexString(stat.getPzxid()));
        out.println("cversion = " + stat.getCversion());
        out.println("dataVersion = " + stat.getVersion());
        out.println("aclVersion = " + stat.getAversion());
        out.println("ephemeralOwner = 0x" + Long.toHexString(stat.getEphemeralOwner()));
        out.println("dataLength = " + stat.getDataLength());
        out.println("numChildren = " + stat.getNumChildren());
    }

}
