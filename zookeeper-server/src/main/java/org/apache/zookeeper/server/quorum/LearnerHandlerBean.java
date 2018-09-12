package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.net.InetSocketAddress;
import java.net.Socket;

public class LearnerHandlerBean implements LearnerHandlerMXBean, ZKMBeanInfo{
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
        return MBeanRegistry.getInstance().makeFullPath("Learner_Connections", ObjectName.quote(remoteAddr),
                String.format("\"id:%d\"", learnerHandler.getSid()));
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public void terminateConnection() {
        LOG.info("terminating learner handler connection on demand " + toString());
        learnerHandler.shutdown();
    }

    @Override
    public String toString() {
        return "LearnerHandlerBean{remoteIP=" + remoteAddr + ",ServerId=" + learnerHandler.getSid() + "}";
    }
}
