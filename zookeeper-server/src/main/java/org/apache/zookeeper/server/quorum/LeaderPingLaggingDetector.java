package org.apache.zookeeper.server.quorum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class track the ack time between leader and learner, and return true if it
 * exceeds the sync timeout, used to track lagging connections between leader and learner.
 *
 * It keeps track of only one message at a time, when the ack for that
 * message arrived, it will switch to track the last packet we've sent.
 */
public class LeaderPingLaggingDetector implements PingLaggingDetector {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderPingLaggingDetector.class);

    private boolean started = false;
    private long currentZxid = 0;
    private long currentTime = 0;
    private long nextZxid = 0;
    private long nextTime = 0;

    private final int laggingThreshold;

    public LeaderPingLaggingDetector(int laggingThreshold) {
        this.laggingThreshold = laggingThreshold;
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void trackMessage(long zxid, long time) {
        if (!started) {
            return;
        }

        // On the leader side, we do not track every ping--we track at least one ping in each
        // lagging threshold window, that is, the two pings we track are no longer than the
        // lagging threshold apart. Lagging is detected before sessions expire because session
        // expiration timer is reset whenever an ack is received and the lagging threshold is
        // a little shorter than the session timeout.
        if (currentTime == 0) {
            currentTime = time;
            currentZxid = zxid;
        } else {
            nextTime = time;
            nextZxid = zxid;
        }
    }

    @Override
    public synchronized void trackAck(long zxid) {
        if (currentZxid == zxid) {
            currentTime = nextTime;
            currentZxid = nextZxid;
            nextTime = 0;
            nextZxid = 0;
        } else if (nextZxid == zxid) {
            LOG.warn("Response for 0x{} received before 0x{}",
                    Long.toHexString(zxid), Long.toHexString(currentZxid));
            nextTime = 0;
            nextZxid = 0;
        }
    }

    @Override
    public synchronized boolean isLagging(long time) {
        if (currentTime == 0) {
            return false;
        } else {
            long msDelay = (time - currentTime) / 1000000;
            return msDelay >= laggingThreshold;
        }
    }
}
