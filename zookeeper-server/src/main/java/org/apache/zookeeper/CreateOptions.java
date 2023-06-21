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

import java.util.List;
import java.util.Objects;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.EphemeralType;

/**
 * Options for creating znode in ZooKeeper data tree.
 */
public class CreateOptions {
    private final CreateMode createMode;
    private final List<ACL> acl;
    private final long ttl;

    public CreateMode getCreateMode() {
        return createMode;
    }

    public List<ACL> getAcl() {
        return acl;
    }

    public long getTtl() {
        return ttl;
    }

    /**
     * Constructs a builder for {@link CreateOptions}.
     *
     * @param acl
     *                the acl for the node
     * @param createMode
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     */
    public static Builder newBuilder(List<ACL> acl, CreateMode createMode) {
        return new Builder(createMode, acl);
    }

    private CreateOptions(CreateMode createMode, List<ACL> acl, long ttl) {
        this.createMode = createMode;
        this.acl = acl;
        this.ttl = ttl;
        EphemeralType.validateTTL(createMode, ttl);
    }

    /**
     * Builder for {@link CreateOptions}.
     */
    public static class Builder {
        private final CreateMode createMode;
        private final List<ACL> acl;
        private long ttl = -1;

        private Builder(CreateMode createMode, List<ACL> acl) {
            this.createMode = Objects.requireNonNull(createMode, "create mode is mandatory for create options");
            this.acl = Objects.requireNonNull(acl, "acl is mandatory for create options");
        }

        public Builder withTtl(long ttl) {
            this.ttl = ttl;
            return this;
        }

        public CreateOptions build() {
            return new CreateOptions(createMode, acl, ttl);
        }
    }
}
