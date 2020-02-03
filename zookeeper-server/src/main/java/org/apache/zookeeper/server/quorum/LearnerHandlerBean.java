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

import java.net.InetSocketAddress;
import java.net.Socket;
import javax.management.ObjectName;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LearnerHandlerBean implements LearnerHandlerMXBean, ZKMBeanInfo {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerHandlerBean.class);

    private final LearnerHandler learnerHandler;
    private final String remoteAddr;

    public LearnerHandlerBean(final LearnerHandler learnerHandler, final Socket socket) {
        this.learnerHandler = learnerHandler;
        InetSocketAddress sockAddr = (InetSocketAddress) socket.getRemoteSocketAddress();
        if (sockAddr == null) {
            this.remoteAddr = "Unknown";
        } else {
            this.remoteAddr = sockAddr.getAddress().getHostAddress() + ":" + sockAddr.getPort();
        }
    }

    @Override
    public String getName() {
        return MBeanRegistry.getInstance()
                            .makeFullPath(
                                "Learner_Connections",
                                ObjectName.quote(remoteAddr),
                                String.format("\"id:%d\"", learnerHandler.getSid()));
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public void terminateConnection() {
        LOG.info("terminating learner handler connection on demand {}", toString());
        learnerHandler.shutdown();
    }

    @Override
    public String toString() {
        return "LearnerHandlerBean{remoteIP=" + remoteAddr + ",ServerId=" + learnerHandler.getSid() + "}";
    }

}
