/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.recipes.lock;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an immutable ephemeral znode name which has an ordered sequence
 * number and can be sorted in order. The expected name format of the znode is
 * as follows:
 *
 * <pre>
 * &lt;name&gt;-&lt;sequence&gt;
 *
 * For example: lock-00001
 * </pre>
 */
class ZNodeName implements Comparable<ZNodeName> {

    private static final Logger LOG = LoggerFactory.getLogger(ZNodeName.class);

    private final String name;
    private final String prefix;
    private final Optional<Integer> sequence;

    /**
     * Instantiate a ZNodeName with the provided znode name.
     *
     * @param name The name of the znode
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public ZNodeName(final String name) {
        this.name = Objects.requireNonNull(name, "ZNode name cannot be null");

        final int idx = name.lastIndexOf('-');
        if (idx < 0) {
            this.prefix = name;
            this.sequence = Optional.empty();
        } else {
            this.prefix = name.substring(0, idx);
            this.sequence = Optional.ofNullable(parseSequenceString(name.substring(idx + 1)));
        }
    }

    private Integer parseSequenceString(final String seq) {
        try {
            return Integer.parseInt(seq);
        } catch (Exception e) {
            LOG.warn("Number format exception for {}", seq, e);
            return null;
        }
    }

    @Override
    public String toString() {
      return "ZNodeName [name=" + name + ", prefix=" + prefix + ", sequence="
          + sequence + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ZNodeName other = (ZNodeName) o;

        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * Compare znodes based on their sequence number.
     *
     * @param that other znode to compare to
     * @return the difference between their sequence numbers: a positive value if this
     *         znode has a larger sequence number, 0 if they have the same sequence number
     *         or a negative number if this znode has a lower sequence number
     */
    public int compareTo(final ZNodeName that) {
        if (this.sequence.isPresent() && that.sequence.isPresent()) {
            int cseq = Integer.compare(this.sequence.get(), that.sequence.get());
            return (cseq != 0) ? cseq : this.prefix.compareTo(that.prefix);
        }
        if (this.sequence.isPresent()) {
            return -1;
        }
        if (that.sequence.isPresent()) {
            return 1;
        }
        return this.prefix.compareTo(that.prefix);
    }

    /**
     * Returns the name of the znode.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the optional sequence number.
     */
    public Optional<Integer> getSequence() {
        return sequence;
    }

    /**
     * Returns the text prefix before the sequence number.
     */
    public String getPrefix() {
        return prefix;
    }

}
