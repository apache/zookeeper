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

import java.util.List;
import org.apache.zookeeper.data.ClientInfo;

/**
 * WhoAmI command for cli
 */
public class WhoAmICommand extends CliCommand {

    public WhoAmICommand() {
        super("whoami", "");
    }

    @Override
    public CliCommand parse(String[] cmdArgs) throws CliParseException {
        return this;
    }

    @Override
    public boolean exec() throws CliException {
        try {
            List<ClientInfo> clientInfos = zk.whoAmI();
            out.println("Auth scheme: User");
            if (clientInfos != null) {
                // clientInfos will never be null, added null check to pass static checks
                clientInfos.forEach(clientInfo -> {
                    out.println(clientInfo.getAuthScheme() + ": " + clientInfo.getUser());
                });
            }
        } catch (Exception ex) {
            throw new CliWrapperException(ex);
        }
        return false;
    }

}
