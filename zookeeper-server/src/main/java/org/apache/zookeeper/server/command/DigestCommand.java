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

package org.apache.zookeeper.server.command;

import java.io.PrintWriter;
import java.util.List;
import org.apache.zookeeper.server.DataTree.ZxidDigest;
import org.apache.zookeeper.server.ServerCnxn;

/**
 * Command used to dump the latest digest histories.
 */
public class DigestCommand extends AbstractFourLetterCommand {

    public DigestCommand(PrintWriter pw, ServerCnxn serverCnxn) {
        super(pw, serverCnxn);
    }

    @Override
    public void commandRun() {
        if (!isZKServerRunning()) {
            pw.print(ZK_NOT_SERVING);
        } else {
            List<ZxidDigest> digestLog = zkServer.getZKDatabase().getDataTree().getDigestLog();
            for (ZxidDigest zd : digestLog) {
                pw.println(Long.toHexString(zd.getZxid()) + ": " + zd.getDigest());
            }
        }
    }

}
