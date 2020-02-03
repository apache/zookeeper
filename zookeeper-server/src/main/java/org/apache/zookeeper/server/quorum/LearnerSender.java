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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.zookeeper.server.ZooKeeperCriticalThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LearnerSender extends ZooKeeperCriticalThread {
    private static final Logger LOG = LoggerFactory.getLogger(LearnerSender.class);

    private final LinkedBlockingQueue<QuorumPacket> queuedPackets = new LinkedBlockingQueue<>();
    private final QuorumPacket proposalOfDeath = new QuorumPacket();

    Learner learner;

    public LearnerSender(Learner learner) {
        super("LearnerSender:" + learner.zk.getServerId(), learner.zk.getZooKeeperServerListener());
        this.learner = learner;
    }

    @Override
    public void run() {
        while (true) {
            try {
                QuorumPacket p = queuedPackets.poll();
                if (p == null) {
                    learner.bufferedOutput.flush();
                    p = queuedPackets.take();
                }

                if (p == proposalOfDeath) {
                    // Packet of death!
                    break;
                }

                learner.messageTracker.trackSent(p.getType());
                learner.leaderOs.writeRecord(p, "packet");
            } catch (IOException e) {
                handleException(this.getName(), e);
                break;
            } catch (InterruptedException e) {
                handleException(this.getName(), e);
                break;
            }
        }

        LOG.info("LearnerSender exited");
    }

    public void queuePacket(QuorumPacket pp) throws IOException {
        if (pp == null) {
            learner.bufferedOutput.flush();
        } else {
            queuedPackets.add(pp);
        }
    }

    public void shutdown() {
        LOG.info("Shutting down LearnerSender");
        queuedPackets.clear();
        queuedPackets.add(proposalOfDeath);
    }
}
