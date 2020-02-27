package org.apache.zookeeper.server.quorum;

/**
 * This class track the ack time between leader and learner, and return true if it
 * exceeds the sync timeout, mainly used to track lagging learner.
 *
 * It keeps track of only one message at a time, when the ack for that
 * message arrived, it will switch to track the last packet we've sent.
 */
public interface PingLaggingDetector {

    void start();

    void trackMessage(long zxid, long time);

    void trackAck(long zxid);

    boolean isLagging(long time);
}
