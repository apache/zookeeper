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

package org.apache.zookeeper.server.quorum.flexible;

import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 *
 * QuorumOracleMaj is a subclass of QuorumMaj.
 *
 * QuorumOracleMaj is designed to be functional in a 2-nodes configuration. The only method that this class overrides super
 * class' method is containsQuorum(). Besides the check of oracle, it also checks the number of voting member. Whenever the
 * number of voting members is greater than 2. QuorumOracleMaj shall function as hook to its super class.
 * */
public class QuorumOracleMaj extends QuorumMaj {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumOracleMaj.class);

    private String oracle = null;

    private final AtomicBoolean needOracle = new AtomicBoolean(true);

    public QuorumOracleMaj(Map<Long, QuorumPeer.QuorumServer> allMembers, String oraclePath) {
        super(allMembers);
        setOracle(oraclePath);
    }

    public QuorumOracleMaj(Properties props, String oraclePath) throws QuorumPeerConfig.ConfigException {
        super(props);
        setOracle(oraclePath);
    }

    private void setOracle(String path) {
        if (oracle == null) {
            oracle = path;
            LOG.info("Oracle is set to {}", path);
        } else {
            LOG.warn("Oracle is already set. Ignore:{}", path);
        }
    }

    @Override
    public boolean updateNeedOracle(List<LearnerHandler> forwardingFollowers) {
        // Do we have the quorum
        needOracle.set(forwardingFollowers.isEmpty() && super.getVotingMembers().size() == 2);
        return needOracle.get();
    }

    @Override
    public boolean askOracle() {
        FileReader fr = null;
        try {
            int read;
            fr = new FileReader(oracle);
            read = fr.read();
            LOG.debug("Oracle says:{}", (char) read);
            fr.close();
            return (char) read == '1';
        } catch (Exception e) {
            e.printStackTrace();
            if (oracle == null) {
                LOG.error("Oracle is not set, return false");
            }
            return false;
        } finally {
            if (fr != null) {
                try {
                    fr.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public boolean getNeedOracle() {
        return needOracle.get();
    }

    @Override
    public String getOraclePath() {
        return oracle;
    }

    @Override
    public boolean containsQuorum(Set<Long> ackSet) {
        if (oracle == null || getVotingMembers().size() > 2) {
            return super.containsQuorum(ackSet);
        } else if (!super.containsQuorum(ackSet)) {
            if (getNeedOracle()) {
                LOG.debug("We lose the quorum, but we do not have any valid followers Oracle:{}", askOracle());
                return askOracle();
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        QuorumOracleMaj qm = (QuorumOracleMaj) o;
        if (qm.getVersion() == super.getVersion()) {
            return true;
        }
        if (super.getAllMembers().size() != qm.getAllMembers().size()) {
            return false;
        }
        for (QuorumPeer.QuorumServer qs : super.getAllMembers().values()) {
            QuorumPeer.QuorumServer qso = qm.getAllMembers().get(qs.id);
            if (qso == null || !qs.equals(qso)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        assert false : "hashCode not designed";
        return 43; // any arbitrary constant will do
    }
}

