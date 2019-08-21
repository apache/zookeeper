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

package org.apache.zookeeper.server.admin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class CommandBase implements Command {

    private final String primaryName;
    private final Set<String> names;
    private final String doc;
    private final boolean serverRequired;

    /**
     * @param names The possible names of this command, with the primary name first.
     */
    protected CommandBase(List<String> names) {
        this(names, true, null);
    }
    protected CommandBase(List<String> names, boolean serverRequired) {
        this(names, serverRequired, null);
    }

    protected CommandBase(List<String> names, boolean serverRequired, String doc) {
        this.primaryName = names.get(0);
        this.names = new HashSet<String>(names);
        this.doc = doc;
        this.serverRequired = serverRequired;
    }

    @Override
    public String getPrimaryName() {
        return primaryName;
    }

    @Override
    public Set<String> getNames() {
        return names;
    }

    @Override
    public String getDoc() {
        return doc;
    }

    @Override
    public boolean isServerRequired() {
        return serverRequired;
    }

    /**
     * @return A response with the command set to the primary name and the
     *         error set to null (these are the two entries that all command
     *         responses are required to include).
     */
    protected CommandResponse initializeResponse() {
        return new CommandResponse(primaryName);
    }

}
